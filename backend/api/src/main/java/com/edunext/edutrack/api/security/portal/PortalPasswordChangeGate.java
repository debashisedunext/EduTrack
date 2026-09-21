package com.edunext.edutrack.api.security.portal;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The "forced" half of the client portal's forced password change.
 *
 * <h2>Why this has to be a server decision</h2>
 *
 * <p>A portal login is now issued with a temporary password that staff read off
 * a screen and hand over — see {@code PortalTemporaryPasswordProperties} for
 * why that replaced the link-only flow. The whole argument for it being an
 * acceptable credential to expose is that it buys exactly one session, and that
 * session can do nothing but replace it.
 *
 * <p>Reporting {@code mustChangePassword} on the login response and trusting
 * the SPA to route to the change form would make that a suggestion. The token
 * issued alongside it is a fully privileged fifteen-minute portal credential,
 * so anyone willing to skip the redirect — a stale tab, a generated client,
 * curl — operates the whole portal on a password an operator generated, read
 * aloud, and can still recall. {@code A-026}'s
 * {@code PasswordChangeGate} makes this argument for staff in almost these
 * words; it is the same argument on the more exposed of the two surfaces.
 *
 * <h2>The claim, and why absence means "not required"</h2>
 *
 * <p>Read from {@link ClientAccessTokenIssuer#MUST_CHANGE_PASSWORD_CLAIM} on
 * the verified token rather than from {@code client_accounts}, so it costs no
 * query on a path that runs on every authenticated portal request. The claim is
 * emitted only when true, so an absent claim reads as "not required" — see that
 * class for why fail-open is the correct direction here and fail-closed would
 * be a total outage.
 *
 * <h2>What stays reachable, and why the list is one entry long</h2>
 *
 * <p>Only {@code PATCH /api/v1/portal/me/password} — the way out. Blocking it
 * would be a deadlock.
 *
 * <p>Staff's gate allows three, and the other two have no counterpart here:
 * there is no portal refresh route (a portal session is one access token, no
 * rotation) and no portal logout route (ending a session is a client-side
 * discard). So the shorter list is the absence of those routes rather than a
 * narrower policy, and it should grow only when one of them is built.
 *
 * <p>{@code /portal/auth/login} is deliberately <b>not</b> here and does not
 * need to be: it is unauthenticated, so a caller reaching it carries no token
 * for this gate to read. Signing in again while the flag is set produces
 * another token with the same claim, which is correct — the way out is the
 * change, not a fresh login.
 */
@Component
public class PortalPasswordChangeGate {

    /**
     * Full request paths including the {@code /api/v1} prefix, because that is
     * what {@code HttpServletRequest#getRequestURI} hands the filter chain.
     */
    static final Set<String> ALWAYS_ALLOWED = Set.of("/api/v1/portal/me/password");

    /**
     * Whether this request must be refused.
     *
     * <p>Exact matching, not {@code startsWith}. A prefix rule would let
     * {@code /api/v1/portal/me/password/../onboarding} through on any container
     * that normalises after the check, and the allowlist is one fixed string —
     * there is no path parameter to accommodate.
     *
     * @param accessToken a token the resource server has already verified.
     *                    Passing an unverified one would make the claim below
     *                    caller-controlled, which is the whole attack.
     * @param requestPath the request URI, e.g. {@code /api/v1/portal/onboarding}
     */
    public boolean blocks(Jwt accessToken, String requestPath) {
        if (accessToken == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(accessToken.getClaim(ClientPrincipal.MUST_CHANGE_PASSWORD_CLAIM))) {
            return false;
        }
        return !ALWAYS_ALLOWED.contains(requestPath);
    }
}
