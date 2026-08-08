package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * A-024 · refresh rotation with family revocation. Blueprint §10.1: "each
 * refresh issues a new token and invalidates the old jti. Re-use of a consumed
 * refresh token ⇒ token theft ⇒ revoke the whole family and force re-login."
 *
 * <p>This class exists because rotation is a <b>sequence</b>, and every property
 * it is supposed to hold comes from the order rather than from any one step. The
 * order below is the design; re-arranging it for readability breaks something
 * each time.
 *
 * <h2>Why reuse is detectable at all</h2>
 *
 * <p>The intuitive rotation deletes the old token and writes a new one. It also
 * makes §10.1 unenforceable: a deleted key and a key that never existed are the
 * same absence, so a stolen token replayed after the victim's browser has moved
 * on is indistinguishable from a garbage value. Detection needs the server to
 * remember that the token was <i>real and spent</i>, which is what
 * {@link RefreshTokenStore#markConsumed} is for.
 *
 * <h2>What a refusal must never do</h2>
 *
 * <p>Only one of the refusals here revokes a family. The others must not, and
 * the reason is symmetrical: a control that fires on benign events gets disabled
 * by whoever is on support that week.
 *
 * <ul>
 *   <li><b>Reuse revokes.</b> There is no ordinary reason to present a token
 *       that has already been rotated away.</li>
 *   <li><b>A device mismatch does not.</b> The fingerprint is a hash of the
 *       {@code User-Agent}, and Chrome rewrites that string on every auto-update
 *       — inside a seven-day window that is close to routine. Revoking on it
 *       would sign people out after a browser update and call it theft. Refusing
 *       the single token costs one re-login, which is the correct price for a
 *       binding {@link StoredRefreshToken#fingerprintOf} openly describes as
 *       weak. A real thief copies the header anyway, so the family revocation
 *       would buy almost nothing against the actual threat while firing
 *       constantly on the benign one.</li>
 *   <li><b>An expired or unknown token does not.</b> There is no family to
 *       revoke, and inventing one from an unauthenticated value would hand any
 *       caller a way to end sessions.</li>
 *   <li><b>A deactivated account does not.</b> Nothing was stolen; the session
 *       is simply over.</li>
 * </ul>
 *
 * <h2>Failure direction</h2>
 *
 * <p>Every write happens after the point of no return — the token is claimed
 * first, so if the consumed marker or the successor cannot be written the
 * exception propagates and the caller is left with no session at all. That is
 * the safe direction. The tempting alternative, catching the failure and
 * re-issuing the old token, converts a storage outage into an un-rotating
 * session.
 *
 * <p>Read failures are <b>not</b> converted to 401 either. A 401 is a statement
 * about the credential; saying it because Redis blinked signs out the entire
 * fleet and tells each of them their session was invalid, which is both false
 * and unrecoverable-looking. The exception propagates as a server error, the
 * browser keeps its cookie, and a retry after the blip works.
 *
 * <h2>Not in this task</h2>
 *
 * <p>The access token minted by a previous refresh stays valid for up to its
 * remaining fifteen minutes even after a family is revoked — there is no
 * blacklist yet, and no filter reading one. That is A-025 (blacklist) and A-032
 * (the chain that consults it). Until then "force re-login" means "issue nothing
 * further", not "cut the current call off mid-flight".
 */
@Service
class RefreshRotationService {

    private static final Logger log = LoggerFactory.getLogger(RefreshRotationService.class);

    private final RefreshTokenStore store;
    private final RefreshTokenIssuer issuer;
    private final RefreshTokenProperties properties;
    private final AuthenticationService authentication;
    private final AccessTokenIssuer accessTokens;

    RefreshRotationService(RefreshTokenStore store,
                           RefreshTokenIssuer issuer,
                           RefreshTokenProperties properties,
                           AuthenticationService authentication,
                           AccessTokenIssuer accessTokens) {
        this.store = store;
        this.issuer = issuer;
        this.properties = properties;
        this.authentication = authentication;
        this.accessTokens = accessTokens;
    }

    /**
     * Exchanges a refresh token for a new session, consuming the token in the
     * process.
     *
     * @param tokenValue the opaque value from the cookie, or null if absent
     * @param userAgent  this request's {@code User-Agent}, for the device check
     * @throws InvalidRefreshTokenException on every ordinary refusal
     * @throws RefreshTokenReuseException   when the token had already been spent
     */
    Rotation rotate(String tokenValue, String userAgent) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        // 1 ── Is this token live? If not, the interesting question is whether it
        //      USED to be. Absence alone proves nothing; the consumed marker is
        //      the only thing that separates theft from a stale or bogus value.
        StoredRefreshToken token = store.find(tokenValue).orElseThrow(() -> {
            Optional<String> spentFamily = store.findConsumedFamily(tokenValue);
            if (spentFamily.isPresent()) {
                return reuseDetected(spentFamily.get(), "a consumed token was presented again");
            }
            return new InvalidRefreshTokenException();
        });

        // 2 ── Was the family already condemned by a sibling's reuse? The token
        //      itself is live and looks perfect — this is the check that reaches
        //      the successors an attacker (or the victim) minted before detection.
        //      It runs BEFORE the claim, so a revoked family never rotates.
        if (store.isFamilyRevoked(token.familyId())) {
            store.discard(tokenValue);
            log.info("auth: refresh refused for user {} — family {} is revoked",
                    token.userId(), token.familyId());
            throw new InvalidRefreshTokenException();
        }

        // 3 ── The device binding A-023 recorded and left for this endpoint to
        //      enforce. Refuses this token; deliberately does not revoke the
        //      family — see the class javadoc.
        //
        //      It DOES discard the token, which is not merely tidy: without it an
        //      attacker holding the cookie can retry with one User-Agent string
        //      after another until one matches, and the fingerprint becomes a
        //      guessing game they eventually win. Discarding caps that at a single
        //      attempt. It costs the benign case nothing — a token whose recorded
        //      User-Agent no longer matches can never be presented successfully
        //      again, so the browser-update victim had already lost it.
        if (!token.matchesDevice(userAgent)) {
            store.discard(tokenValue);
            log.warn("auth: refresh refused for user {} — the token was presented by a different "
                    + "client than the one it was issued to. Benign if the browser updated; "
                    + "this token is discarded, the rest of family {} is untouched.",
                    token.userId(), token.familyId());
            throw new InvalidRefreshTokenException();
        }

        // 4 ── Expiry. Redis drops the key on its own, so reaching this with an
        //      expired record means the clock moved between the read and here.
        //      Checked anyway: `rotate` would otherwise mint a successor with a
        //      non-positive TTL and fail deeper in, as a 500 rather than a 401.
        if (!token.expiresAt().isAfter(Instant.now())) {
            store.discard(tokenValue);
            throw new InvalidRefreshTokenException();
        }

        // 5 ── The identity, re-read rather than taken from the stored record.
        //      A deactivation, a role change or a project reassignment lands
        //      here, at most one access-token lifetime stale. Runs before the
        //      claim so a refusal leaves the caller's token intact — being
        //      deactivated should not also destroy the evidence.
        AuthenticatedUser user = authentication.resolveActiveUser(token.userId());

        // 6 ── The point of no return, and the only place rotation may begin.
        //      DEL is atomic and tells exactly one caller it won the race, so two
        //      simultaneous refreshes of one token cannot both succeed and fork
        //      the family. The loser is presenting a token that is, by then,
        //      already consumed — which is reuse, and is treated as reuse.
        if (!store.claim(tokenValue)) {
            throw reuseDetected(token.familyId(),
                    "two requests presented the same token at once");
        }

        // 7 ── Remember it was spent, THEN mint the successor. This order is
        //      load-bearing: if the marker write fails, nothing has been issued
        //      and the session simply ends. Minting first and failing here would
        //      leave a live successor whose predecessor is silently undetectable
        //      — a stolen token that works forever with no alarm.
        store.markConsumed(tokenValue, token.familyId(), token.expiresAt());

        ResponseCookie cookie = issuer.rotate(token);
        return new Rotation(Session.issue(user, accessTokens.issue(user)), cookie);
    }

    /**
     * Revoking is the response, not the reporting of it. Done here so that no
     * caller of {@link RefreshTokenReuseException} can construct one without the
     * family having already been ended.
     */
    private RefreshTokenReuseException reuseDetected(String familyId, String how) {
        store.revokeFamily(familyId, properties.ttl());
        // ERROR, not WARN. Under the blueprint's own reading this is a stolen
        // credential being exercised against a live session, and it is the one
        // event in the auth package that someone should be paged about rather
        // than find later. The family id is the handle for the investigation;
        // the token is not logged, because writing a credential into a log file
        // is how the next incident starts.
        log.error("auth: SECURITY — refresh token reuse for user in family {} ({}). "
                        + "The family is revoked and every session descending from that login is over.",
                familyId, how);
        return new RefreshTokenReuseException();
    }

    /**
     * The two halves of a rotation: what goes in the body and what goes in the
     * header. Returned together because issuing one without the other is always
     * a bug — a session with no successor cookie cannot be renewed again, and a
     * cookie with no session is a credential the caller cannot use.
     */
    record Rotation(Session session, ResponseCookie cookie) {
    }
}
