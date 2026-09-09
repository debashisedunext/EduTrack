package com.edunext.edutrack.api.feature.portal;

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
 * C-121 · mints the opaque portal refresh token and the cookie that carries
 * it — {@code RefreshTokenIssuer}'s shape, and simpler where noted on {@link
 * PortalRefreshTokenStore}'s own account.
 *
 * <p>Rotation here is plain rotate-and-replace: {@link #rotate} mints a fresh
 * token inheriting nothing but the account id, on a fresh TTL. There is no
 * family to inherit and no absolute session cap to preserve, because neither
 * exists yet for this principal — flagged, not silently narrower.
 */
@Component
class PortalRefreshTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(PortalRefreshTokenIssuer.class);

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final PortalRefreshTokenStore store;
    private final PortalRefreshTokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    PortalRefreshTokenIssuer(PortalRefreshTokenStore store, PortalRefreshTokenProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * A new session for an account that has just authenticated (login or a
     * successful credential-link redemption).
     *
     * <p>Empty only when Redis is unreachable — {@code RefreshTokenIssuer
     * #issue}'s own degrade-not-fail direction: the login still succeeds with
     * a bearer-only, non-renewable session rather than being refused outright.
     */
    Optional<ResponseCookie> issue(long clientAccountId) {
        Instant issuedAt = Instant.now();
        String value = mint();

        StoredPortalRefreshToken token = new StoredPortalRefreshToken(
                UUID.randomUUID().toString(), clientAccountId, issuedAt, issuedAt.plus(properties.ttl()));

        try {
            store.save(value, token);
        } catch (RuntimeException e) {
            log.error("portal auth: refresh token for client account {} could not be stored — this "
                            + "session cannot be renewed until the token store is reachable.",
                    clientAccountId, e);
            return Optional.empty();
        }
        return Optional.of(cookieFor(value, properties.ttl()));
    }

    /** Mints a successor on a fresh TTL — see the class note on why nothing is inherited. */
    ResponseCookie rotate(StoredPortalRefreshToken consumed) {
        String value = mint();
        Instant now = Instant.now();
        store.save(value, new StoredPortalRefreshToken(
                UUID.randomUUID().toString(), consumed.clientAccountId(), now, now.plus(properties.ttl())));
        return cookieFor(value, properties.ttl());
    }

    ResponseCookie clearing() {
        return cookieFor("", Duration.ZERO);
    }

    private ResponseCookie cookieFor(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(properties.cookiePath())
                .maxAge(maxAge)
                .build();
    }

    private String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
