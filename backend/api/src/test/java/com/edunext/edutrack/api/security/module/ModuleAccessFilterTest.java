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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-111, second half · proof that the module gate is now called.
 *
 * <h2>Why a chain test and not more unit tests</h2>
 *
 * <p>{@code ModuleAccessGuardTest} already covers the decision — which paths
 * the guard has an opinion about and who it lets through — and every one of
 * those tests passed for the three weeks the guard was <b>never invoked</b>.
 * That is the whole point of this file: the thing that was missing was not
 * logic but a caller, so the assertion has to go through the real filter chain
 * or it proves the same nothing again.
 *
 * <p>No infrastructure, the way {@code RouteAuthorizationTest} does it, so this
 * runs in surefire on every build rather than only where Docker is installed.
 * A refusal happens before the handler, so the requests below reach no service
 * and no datasource.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class ModuleAccessFilterTest {

    /** A guarded path that a real controller answers, so a pass is visible as "not 404". */
    private static final String ONBOARDING = "/api/v1/onboarding/dashboard/summary";
    private static final String PORTAL = "/api/v1/portal/onboarding/journeys";
    /** Outside both guarded trees. */
    private static final String TICKETING = "/api/v1/tickets";
    /** {@code ModuleAccessFilter}'s own refusal text — its fingerprint in a body. */
    private static final String GATE_DETAIL = "No resource was found at this path.";

    @Autowired
    MockMvc mvc;

    private static JwtAuthenticationToken caller(List<String> modules) {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "HS256")
                .subject("1")
                .claim("role", "ADMIN")
                .claim("projects", List.of())
                .claim("modules", modules)
                // A-122 · a real grant always carries a module_role — the
                // column is NOT NULL — and as of A-122 a caller holding the
                // module with no role recorded is refused 404 by
                // ObModuleRoleFilter, because they can do nothing either way.
                // Map.of() was a fixture no database can produce, and it made
                // `passesTheGate` assert that such a caller reaches a handler.
                // This file is about the MODULE gate; the role rules have
                // ObModuleRoleFilterTest.
                .claim("moduleRoles", modules.contains("ONBOARDING")
                        ? Map.of("ONBOARDING", "OB_ADMIN")
                        : Map.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    @Nested
    @DisplayName("a caller without the ONBOARDING module")
    class Unentitled {

        @Test
        @DisplayName("is refused on the onboarding tree, with 404 and not 403")
        void isRefusedOnOnboarding() throws Exception {
            // The status is the guard's argument: a 403 here would tell a
            // ticketing-only user that the onboarding module is deployed, which
            // is a fact about what the organisation bought disclosed to
            // somebody the organisation decided should not have it.
            mvc.perform(get(ONBOARDING).with(authentication(caller(List.of("TICKETING")))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("is refused on the portal tree by THIS filter, not by the absent handler")
        void isRefusedOnPortal() throws Exception {
            // /api/v1/portal/ is in GUARDED_PREFIXES because plan §2.3 puts the
            // client principal's routes in their own tree and those are
            // onboarding routes as well. A staff caller without the module must
            // not reach them either.
            //
            // ASSERTED ON THE BODY, NOT THE STATUS, AND THAT IS THE WHOLE
            // CARE OF THIS TEST. Nothing serves /api/v1/portal/** yet — A-126
            // builds that tree — so the path answers 404 whether or not this
            // filter exists. A status assertion here would pass with the filter
            // deleted, which is the failure mode this file was written to stop
            // repeating. The gate's own detail string is what distinguishes its
            // refusal from the framework's, and `passesOnPortalIntoAnEmptyTree`
            // below is the other half of the pair.
            mvc.perform(get(PORTAL).with(authentication(caller(List.of("TICKETING")))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL));
        }

        @Test
        @DisplayName("gets a problem+json body that never mentions modules")
        void refusalDisclosesNothing() throws Exception {
            // A detail naming the entitlement would hand back exactly the fact
            // the 404 exists to withhold, in the one place a reader would look.
            mvc.perform(get(ONBOARDING).with(authentication(caller(List.of("TICKETING")))))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL))
                    .andExpect(jsonPath("$.instance").value(ONBOARDING));
        }

        @Test
        @DisplayName("holding no modules at all is refused the same way")
        void refusesAnEmptyGrant() throws Exception {
            mvc.perform(get(ONBOARDING).with(authentication(caller(List.of()))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("still reaches the ticketing tree, which this gate has no opinion about")
        void doesNotGuardTicketing() throws Exception {
            // The counterweight. A filter that refused everything would satisfy
            // every assertion above and be entirely broken.
            //
            // DELETE, and the method is the whole care of this test. Only GET
            // and POST are mapped on /api/v1/tickets, so DELETE is answered 405
            // by the handler mapping — which sits behind the entire filter chain
            // and in front of every controller. That is the narrowest place a
            // response can prove this filter passed the request on: a 405 is
            // unreachable if the gate refused, and it is produced without
            // invoking a handler, so it asks no service and opens no
            // transaction.
            //
            // This asserted `get(TICKETING)` first and it cost a red build.
            // Listing tickets is `isAuthenticated()`, which this caller is, so
            // the request ran the real handler, which opened a transaction and
            // asked for a connection: green on a developer's machine with the
            // compose stack up, and CannotCreateTransactionException in CI,
            // where the MySQL service exists for the integration tests and does
            // not carry the application's credentials. A surefire test that
            // passes or fails on whether a database happens to be reachable is
            // testing the machine. The class note above promises these requests
            // "reach no service and no datasource" — that promise held for the
            // six refusals and was broken by this one.
            mvc.perform(delete(TICKETING).with(authentication(caller(List.of("TICKETING")))))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("a caller holding ONBOARDING")
    class Entitled {

        @Test
        @DisplayName("passes the gate — whatever the handler then answers, it is not this filter")
        void passesTheGate() throws Exception {
            // DELETE, for the reason `doesNotGuardTicketing` sets out one tree
            // over. Only GET is mapped on the dashboard summary, so DELETE is
            // answered 405 by the handler mapping — which sits behind every
            // filter and in front of every controller. A 405 is unreachable if
            // the gate refused, and it is produced without invoking a handler,
            // so this request asks no service and opens no transaction.
            //
            // This asserted `get(ONBOARDING)` and not404() until A-122, and it
            // was green only because the caller's empty `moduleRoles` made the
            // dashboard give up before it reached a database. Once the fixture
            // above started carrying the OB_ADMIN that a real grant always
            // carries, the same request ran the whole handler and CI answered
            // CannotGetJdbcConnectionException. The class note's promise that
            // these requests "reach no service and no datasource" was true of
            // the six refusals and was being kept, for this one, by a handler
            // that happened to fail early.
            mvc.perform(delete(ONBOARDING).with(authentication(caller(List.of("TICKETING", "ONBOARDING")))))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("is refused the portal tree anyway, now that A-126 owns it")
        void isStillRefusedThePortalTree() throws Exception {
            // REWRITTEN BY A-126, and the behaviour it asserted really did
            // change rather than the test being wrong.
            //
            // It used to pair with `isRefusedOnPortal`: both callers saw 404
            // because the tree was empty, and only the unentitled one carried
            // the gate's detail — which is what proved this filter ran. A-126
            // then made the portal tree clients-only, so an entitled staff
            // caller is refused there too. The pairing that discriminated is
            // gone because the two callers now get the same answer.
            //
            // The proof that THIS filter runs has not been lost: the two
            // onboarding-tree cases above still carry it, and PortalRouteFilterTest
            // owns the portal half. Kept rather than deleted so the change of
            // rule is visible at the place that used to assert the old one.
            mvc.perform(get(PORTAL).with(authentication(caller(List.of("ONBOARDING")))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL));
        }
    }
}
