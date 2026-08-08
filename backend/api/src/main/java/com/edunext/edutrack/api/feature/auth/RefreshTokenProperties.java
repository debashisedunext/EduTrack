package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A-023 · binds {@code edutrack.auth.refresh-token.*}. Defaults live in
 * application.yml; the compact constructor repeats them so a
 * {@code new RefreshTokenProperties(null, null, null, null)} in a test gets the
 * production shape rather than four nulls.
 *
 * @param ttl          seven days, per §10.1. Drives both the Redis TTL and the
 *                     cookie's {@code Max-Age}, so the two cannot drift.
 * @param cookieName   {@code refresh_token}, matching
 *                     {@code contracts/openapi.yaml}.
 * @param cookiePath   {@code /api/v1/auth} — narrower than {@code /} on
 *                     purpose. Only {@code /auth/refresh} (A-024) and
 *                     {@code /auth/logout} (A-025) ever read this cookie;
 *                     scoping it to {@code /} would attach a seven-day
 *                     credential to every ticket list and every attachment
 *                     download, multiplying the number of proxy logs, request
 *                     mirrors and browser dev-tools sessions it passes through
 *                     for no benefit at all.
 * @param secureCookie always true outside {@code local} —
 *                     {@link RefreshTokenConfig} refuses to start otherwise.
 *                     It exists because {@code Secure} cookies are rejected by
 *                     Safari over {@code http://localhost}, and by every browser
 *                     over a plain-http LAN address of the kind used to test on
 *                     a real phone. Without the knob those developers cannot
 *                     hold a session at all; without the guard, someone
 *                     eventually ships {@code false}.
 */
@ConfigurationProperties(prefix = "edutrack.auth.refresh-token")
record RefreshTokenProperties(
        Duration ttl,
        String cookieName,
        String cookiePath,
        Boolean secureCookie
) {
    RefreshTokenProperties {
        if (ttl == null) ttl = Duration.ofDays(7);
        if (cookieName == null) cookieName = "refresh_token";
        if (cookiePath == null) cookiePath = "/api/v1/auth";
        if (secureCookie == null) secureCookie = true;
    }
}
