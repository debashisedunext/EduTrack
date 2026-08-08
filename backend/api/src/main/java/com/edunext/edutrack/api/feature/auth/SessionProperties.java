package com.edunext.edutrack.api.feature.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * A-025 · binds {@code edutrack.auth.session.*} — the two bounds §10.1 puts on
 * a session's life, independent of how long the refresh token itself is stored.
 *
 * <p><b>These are separate from {@link RefreshTokenProperties#ttl()} on
 * purpose, and the difference is the interesting part.</b> A-023 gives the
 * refresh token a seven-day TTL; §10.1 also says a session times out after 30
 * minutes idle and 12 hours absolute. Read together those look contradictory —
 * they are not, they govern different things. The seven days is how long the
 * server <i>remembers</i> a token; the two below are how long it will
 * <i>honour</i> one. Remembering for longer than we honour is deliberate: a
 * stolen token replayed on day three is refused either way, but only a
 * remembered token is refused as <b>theft</b> rather than as an unknown value,
 * and that is the difference between A-024 revoking the family with an alert
 * and shrugging at what looks like a typo.
 *
 * <p>The visible cost is that a browser keeps a cookie the server will not
 * accept. It is harmless — the cookie is {@code HttpOnly} and scoped to
 * {@code /api/v1/auth}, so it is neither readable nor sent anywhere else — and
 * it buys a theft-detection window six days longer than the session.
 *
 * @param idleTimeout     30 minutes, per §10.1. Measured from the last
 *                        successful refresh, so an open tab that renews every
 *                        fifteen minutes never trips it and a walked-away-from
 *                        desk does.
 * @param absoluteTimeout 12 hours, per §10.1. Measured from <b>login</b> and
 *                        never extended by activity — this is the bound that
 *                        makes a session finite. Without it, rotation is an
 *                        unbounded chain: a token used inside every idle window
 *                        lives forever, and so does a stolen chain being kept
 *                        warm, leaving explicit logout as the only thing that
 *                        ever ends a session — which an attacker has no reason
 *                        to perform.
 */
@ConfigurationProperties(prefix = "edutrack.auth.session")
record SessionProperties(Duration idleTimeout, Duration absoluteTimeout) {

    SessionProperties {
        if (idleTimeout == null) idleTimeout = Duration.ofMinutes(30);
        if (absoluteTimeout == null) absoluteTimeout = Duration.ofHours(12);
    }
}
