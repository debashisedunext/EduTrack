package com.edunext.edutrack.api.security.dev;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Does the {@code dev-noauth} principal actually survive the security chain?
 *
 * <p>Nothing proved this before. {@link DevNoAuthFilterTest} exercises the
 * filter alone, and every feature test so far — {@code ChatEngineIT},
 * {@code NotificationCentreIT} — calls its service directly and never travels
 * through HTTP. So the whole identity path was assumed rather than checked,
 * which is what D-051 recorded as unverified.
 *
 * <p>It matters beyond tidiness. {@code DevNoAuthFilter} is registered as a
 * servlet filter ahead of Spring Security's chain, and
 * {@code SecurityContextHolderFilter} <em>replaces</em> the held context with a
 * deferred one loaded from its repository. If that wins, every call into
 * {@code CurrentUser.idOf} throws and chat and notifications are unusable in
 * the only profile that has an identity at all.
 *
 * <p>This is also the prerequisite for D-013: a subscription cannot be
 * authorised against a principal that never arrives.
 */
@SpringBootTest(properties = {
        // B-023, Stream B edit — flagged for Shivendra's sign-off rather than
        // made quietly (CLAUDE.md, code ownership).
        //
        // JPA is no longer excluded: `CalendarService` is the first bean in this
        // module to read through a Spring Data repository, so without it the
        // context cannot build `CalendarController`. Naming the dialect and
        // refusing the JDBC metadata lookup lets Hibernate build an
        // EntityManagerFactory without connecting, so this still runs with
        // nothing installed. Flyway stays excluded — it connects regardless.
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "dev-noauth"})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
class DevPrincipalReachesRequestsTest {

    @TestConfiguration
    static class Probe {

        /** Reports what a controller actually receives, by both routes. */
        @RestController
        static class WhoAmIController {

            @GetMapping("/__test__/whoami")
            String whoAmI(Authentication authentication, Principal principal) {
                String fromAuthentication = authentication == null
                        ? "none"
                        : authentication.getPrincipal().getClass().getSimpleName();
                String fromPrincipal = principal == null ? "none" : "present";
                return fromAuthentication + "/" + fromPrincipal;
            }

            /**
             * A-032 · the same probe under {@code /api}, which the real chain
             * requires authentication for.
             */
            @GetMapping("/api/v1/__test__/whoami")
            String whoAmIBehindTheChain(Authentication authentication, Principal principal) {
                return whoAmI(authentication, principal);
            }
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    MockMvc mockMvc;

    @Test
    void theFakePrincipalReachesAController() throws Exception {
        mockMvc.perform(get("/__test__/whoami"))
                .andExpect(status().isOk())
                // Both matter: `Authentication` is what CurrentUser reads, and
                // `Principal` is what the STOMP handshake resolves the socket
                // user from.
                .andExpect(content().string("DevPrincipal/present"));
    }

    @Test
    void theFakePrincipalSatisfiesTheRealFilterChain() throws Exception {
        // A-032 · the assertion this profile's whole purpose rests on, and which
        // the test above could not make: `/__test__/whoami` falls under the
        // chain's permitAll for non-API paths, so it would keep passing even if
        // the dev principal never satisfied anything. This path is inside
        // `/api/**`, which A-032 requires authentication for.
        //
        // It works because DevNoAuthFilter registers at HIGHEST_PRECEDENCE + 50,
        // ahead of Spring Security's proxy at -100, and stores the context in the
        // same RequestAttributeSecurityContextRepository the chain reads under
        // SessionCreationPolicy.STATELESS. Assert it rather than reason about it:
        // that alignment is invisible from either file alone, and a future change
        // to the session policy would break B, C and D without touching them.
        mockMvc.perform(get("/api/v1/__test__/whoami"))
                .andExpect(status().isOk())
                .andExpect(content().string("DevPrincipal/present"));
    }
}
