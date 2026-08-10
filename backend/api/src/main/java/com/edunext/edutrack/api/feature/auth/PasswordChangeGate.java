package com.edunext.edutrack.api.feature.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A-026 · the "forced" half of the forced password change. Blueprint §10.1:
 * {@code → must_change_password? → force reset screen}.
 *
 * <h2>Why this is a server decision at all</h2>
 *
 * <p>Before A-026 the flag was reported to the client and enforced nowhere: login
 * returned {@code mustChangePassword} in the {@link Session} body and the SPA was
 * trusted to route to S-03. That is a suggestion, not a control. The token issued
 * alongside it is a fully privileged 15-minute credential, so anyone willing to
 * skip the redirect — a stale tab, a generated client, curl — operates the whole
 * application on a password an administrator generated, emailed in plain text,
 * and can still read. The flag has to be able to refuse requests, and only the
 * server can refuse.
 *
 * <h2>The claim, and why absence means "not required"</h2>
 *
 * <p>The decision reads {@link AccessTokenIssuer#MUST_CHANGE_PASSWORD_CLAIM} from
 * the verified token rather than the {@code users} row, so it costs no query on a
 * path that runs on every authenticated request. The claim is emitted only when
 * true, so an absent claim reads as "not required" — the fail-open direction,
 * chosen on purpose. Reading absence as "required" would be fail-closed in the
 * abstract and a total outage in practice: every token minted before this task,
 * and every token that will ever be minted for the overwhelming majority of
 * users, has no such claim. The claim is inside a signature we verify, so its
 * absence cannot be arranged by a caller — only by us failing to emit it, which
 * {@code AccessTokenIssuerTest} is what stops.
 *
 * <p>The claim can be up to 15 minutes stale after a successful change, which is
 * why {@link PasswordChangeService} revokes the token that performed the change:
 * the client refreshes, {@code AuthenticationService#resolveActiveUser} re-reads
 * the row, and the successor carries no claim. Without that revocation a user
 * would change their password successfully and stay locked out of the
 * application until their access token expired.
 *
 * <h2>What stays reachable</h2>
 *
 * <p>Three routes, and the list is short on purpose — every addition is a route a
 * user who has not yet rotated a known-compromised password may still call.
 *
 * <ul>
 *   <li>{@code PATCH /me/password} — the way out. Blocking it would be a deadlock.</li>
 *   <li>{@code POST /auth/logout} — someone who does not want to set a password
 *       right now must still be able to end their session rather than being held
 *       in it.</li>
 *   <li>{@code POST /auth/refresh} — the change is only observed by the server on
 *       the next refresh, so blocking it would strand the user on the stale claim
 *       described above. It is also cookie-authenticated and carries no bearer
 *       token, so in practice it never reaches this decision; it is listed so
 *       that stays true by intent rather than by accident.</li>
 * </ul>
 *
 * <p>{@code GET /me} is deliberately <b>not</b> here. Login already returned the
 * user object S-03 needs to render, so nothing is lost, and every route left open
 * is one more thing a not-yet-rotated credential can read.
 *
 * <h2>Built now, enforced at A-032</h2>
 *
 * <p><b>Nothing calls this yet.</b> The only authenticated routes that exist are
 * logout and the change-password endpoint itself, and both are on the allowlist —
 * so today there is literally nothing for the gate to refuse. The reader is
 * A-032's filter chain, which will consult {@link #blocks} on every authenticated
 * request. This is the same "hook built with its tests, enforcement in the task
 * that owns the chain" shape as A-023's device fingerprint (enforced by A-024)
 * and A-025's access-token blacklist (enforced by A-032), and it is called out
 * because a decision that is written and never asked looks like dead code on
 * reading and is instead the first half of a two-task change.
 */
@Component
class PasswordChangeGate {

    /**
     * Full request paths including the {@code /api/v1} prefix, because that is
     * what {@code HttpServletRequest#getRequestURI} hands the filter chain.
     */
    static final Set<String> ALWAYS_ALLOWED = Set.of(
            "/api/v1/me/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh");

    /**
     * Whether this request must be refused with
     * {@link PasswordChangeRequiredException}.
     *
     * <p>Exact matching, not {@code startsWith}. A prefix rule would let
     * {@code /api/v1/me/password/../../tickets} through on any container that
     * normalises after the check, and the allowlist is three fixed strings —
     * there is no path parameter to accommodate.
     *
     * @param accessToken a token already verified by {@link AccessTokenVerifier}.
     *                    Passing an unverified one would make the claim below
     *                    caller-controlled, which is the whole attack.
     * @param requestPath the servlet path, e.g. {@code /api/v1/tickets}
     */
    boolean blocks(Jwt accessToken, String requestPath) {
        if (!Boolean.TRUE.equals(accessToken.getClaim(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM))) {
            return false;
        }
        return !ALWAYS_ALLOWED.contains(requestPath);
    }
}
