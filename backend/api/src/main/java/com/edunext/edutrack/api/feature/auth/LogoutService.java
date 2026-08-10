package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * A-025 · ends one session. Blueprint §10.1: "delete refresh jti, add access
 * jti to a short-lived Redis blacklist".
 *
 * <p><b>Both halves are required, and each alone is a bug.</b> Deleting only the
 * refresh token leaves the access token working for up to fifteen more minutes —
 * on a shared machine, fifteen minutes of someone else's session after they
 * pressed Sign out. Blacklisting only the access token leaves the refresh cookie
 * able to mint a new one immediately, so the logout undoes itself on the next
 * tick. Sign out means "this session is over now and cannot be resumed", which
 * takes both.
 *
 * <h2>Why this endpoint verifies its own caller</h2>
 *
 * <p>{@code /auth/logout} is the first route in the system the contract does not
 * mark {@code security: []}, and A-032's filter chain — the thing that would
 * normally have authenticated the caller long before a service saw them — does
 * not exist yet. So the token is verified here, through
 * {@link AccessTokenVerifier}: signature, algorithm, issuer and expiry are all
 * the shared {@code JwtDecoder}'s business (see {@code JwtDecoderConfig}), and
 * this class only reads claims out of a token already proven good.
 *
 * <p><b>It has to be a real verification, not a decode.</b> Accepting an
 * unverified token here would let anyone forge a {@code jti} and blacklist an
 * arbitrary stranger's access token — a logout endpoint that logs other people
 * out. That is why nothing in this class parses a JWT itself.
 *
 * <p>A-026 moved that verification out to {@link AccessTokenVerifier} when
 * {@code PATCH /me/password} needed the identical check; the behaviour is
 * unchanged, and there is now one implementation for A-032's chain to replace
 * instead of two.
 *
 * <h2>What logout deliberately does not do</h2>
 *
 * <ul>
 *   <li><b>It does not revoke the family.</b> Signing out of a laptop must not
 *       sign the same user out of their phone. Family revocation is A-024's
 *       response to <i>theft</i>; borrowing it here would make an ordinary
 *       logout indistinguishable from a security incident, in the logs and in
 *       the user's experience.</li>
 *   <li><b>It does not mark the refresh token consumed.</b> {@code discard}, not
 *       {@code claim}+{@code markConsumed} — a consumed marker is what turns a
 *       later presentation into detected reuse, so recording one here would mean
 *       a stale tab retrying after logout revokes the user's whole family for
 *       something that was never theft.</li>
 *   <li><b>It does not require the refresh cookie to be present or valid.</b>
 *       See {@link #logout}.</li>
 * </ul>
 *
 * <p><b>Not in this task:</b> enforcement. Nothing reads
 * {@link AccessTokenBlacklist} until A-032's chain consults it on every request,
 * so today a blacklisted token is poisoned and no route notices. Same shape as
 * A-023's device fingerprint, which A-024 then enforced.
 */
@Service
class LogoutService {

    private static final Logger log = LoggerFactory.getLogger(LogoutService.class);

    private final AccessTokenVerifier accessTokens;
    private final AccessTokenBlacklist blacklist;
    private final RefreshTokenStore refreshTokens;

    LogoutService(AccessTokenVerifier accessTokens,
                  AccessTokenBlacklist blacklist,
                  RefreshTokenStore refreshTokens) {
        this.accessTokens = accessTokens;
        this.blacklist = blacklist;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Ends the session the presented access token belongs to.
     *
     * <p><b>The access token is mandatory; the refresh cookie is not.</b> The
     * asymmetry is deliberate. The access token is how we know <i>whose</i>
     * session to end, so without a valid one there is nothing to act on and no
     * safe guess to make. The refresh cookie only names a token to delete — and
     * the people most in need of a working Sign out are exactly those whose
     * session is already half-broken: the cookie expired, or was already
     * rotated, or {@code SameSite=Strict} withheld it on this navigation.
     * Refusing them would leave the access token live and the user staring at a
     * button that does nothing.
     *
     * <p><b>Idempotent by construction.</b> Blacklisting an already-blacklisted
     * {@code jti} rewrites the same key, and discarding an absent token is a
     * no-op, so a double-click or a retry is indistinguishable from one call.
     * Logout is the wrong place to be strict — every failure mode here leaves
     * someone logged in who asked not to be.
     *
     * @param authorizationHeader the raw {@code Authorization} header, or null
     * @param refreshTokenValue   the refresh cookie's value, or null if absent
     * @throws InvalidAccessTokenException if the header is missing, malformed,
     *                                     unsigned by us, or expired
     */
    void logout(String authorizationHeader, String refreshTokenValue) {
        Jwt accessToken = accessTokens.verify(authorizationHeader);

        // Order matters, slightly: revoke the credential that is currently
        // usable before removing the one that could mint another. Reversed, a
        // request arriving in the gap could rotate the refresh token and walk
        // away with an access token minted after the logout began.
        blacklistAccessToken(accessToken);

        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            // discard, NOT claim+markConsumed — see the class javadoc.
            refreshTokens.discard(refreshTokenValue);
        }

        log.info("auth: user {} signed out; access token {} revoked until its natural expiry",
                accessToken.getSubject(), accessToken.getId());
    }

    /**
     * <b>Falls back to a horizon, never to "skip".</b> {@code exp} is required by
     * the decoder's validators so a token reaching here always has one; the
     * branch exists because {@link Jwt#getExpiresAt()} is nullable in the type
     * system, and the only safe reading of "I cannot tell when this expires" is
     * to blacklist it for longer than it could possibly live. Skipping instead
     * would silently let one class of token survive logout.
     */
    private void blacklistAccessToken(Jwt accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        if (expiresAt == null) {
            log.warn("auth: access token {} carries no exp; blacklisting for a conservative window",
                    accessToken.getId());
            expiresAt = Instant.now().plus(java.time.Duration.ofHours(1));
        }
        blacklist.revoke(accessToken.getId(), expiresAt);
    }
}
