package com.edunext.edutrack.api.security.portal;

import com.edunext.edutrack.api.security.PrincipalType;
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
 * A-126 · the route trees, through the real chain.
 *
 * <h2>Both directions, and the portal half needs care</h2>
 *
 * <p>Nothing serves {@code /api/v1/portal/**} yet — the tree is declared and
 * empty until the portal endpoints land — so a request there answers 404
 * whether or not this filter exists. A status assertion would therefore pass
 * with the gate deleted, which is the trap {@code ModuleAccessFilterTest}
 * already had to be rescued from once.
 *
 * <p>So the portal assertions discriminate on the <b>body</b>: the gate's own
 * detail string against Spring's "No static resource …".
 *
 * <p>The staff-route direction asserts on status instead — but on <b>405</b>
 * from an unmapped method, not on "not 404" from a real GET. A GET reaches the
 * handler, which opens a transaction; 405 comes from the handler mapping, behind
 * the whole filter chain and in front of every controller, and needs no
 * datasource. See {@code Staff#reachesStaffRoutes} for what that cost the first
 * time.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PortalRouteFilterTest {

    private static final String PORTAL = "/api/v1/portal/onboarding/journeys";
    private static final String STAFF = "/api/v1/onboarding/dashboard/summary";
    private static final String TICKETS = "/api/v1/tickets";

    /** The gate's fingerprint in a body — distinct from the framework's 404. */
    private static final String GATE_DETAIL = "No resource was found at this path.";

    @Autowired
    MockMvc mvc;

    private static JwtAuthenticationToken staff() {
        return token(Jwt.withTokenValue("staff")
                .header("alg", "HS256")
                .subject("1")
                .claim("role", "ADMIN")
                .claim("projects", List.of())
                .claim("modules", List.of("TICKETING", "ONBOARDING"))
                .claim("moduleRoles", Map.of("ONBOARDING", "OB_ADMIN")));
    }

    private static JwtAuthenticationToken client() {
        return token(Jwt.withTokenValue("client")
                .header("alg", "HS256")
                .subject("55")
                .claim(PrincipalType.CLAIM, "CLIENT")
                .claim("client_id", 7)
                .claim("ob_client_id", 9));
    }

    private static JwtAuthenticationToken token(Jwt.Builder builder) {
        return new JwtAuthenticationToken(
                builder.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build(),
                List.of());
    }

    @Nested
    @DisplayName("a client principal")
    class Client {

        @Test
        @DisplayName("is refused a staff route, with 404 and not 403")
        void isRefusedStaffRoutes() throws Exception {
            // A 403 would confirm the endpoint to somebody outside the
            // organisation. The staff surface is where owners, internal
            // comments, escalations and TAT internals live — the plan's
            // never-visible list.
            mvc.perform(get(STAFF).with(authentication(client())))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL));
        }

        @Test
        @DisplayName("is refused the ticketing tree too")
        void isRefusedTicketing() throws Exception {
            mvc.perform(get(TICKETS).with(authentication(client())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL));
        }

        @Test
        @DisplayName("reaches the portal tree — the module gate does not refuse them")
        void reachesThePortal() throws Exception {
            // THE INTERACTION THIS FILTER EXISTS TO GET RIGHT. ModuleAccessGuard
            // treats an absent CallerIdentity as blocked and a client token
            // produces none, so without the deferral in ModuleAccessFilter the
            // module gate 404s every client off their own portal — with its own
            // detail string, which is what this distinguishes.
            //
            // Nothing serves the portal tree yet, so the 404 here is the
            // framework's. What must NOT appear is a gate refusal.
            mvc.perform(get(PORTAL).with(authentication(client())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.not(GATE_DETAIL)));
        }
    }

    @Nested
    @DisplayName("a staff principal")
    class Staff {

        @Test
        @DisplayName("is refused the portal tree by this gate")
        void isRefusedThePortal() throws Exception {
            // The mirror, and the less obvious half: a staff user must not learn
            // whether a given client has a portal login, which a 403 would
            // disclose. Asserted on the body because the tree is empty and a
            // status check would pass with the gate deleted.
            mvc.perform(get(PORTAL).with(authentication(staff())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(GATE_DETAIL));
        }

        @Test
        @DisplayName("still reaches its own routes")
        void reachesStaffRoutes() throws Exception {
            // The counterweight. A filter that refused everybody would satisfy
            // every assertion above and be entirely broken.
            //
            // DELETE, and the method is the whole care of these two tests —
            // the correction ModuleAccessFilterTest's own counterweight took one
            // commit earlier, for the same reason and on the same route. Only
            // GET is mapped on the onboarding dashboard summary, so DELETE is
            // answered 405 by the handler mapping, which sits behind the entire
            // filter chain and in front of every controller. That is the
            // narrowest place a response can prove this filter passed the
            // request on: a 405 is unreachable if the gate refused, and it is
            // produced without invoking a handler, so it asks no service and
            // opens no transaction.
            //
            // This asserted `get(STAFF)` first and it cost a red build. The
            // summary is isAuthenticated(), which this caller is, so the request
            // ran the real handler, which opened a transaction and asked for a
            // connection: green on a developer's machine with the compose stack
            // up, and CannotGetJdbcConnectionException in CI, where MySQL exists
            // for the integration tests and does not carry the application's
            // credentials. A test that passes or fails on whether a database
            // happens to be reachable is testing the machine, not the filter.
            mvc.perform(delete(STAFF).with(authentication(staff())))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("still reaches the ticketing tree")
        void reachesTicketing() throws Exception {
            // Only GET and POST are mapped on /api/v1/tickets — same reasoning
            // as the test above.
            mvc.perform(delete(TICKETS).with(authentication(staff())))
                    .andExpect(status().isMethodNotAllowed());
        }
    }
}
