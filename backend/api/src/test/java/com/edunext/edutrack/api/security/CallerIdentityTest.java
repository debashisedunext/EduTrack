package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-034 · the two principal shapes must read as the same caller, and every
 * unreadable shape must read as no caller at all.
 *
 * <p>No Docker: this is claim-parsing, and the property under test is what the
 * class does with input, not what the database does with the result.
 * {@code TicketScopeIT} covers the other half.
 */
class CallerIdentityTest {

    @Nested
    @DisplayName("the real chain")
    class FromJwt {

        @Test
        @DisplayName("reads sub, role and projects off the token")
        void readsTheClaims() {
            Optional<CallerIdentity> caller = CallerIdentity.of(bearer(jwt("42", "PM", List.of(7, 9))));

            assertThat(caller).hasValueSatisfying(identity -> {
                assertThat(identity.userId()).isEqualTo(42L);
                assertThat(identity.roleCode()).isEqualTo("PM");
                assertThat(identity.projectIds()).containsExactly(7L, 9L);
            });
        }

        @Test
        @DisplayName("the role is upper-cased, so a lower-case claim still matches the §2 matrix")
        void normalisesTheRole() {
            assertThat(CallerIdentity.of(bearer(jwt("1", "developer", List.of()))))
                    .hasValueSatisfying(identity -> assertThat(identity.roleCode()).isEqualTo("DEVELOPER"));
        }

        @Test
        @DisplayName("JSON integers of either width become project ids")
        void coercesNumericClaims() {
            // Jackson hands back Integer for small values and Long for large
            // ones. Reading the claim as List<Long> would ClassCastException on
            // the common case, which is the failure that only shows up in
            // production where project ids are still small.
            Jwt token = jwt("1", "SUPPORT", List.of(3, 4_000_000_000L));

            assertThat(CallerIdentity.of(bearer(token)))
                    .hasValueSatisfying(identity ->
                            assertThat(identity.projectIds()).containsExactly(3L, 4_000_000_000L));
        }

        @Test
        @DisplayName("a missing projects claim is an empty list, not a null")
        void missingProjectsClaim() {
            Jwt token = Jwt.withTokenValue("t").header("alg", "HS256")
                    .subject("5").claim("role", "PM").build();

            assertThat(CallerIdentity.of(bearer(token)))
                    .hasValueSatisfying(identity -> assertThat(identity.projectIds()).isEmpty());
        }

        @Test
        @DisplayName("a malformed projects claim is an empty list, which for a PM is deny-all")
        void malformedProjectsClaim() {
            Jwt token = Jwt.withTokenValue("t").header("alg", "HS256")
                    .subject("5").claim("role", "PM").claim("projects", "7,9").build();

            assertThat(CallerIdentity.of(bearer(token)))
                    .hasValueSatisfying(identity -> assertThat(identity.projectIds()).isEmpty());
        }

        @Test
        @DisplayName("a non-numeric sub is nobody — it is not user 0")
        void nonNumericSubject() {
            assertThat(CallerIdentity.of(bearer(jwt("asha.rao", "ADMIN", List.of())))).isEmpty();
        }

        @Test
        @DisplayName("a token with no role claim is nobody")
        void missingRoleClaim() {
            Jwt token = Jwt.withTokenValue("t").header("alg", "HS256").subject("5").build();

            assertThat(CallerIdentity.of(bearer(token))).isEmpty();
        }
    }

    @Nested
    @DisplayName("dev-noauth")
    class FromDevPrincipal {

        @Test
        @DisplayName("reads the same four facts off the fake principal")
        void readsTheProperties() {
            Authentication authentication = dev(new DevPrincipal(
                    7L, "asha", "Asha Rao", "DEVELOPER", List.of(3L), List.of(11L)));

            assertThat(CallerIdentity.of(authentication)).hasValueSatisfying(identity -> {
                assertThat(identity.userId()).isEqualTo(7L);
                assertThat(identity.roleCode()).isEqualTo("DEVELOPER");
                assertThat(identity.projectIds()).containsExactly(3L);
            });
        }

        @Test
        @DisplayName("both chains produce an identical caller, which is the point of the class")
        void agreesWithTheRealChain() {
            CallerIdentity fromToken = CallerIdentity.of(bearer(jwt("7", "PM", List.of(3)))).orElseThrow();
            CallerIdentity fromProperties = CallerIdentity.of(dev(new DevPrincipal(
                    7L, "asha", "Asha Rao", "PM", List.of(3L), List.of()))).orElseThrow();

            assertThat(fromToken).isEqualTo(fromProperties);
        }
    }

    @Nested
    @DisplayName("nobody")
    class Unidentifiable {

        @Test
        @DisplayName("no authentication at all")
        void noAuthentication() {
            assertThat(CallerIdentity.of(null)).isEmpty();
        }

        @Test
        @DisplayName("an anonymous token is not a caller with an empty scope — it is not a caller")
        void anonymous() {
            Authentication anonymous = new AnonymousAuthenticationToken(
                    "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

            assertThat(CallerIdentity.of(anonymous)).isEmpty();
        }

        @Test
        @DisplayName("a principal shape nobody here recognises")
        void unknownPrincipal() {
            Authentication odd = new UsernamePasswordAuthenticationToken("a-bare-string", null, List.of());

            assertThat(CallerIdentity.of(odd)).isEmpty();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Jwt jwt(String subject, String role, List<? extends Number> projects) {
        return Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(subject)
                .claim("role", role)
                .claim("projects", projects)
                .build();
    }

    private static Authentication bearer(Jwt token) {
        return new JwtAuthenticationToken(token, List.of(), token.getSubject());
    }

    private static Authentication dev(DevPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
