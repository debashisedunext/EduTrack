package com.edunext.edutrack.api.security.jwt;

import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A-033 · turns the {@code role} and {@code permissions} claims A-022 has been
 * minting since week 4 into the authorities {@code @PreAuthorize} actually
 * tests.
 *
 * <p><b>Without this class the whole permission model is inert, and silently
 * so.</b> Spring's stock {@code JwtGrantedAuthoritiesConverter} reads
 * {@code scope} and {@code scp} — OAuth2 scopes, which EduTrack does not issue.
 * An EduTrack access token carries neither, so every authenticated principal
 * arrived at the controller holding <em>zero</em> authorities: {@code
 * hasRole('ADMIN')} was false for an Admin, and {@code hasAuthority('master.write')}
 * was false for everybody. The failure mode is the dangerous direction of
 * quiet — a route that denies all six roles looks, in a review and in a smoke
 * test as an Admin, exactly like a route nobody got round to annotating.
 *
 * <h2>Two authority shapes, on purpose</h2>
 *
 * <ul>
 *   <li>{@code ROLE_ADMIN} … {@code ROLE_DEPLOYMENT} from the {@code role}
 *       claim, so {@code hasRole('ADMIN')} works and matches the convention
 *       {@code DevNoAuthFilter} has used since A-012.</li>
 *   <li>the dotted capability codes verbatim — {@code master.write},
 *       {@code ticket.assign} — from {@code permissions}, tested with
 *       {@code hasAuthority('…')}.</li>
 * </ul>
 *
 * <p>They cannot collide: a permission code is lower-case and dotted, a role
 * authority is prefixed and upper-case. <b>Prefer the permission in an
 * annotation.</b> A role check hard-codes today's matrix into Java and quietly
 * stops matching the {@code roles} table the moment S-09 lets an Admin grant
 * {@code master.write} to a seventh role; a permission check follows the grant.
 * Roles are for the handful of rules the §2 matrix expresses as a role and not
 * as a capability.
 *
 * <h2>The claim is trusted, and that is a 15-minute window</h2>
 *
 * <p>Permissions come from the token, not from a fresh {@code role_permissions}
 * read per request. The token is signed by us and its grants were read from the
 * database at login, so the cost is staleness: revoke a permission in S-09 and
 * the holder keeps it until their access token expires — at most
 * {@code edutrack.jwt.access-token-ttl}, 15 minutes. That is the same bound
 * A-025 accepted for logout before it added a blacklist, and it buys not doing a
 * join on every request. If an immediate revoke is ever needed, the mechanism
 * already exists: blacklist the {@code jti} the way logout does, which forces a
 * refresh and a fresh read.
 *
 * <p><b>Nothing is inferred from the role when the claim is missing.</b> An
 * absent or malformed {@code permissions} array yields no permission
 * authorities, so the request is refused by every annotated route. Falling back
 * to {@link RolePermissions} here would look like resilience and would in fact
 * be a way to hold permissions that the token does not say you hold — the
 * static map is for {@code dev-noauth}, which has no token to read.
 */
@Component
public class JwtAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String ROLE_CLAIM = "role";
    static final String PERMISSIONS_CLAIM = "permissions";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        String role = jwt.getClaimAsString(ROLE_CLAIM);
        if (role != null && !role.isBlank()) {
            authorities.add(new SimpleGrantedAuthority(
                    RolePermissions.ROLE_AUTHORITY_PREFIX + role.trim().toUpperCase(Locale.ROOT)));
        }

        for (String permission : permissionsOf(jwt)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }

        // The principal name stays the `sub` claim — the numeric user id, per
        // A-022. Authentication#getName is what CurrentUser and A-034's
        // ScopeResolver read to compare against assigned_to, and switching it to
        // the username here would break both from a distance.
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * {@code getClaimAsStringList} throws when the claim is present but not a
     * list, which would turn a malformed token into a 500 instead of a 401.
     * Read defensively: the claim is inside our own signature, but "inside a
     * signature we control" is a reason to expect it to be well-formed, not a
     * reason for the failure to be unhandled.
     */
    private static List<String> permissionsOf(Jwt jwt) {
        Object claim = jwt.getClaim(PERMISSIONS_CLAIM);
        if (!(claim instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(code -> !code.isBlank())
                .toList();
    }
}
