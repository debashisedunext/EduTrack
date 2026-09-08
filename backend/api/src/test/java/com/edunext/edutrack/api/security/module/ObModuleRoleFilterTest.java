package com.edunext.edutrack.api.security.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-122 · §3's table applied, through the real chain.
 *
 * <h2>The pair of statuses is what this file is for</h2>
 *
 * <p>A caller with no onboarding standing gets 404; one with the wrong role
 * gets 403. Both are easy to get backwards, and both failures are quiet:
 * all-404 tells an OB Viewer their own module is broken, all-403 hands the
 * module's existence to everybody else. Neither shows up in a test that only
 * checks "the request was refused".
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ObModuleRoleFilterTest {

    /** ADMIN_ONLY by §3 — the template catalogue is OB Admin's alone. */
    private static final String PUBLISH = "/api/v1/onboarding/journey-templates/7/publish";
    /** EVERY_ROLE — Viewer sees everything read-only. */
    private static final String REPORTS = "/api/v1/onboarding/reports";
    /** STEP_ACTORS — working a live step. */
    private static final String START_STEP = "/api/v1/onboarding/journey-steps/3/start";
    /** ADMIN_AND_MANAGER — the moderator override. */
    private static final String SKIP_STEP = "/api/v1/onboarding/journey-steps/3/skip";
    /** No rule: outside the onboarding tree entirely. */
    private static final String TICKETS = "/api/v1/tickets";

    /**
     * The same two routes with an id no {@code long} can hold — and the whole
     * reason the permitted cases below can be asserted at all.
     *
     * <p>A refusal is answered by this filter, so it costs nothing. A
     * <em>pass</em> is not: the request goes on to a real controller, which
     * opens a transaction and asks for a connection. There is no datasource in
     * surefire, so every permitted case here failed CI with
     * CannotGetJdbcConnectionException while passing on any machine with the
     * compose stack up — a test answering "is a database reachable" rather
     * than "did the filter refuse".
     *
     * <p>{@code PathPattern} matches a URI segment whatever is in it, so these
     * hit exactly the same rule as their numeric forms; Spring then fails to
     * bind {@code @PathVariable long} and answers <b>400</b> during argument
     * resolution, before the handler body runs. So 400 means the filter passed
     * the request on, 403 means it refused, and neither outcome needs MySQL.
     * {@code ModuleAccessFilterTest#doesNotGuardTicketing} makes the same move
     * with an unmapped method one tree over.
     */
    private static final String PUBLISH_UNBINDABLE_ID =
            "/api/v1/onboarding/journey-templates/not-an-id/publish";
    private static final String START_STEP_UNBINDABLE_ID =
            "/api/v1/onboarding/journey-steps/not-an-id/start";

    private static final String GATE_404 = "No resource was found at this path.";
    private static final String ROLE_403 = "Your onboarding role does not permit this action.";

    @Autowired
    MockMvc mvc;

    /** Holds ONBOARDING, so the module gate passes them to this filter. */
    private static JwtAuthenticationToken caller(String moduleRole) {
        Jwt.Builder builder = Jwt.withTokenValue("test")
                .header("alg", "HS256")
                .subject("1")
                .claim("role", "ADMIN")
                .claim("projects", List.of())
                .claim("modules", List.of("TICKETING", "ONBOARDING"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        builder.claim("moduleRoles", moduleRole == null ? Map.of() : Map.of("ONBOARDING", moduleRole));
        return new JwtAuthenticationToken(builder.build(), List.of());
    }

    @Nested
    @DisplayName("the wrong onboarding role")
    class WrongRole {

        @Test
        @DisplayName("is refused with 403, not 404 — this caller knows the module exists")
        void isForbiddenNotNotFound() throws Exception {
            // A 404 here would withhold nothing from somebody who holds a role
            // in the module, and would read as "that endpoint is gone" — the
            // answer that produces a bug report instead of a request for a grant.
            mvc.perform(post(PUBLISH).with(authentication(caller(ObModuleRoleRules.OB_VIEWER))))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value(ROLE_403));
        }

        @Test
        @DisplayName("the refusal names no role, neither held nor required")
        void disclosesNoRoleNames() throws Exception {
            // "You are OB_VIEWER, this needs OB_ADMIN" is a map of the
            // permission model handed out one refusal at a time.
            mvc.perform(post(PUBLISH).with(authentication(caller(ObModuleRoleRules.OB_SALES))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("OB_"))));
        }

        @Test
        @DisplayName("Sales cannot work a live step, and Viewer cannot either")
        void neitherSalesNorViewerActsOnASte() throws Exception {
            // §3: Sales views progress, Viewer is read-only. Neither holds a
            // verb over a running step.
            for (String role : List.of(ObModuleRoleRules.OB_SALES, ObModuleRoleRules.OB_VIEWER)) {
                mvc.perform(post(START_STEP).with(authentication(caller(role))))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("a Step Owner may work a step but not override one")
        void stepOwnerIsNotAModerator() throws Exception {
            // The distinction the one pre-existing check already drew:
            // skip is the moderator's, start is the owner's.
            mvc.perform(post(SKIP_STEP).with(authentication(caller(ObModuleRoleRules.OB_STEP_OWNER))))
                    .andExpect(status().isForbidden());
            // The permitted half, on the unbindable id: a 400 from argument
            // binding is only reachable once this filter has passed the
            // request on, and it reaches no service.
            mvc.perform(post(START_STEP_UNBINDABLE_ID)
                            .with(authentication(caller(ObModuleRoleRules.OB_STEP_OWNER))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("no onboarding standing at all")
    class NoStanding {

        @Test
        @DisplayName("is refused with 404, so the module's existence is not disclosed")
        void isNotFoundNotForbidden() throws Exception {
            // The module gate's reasoning, one level in: a 403 would tell
            // somebody the onboarding module is deployed.
            mvc.perform(post(PUBLISH).with(authentication(caller(null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_404));
        }

        @Test
        @DisplayName("even on a route every role may reach")
        void appliesToReadsToo() throws Exception {
            mvc.perform(get(REPORTS).with(authentication(caller(null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_404));
        }
    }

    @Nested
    @DisplayName("the right role")
    class RightRole {

        @Test
        @DisplayName("OB Admin reaches the template catalogue")
        void adminMayPublish() throws Exception {
            // The counterweight to WrongRole: ADMIN_ONLY has to admit Admin,
            // not merely refuse everybody. A rule set that refused all five
            // roles would satisfy every assertion in the nested class above.
            //
            // 400, not 200, and not "anything but 403" — see
            // PUBLISH_UNBINDABLE_ID. An exact status is worth more than a
            // negative one here: not(403) was also satisfied by the failure
            // this test produced in CI when the handler ran and found no
            // database, which is how it passed review and broke the build.
            mvc.perform(post(PUBLISH_UNBINDABLE_ID)
                            .with(authentication(caller(ObModuleRoleRules.OB_ADMIN))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("every role reaches a read §3 gives everybody")
        void everyRoleReadsReports() throws Exception {
            for (String role : ObModuleRoleRules.ALL_ROLES) {
                mvc.perform(get(REPORTS).with(authentication(caller(role))))
                        .andExpect(status().is(org.hamcrest.Matchers.not(403)))
                        .andExpect(status().is(org.hamcrest.Matchers.not(404)));
            }
        }

        @Test
        @DisplayName("a route with no rule is untouched")
        void unruledRoutesPass() throws Exception {
            // The counterweight. A filter that refused everything, or that
            // failed closed on an unruled route, would satisfy every assertion
            // above — and would 403 the whole ticketing product.
            //
            // DELETE, because only GET and POST are mapped on /api/v1/tickets:
            // the handler mapping answers 405 from behind the filter chain and
            // in front of every controller, so a pass is visible without
            // running a handler that would want a database. This is
            // ModuleAccessFilterTest#doesNotGuardTicketing's move, for its
            // reasons, one filter later.
            mvc.perform(delete(TICKETS).with(authentication(caller(null))))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
