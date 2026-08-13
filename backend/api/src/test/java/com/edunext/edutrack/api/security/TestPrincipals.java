package com.edunext.edutrack.api.security;

import com.edunext.edutrack.api.security.jwt.JwtAuthoritiesConverter;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

/**
 * A-036 · a caller of a given role, built the way the application builds one.
 *
 * <p>Deliberately routed through the production {@link JwtAuthoritiesConverter}
 * rather than by handing MockMvc a list of authorities. Supplying authorities
 * directly would make every suite that used it pass while the claim-to-authority
 * mapping was broken — the token would never be read, so the mapping would never
 * be exercised, and the permission matrix would be asserting Spring's behaviour
 * on an input this application does not produce. Going through the converter
 * means a converter that stopped emitting permissions fails here too.
 *
 * <p>The claims are the four A-022 mints: {@code sub}, {@code role},
 * {@code permissions} and {@code projects}. {@code permissions} comes from
 * {@link RolePermissions}, the static mirror {@code PermissionCatalogTest} pins
 * to the seed migration — so a role's grants here cannot quietly diverge from
 * the ones in the database.
 */
public final class TestPrincipals {

    /** The default fixture user id. Any suite that cares supplies its own. */
    public static final long DEFAULT_USER_ID = 1L;

    private TestPrincipals() {
    }

    /** A caller of {@code roleCode} belonging to no projects. */
    public static AbstractAuthenticationToken of(JwtAuthoritiesConverter authorities, String roleCode) {
        return of(authorities, DEFAULT_USER_ID, roleCode, List.of());
    }

    /**
     * A caller of {@code roleCode}, with the project membership §10.2 scopes
     * PM and Support by.
     */
    public static AbstractAuthenticationToken of(JwtAuthoritiesConverter authorities,
                                                 long userId,
                                                 String roleCode,
                                                 List<Long> projectIds) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("role", roleCode)
                .claim("permissions", List.copyOf(RolePermissions.of(roleCode)))
                .claim("projects", List.copyOf(projectIds))
                .build();
        return authorities.convert(jwt);
    }
}
