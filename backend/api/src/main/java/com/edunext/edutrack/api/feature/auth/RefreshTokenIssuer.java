package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A-023 · mints the opaque refresh token, records it, and returns the cookie
 * that carries it. Blueprint §10.1.
 *
 * <p>Mirrors {@link AccessTokenIssuer}: {@link AuthenticationService} answers
 * "is this person who they say they are", and this turns that answer into the
 * long-lived half of the session. {@link AuthController} sequences the two.
 *
 * <p><b>Opaque, meaning it means nothing.</b> The value is 256 bits from
 * {@link SecureRandom} and carries no claims, no user id and no expiry — it is
 * a lookup key and nothing else. That is the whole difference from the access
 * token: a JWT is self-describing and therefore valid until it expires no
 * matter what happens server-side, which is unacceptable for something that
 * lives seven days. An opaque token is only as valid as the row behind it, so
 * A-024's family revocation and A-025's logout can kill it instantly.
 *
 * <p><b>The value never enters the response body.</b> It leaves this class only
 * inside a {@code Set-Cookie} header, {@code HttpOnly}, so script cannot read
 * it. A refresh token in the JSON body would be handed to whatever the frontend
 * stores it in — {@code localStorage}, most likely — where a single XSS turns
 * a fifteen-minute exposure into a seven-day one.
 *
 * <p><b>A-024 adds {@link #rotate}</b> — the same minting, but into an existing
 * family and under that family's existing deadline. {@link #issue} is now
 * precisely "a new login starts a new session"; rotation is "the same session
 * continues".
 *
 * <p><b>Not in this task:</b> logout and the access-token blacklist (A-025).
 */
@Component
class RefreshTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenIssuer.class);

    /** 256 bits. Enough that guessing is not a threat model worth modelling. */
    private static final int TOKEN_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenStore store;
    private final RefreshTokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenIssuer(RefreshTokenStore store, RefreshTokenProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Issues a refresh token for a user whose credentials have already been
     * verified, and returns the cookie to set.
     *
     * <p><b>Empty means Redis was unreachable, and the login still succeeds.</b>
     * The alternative — failing the login — takes the entire product down over
     * a degradation confined to session length, which is the trade
     * {@code RealtimeRelay} already rejected for the same dependency. Degrading
     * here fails towards <i>less</i> access, not more: the user gets a
     * fifteen-minute access token and then has to log in again. Nothing is
     * weakened, only shortened.
     *
     * <p>Logged at ERROR with what it costs, because the failure is otherwise
     * invisible from the outside — a fleet in this state presents to users as
     * "the app keeps logging me out", which is a support ticket rather than an
     * alert. Blueprint §17 asks for degradations to be provable rather than
     * deniable.
     *
     * @param userAgent the request's {@code User-Agent}, or null if absent
     */
    Optional<ResponseCookie> issue(AuthenticatedUser user, String userAgent) {
        Instant issuedAt = Instant.now();
        String value = mint();

        StoredRefreshToken token = new StoredRefreshToken(
                UUID.randomUUID().toString(),
                user.id(),
                // A fresh family per login. Rotation (A-024) keeps the family;
                // only logging in again starts a new one, which is what makes
                // "revoke the family" mean "end that one session" rather than
                // "log this person out everywhere".
                UUID.randomUUID().toString(),
                StoredRefreshToken.fingerprintOf(userAgent),
                issuedAt,
                issuedAt.plus(properties.ttl()));

        try {
            store.save(value, token);
        } catch (RuntimeException e) {
            log.error("auth: refresh token for user {} could not be stored — this session ends in {} "
                            + "minutes with no way to renew it, and every login will do the same until "
                            + "the token store is reachable.",
                    user.id(), properties.ttl().toMinutes(), e);
            return Optional.empty();
        }

        return Optional.of(cookieFor(value, properties.ttl()));
    }

    /**
     * A-024 · mints the successor to a token that has just been consumed.
     *
     * <p><b>The family is inherited, never renewed.</b> A successor in a new
     * family would be a new session, and "revoke the family" would then reach
     * only as far back as the last refresh — an attacker who rotates once has
     * escaped it. The family is the unit revocation operates on, so it has to
     * span the whole chain from login.
     *
     * <p><b>The deadline is inherited too: expiry does not slide.</b> Handing
     * the successor a fresh seven days is the more common design and is wrong
     * here. It makes the session unbounded — a token used once a week never
     * expires, and neither does a stolen chain being kept warm — so the only
     * thing that ever ends a session is an explicit logout, which an attacker
     * has no reason to perform. Inheriting {@code expiresAt} gives the family a
     * hard deadline set at login, which is what §10.1's "7 days" most naturally
     * means and what A-025's "absolute session 12 h" will tighten. The cost is
     * real and accepted: an actively working user is signed out at the deadline
     * rather than carried past it.
     *
     * <p><b>The device fingerprint is inherited rather than re-read</b> from the
     * current request. Re-stamping it would let a token that had drifted to a
     * different browser quietly re-bind itself to that browser — the binding
     * would then only ever match, which is the same as not having one.
     *
     * <p>Unlike {@link #issue} this does <b>not</b> degrade when the store is
     * unreachable. By the time it is called the predecessor has already been
     * destroyed, so there is no session left to preserve; the exception
     * propagates, the caller gets a server error, and the user re-logs in. That
     * fails towards less access, which is the only acceptable direction.
     *
     * @throws IllegalArgumentException if the family deadline has already passed
     */
    ResponseCookie rotate(StoredRefreshToken consumed) {
        String value = mint();
        Duration remaining = Duration.between(Instant.now(), consumed.expiresAt());

        store.save(value, new StoredRefreshToken(
                // A fresh jti. A-025's logout and every audit line identify one
                // link in the chain, not the chain — reusing the predecessor's
                // would make two distinct credentials indistinguishable in a log.
                UUID.randomUUID().toString(),
                consumed.userId(),
                consumed.familyId(),
                consumed.deviceFingerprint(),
                Instant.now(),
                consumed.expiresAt()));

        return cookieFor(value, remaining);
    }

    /**
     * A-024 · the cookie that removes the cookie, sent with every refusal.
     *
     * <p>Without it a browser holding a dead token replays it on every attempt:
     * against a revoked family that is a permanent 401 loop the user cannot
     * escape without clearing cookies by hand, and it keeps a useless credential
     * on the wire. Same name, same {@code Path} — a clearing cookie that differs
     * in either is a <i>second</i> cookie rather than a replacement.
     */
    ResponseCookie clearing() {
        return cookieFor("", Duration.ZERO);
    }

    /**
     * {@code HttpOnly; Secure; SameSite=Strict}, per §10.1 and the contract.
     *
     * <p><b>{@code SameSite=Strict} has a cost worth naming.</b> The cookie is
     * withheld even on top-level navigation, so a user arriving from a link in
     * a notification mail lands on a page that cannot refresh and looks logged
     * out until they navigate once within the app. {@code Lax} would avoid that
     * and is what most sites pick. Strict is still right here: it is what makes
     * CSRF against {@code /auth/refresh} structurally impossible rather than
     * something a token has to defend against, and this is an internal tool
     * where an occasional extra click is cheaper than a second defence to
     * maintain.
     */
    private ResponseCookie cookieFor(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(properties.cookiePath())
                .maxAge(maxAge)
                .build();
    }

    /** Base64url so the value is cookie-safe without percent-encoding. */
    private String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
