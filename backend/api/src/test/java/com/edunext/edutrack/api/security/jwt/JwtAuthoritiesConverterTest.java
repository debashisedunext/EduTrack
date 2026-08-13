package com.edunext.edutrack.api.security.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A-033 · the claim-to-authority mapping, on its own.
 *
 * <p>{@code RouteAuthorizationTest} exercises this class through the real chain,
 * which proves it works for the tokens that test builds. What it cannot cover is
 * the malformed input — an absent claim, a claim of the wrong type — because a
 * token our own issuer minted never has any. Those are the cases that decide
 * whether a bad token is a 401 or a 500, so they are asserted directly.
 */
class JwtAuthoritiesConverterTest {

    private final JwtAuthoritiesConverter converter = new JwtAuthoritiesConverter();

    @Test
    void mapsTheRoleClaimToASpringRoleAuthorityAndPermissionsVerbatim() {
        var authorities = authoritiesOf(token(Map.of(
                "role", "PM",
                "permissions", List.of("ticket.assign", "ticket.close"))));

        // ROLE_ prefixed so hasRole('PM') matches; permissions unprefixed so
        // hasAuthority('ticket.assign') reads as the code in the §2 matrix.
        assertThat(authorities)
                .containsExactlyInAnyOrder("ROLE_PM", "ticket.assign", "ticket.close");
    }

    @Test
    void theSubjectStaysThePrincipalName() {
        // A-034's ScopeResolver compares Authentication#getName against
        // assigned_to, and A-022 made `sub` the numeric user id for exactly
        // that. Switching the principal name to the username here would break
        // row scoping from a long way away.
        assertThat(converter.convert(token(Map.of("role", "ADMIN"))).getName()).isEqualTo("42");
    }

    @Test
    void anAbsentPermissionsClaimGrantsNoPermissions() {
        // Fail closed. The alternative — inferring the role's grants from the
        // static matrix — would mean holding permissions the token does not say
        // you hold, which is a different thing from what the token was signed
        // for. RolePermissions exists for dev-noauth, which has no token.
        assertThat(authoritiesOf(token(Map.of("role", "DEVELOPER"))))
                .containsExactly("ROLE_DEVELOPER");
    }

    @Test
    void aMalformedPermissionsClaimIsIgnoredRatherThanThrowing() {
        // Jwt#getClaimAsStringList throws when the claim is not a list, which
        // would turn a malformed token into a 500 on a request that should
        // simply be refused. Reading defensively keeps the failure a 401.
        assertThatCode(() -> converter.convert(token(Map.of(
                "role", "QA",
                "permissions", "ticket.create"))))
                .doesNotThrowAnyException();

        assertThat(authoritiesOf(token(Map.of("role", "QA", "permissions", "ticket.create"))))
                .containsExactly("ROLE_QA");

        // A list with the wrong element types keeps the entries it can use.
        assertThat(authoritiesOf(token(Map.of(
                "role", "QA",
                "permissions", List.of(1, "ticket.create", "")))))
                .containsExactlyInAnyOrder("ROLE_QA", "ticket.create");
    }

    @Test
    void anAbsentRoleClaimYieldsNoRoleAuthority() {
        assertThat(authoritiesOf(token(Map.of("permissions", List.of("reports.view")))))
                .containsExactly("reports.view");
    }

    @Test
    void theRoleAuthorityIsUpperCased() {
        // Nothing mints a lower-case role today; roles.code is upper-case. This
        // pins the normalisation anyway, because hasRole('PM') against an
        // authority of ROLE_pm fails silently and looks like a missing grant.
        assertThat(authoritiesOf(token(Map.of("role", "pm")))).containsExactly("ROLE_PM");
    }

    private List<String> authoritiesOf(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt token(Map<String, Object> claims) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("42")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
