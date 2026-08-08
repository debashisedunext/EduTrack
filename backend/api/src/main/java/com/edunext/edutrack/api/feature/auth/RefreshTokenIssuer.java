package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
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
 * <p><b>Not in this task:</b> {@code POST /auth/refresh} and rotation with
 * family revocation (A-024), logout and the access-token blacklist (A-025), and
 * enforcement of the device binding — this records the fingerprint, and the
 * only place it can be <i>checked</i> is the refresh endpoint that does not
 * exist yet. Same shape as A-022, which minted a token nothing verified.
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

        return Optional.of(cookieFor(value));
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
    private ResponseCookie cookieFor(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(properties.cookiePath())
                .maxAge(properties.ttl())
                .build();
    }

    /** Base64url so the value is cookie-safe without percent-encoding. */
    private String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
