package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A-027 · binds {@code edutrack.auth.password-reset.*}. Defaults repeated in the
 * compact constructor so a {@code new PasswordResetProperties(null, null)} in a
 * test gets the production shape, matching {@link RefreshTokenProperties}.
 *
 * @param ttl     thirty minutes, per blueprint §10.3 and screen S-02. Short on
 *                purpose: this token is a bearer credential that grants an
 *                account without a password, and it sits in a mailbox — the one
 *                place a credential is most likely to be read by someone other
 *                than its owner, whether through a forwarding rule, a shared
 *                family device or an already-compromised inbox. Thirty minutes
 *                is long enough to walk away from the desk and come back, and
 *                short enough that a link found later is worthless.
 * @param baseUrl where the emailed link points, before {@code ?token=…}. The SPA
 *                route that renders S-02.
 *                <p>Configurable because it is genuinely environment-specific —
 *                {@code localhost:5173} in dev, the real host in production —
 *                and getting it wrong is not a security failure but a broken
 *                link. It is <b>not</b> derived from the incoming request's
 *                {@code Host} header, which would be the convenient choice and
 *                is a well-known hole: an attacker who can set that header makes
 *                the mail we send point at their server, and the victim clicks a
 *                link we authored and hands them the token.
 */
@ConfigurationProperties(prefix = "edutrack.auth.password-reset")
record PasswordResetProperties(
        Duration ttl,
        String baseUrl
) {
    PasswordResetProperties {
        if (ttl == null) ttl = Duration.ofMinutes(30);
        if (baseUrl == null) baseUrl = "http://localhost:8080/reset-password";
    }
}
