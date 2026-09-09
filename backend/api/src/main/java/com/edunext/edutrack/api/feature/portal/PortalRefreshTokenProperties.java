package com.edunext.edutrack.api.feature.portal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * C-121 · binds {@code edutrack.auth.portal-refresh-token.*}.
 *
 * <p>The plan (§2.3) asks for "a separate refresh-token family" for the
 * CLIENT principal — this is that family's own configuration, deliberately
 * not {@code RefreshTokenProperties} widened: that record is package-private
 * in {@code feature.auth}, and a client's cookie must never share a name or a
 * path with the staff one, or a browser holding both would be sending a
 * portal credential to a staff route's {@code Cookie} header and back.
 *
 * @param ttl          seven days, matching the staff session's own §10.1 figure.
 * @param cookieName   {@code portal_refresh_token} — distinct from {@code
 *                     refresh_token} so the two can never collide in one
 *                     browser holding both a staff and a client session.
 * @param cookiePath   {@code /api/v1/portal/auth} — narrower than {@code
 *                     /api/v1/portal} for {@code RefreshTokenProperties}'
 *                     own reason: only the refresh and logout routes ever
 *                     read it.
 * @param secureCookie always true outside {@code local} — see {@link
 *                     PortalRefreshTokenConfig}.
 */
@ConfigurationProperties(prefix = "edutrack.auth.portal-refresh-token")
record PortalRefreshTokenProperties(
        Duration ttl,
        String cookieName,
        String cookiePath,
        Boolean secureCookie
) {
    PortalRefreshTokenProperties {
        if (ttl == null) ttl = Duration.ofDays(7);
        if (cookieName == null) cookieName = "portal_refresh_token";
        if (cookiePath == null) cookiePath = "/api/v1/portal/auth";
        if (secureCookie == null) secureCookie = true;
    }
}
