package com.edunext.edutrack.api.feature.portal;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * C-121 · {@code PasswordChangeGate}'s rule, for the CLIENT principal.
 *
 * <h2>Why this is an explicit call rather than a filter</h2>
 *
 * <p>{@code PasswordChangeGate} is enforced from {@code SecurityConfig}'s
 * filter chain, and this task was told to leave that file alone. Rather than
 * invent a second servlet filter of uncertain ordering relative to Spring
 * Security's own chain — a mistake that can silently run before
 * authentication has populated the security context — this is called
 * explicitly, as the first line of every portal-onboarding handler except the
 * one that sets the password. That mirrors how this module already enforces
 * its other business rules (module role, task ownership): inline, in the
 * service or controller, not via a filter. It is not the shape {@code
 * PasswordChangeGate} took, and the difference is recorded rather than
 * assumed equivalent — Stream A may prefer a proper filter-chain gate once it
 * can touch {@code SecurityConfig}.
 *
 * <p>A caller with no {@code mustChangePassword} claim — the overwhelming
 * majority, once a client has set their own password — passes with no cost.
 */
@Component
public class PortalPasswordChangeGate {

    /**
     * @throws PortalPasswordChangeRequiredException the token carries {@code
     *         mustChangePassword: true}
     */
    public void require(Authentication caller) {
        if (caller instanceof JwtAuthenticationToken jwtAuth) {
            Jwt token = jwtAuth.getToken();
            if (Boolean.TRUE.equals(token.getClaim(PortalAccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM))) {
                throw new PortalPasswordChangeRequiredException();
            }
        }
    }
}
