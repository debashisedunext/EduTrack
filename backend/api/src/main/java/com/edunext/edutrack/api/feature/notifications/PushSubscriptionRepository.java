package com.edunext.edutrack.api.feature.notifications;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * D-045 · which browsers have agreed to be interrupted.
 */
@Repository
class PushSubscriptionRepository {

    /**
     * One statement, and it <strong>reassigns the user</strong> on conflict.
     *
     * <p>The endpoint identifies the browser, not the account. When a second
     * person signs in on a shared machine and grants permission, the browser
     * hands back the endpoint it already had — so anything other than moving
     * the row leaves the previous user subscribed on a screen that is no longer
     * theirs. Push is the one channel where that is not a stale-data annoyance
     * but a disclosure: the ticket title is in the notification body.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} rather than read-then-write for the
     * reason D-042's matrix gives — two tabs subscribing at once would both see
     * nothing, both insert, and turn a save into a 500 on the unique key.
     */
    private static final String UPSERT = """
            INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth_secret, user_agent)
            VALUES (:userId, :endpoint, :p256dh, :authSecret, :userAgent)
            ON DUPLICATE KEY UPDATE
                user_id      = VALUES(user_id),
                p256dh       = VALUES(p256dh),
                auth_secret  = VALUES(auth_secret),
                user_agent   = VALUES(user_agent),
                last_seen_at = CURRENT_TIMESTAMP(6)
            """;

    /**
     * Scoped to the caller, so one user cannot unsubscribe another's browser by
     * guessing an endpoint. A row that is not theirs is simply not deleted, and
     * the endpoint answers 204 either way — see the controller for why that is
     * not an information leak worth worrying about.
     */
    private static final String DELETE = """
            DELETE FROM push_subscriptions WHERE endpoint = :endpoint AND user_id = :userId
            """;

    private final JdbcClient jdbc;

    PushSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void save(long userId, String endpoint, String p256dh, String authSecret, String userAgent) {
        jdbc.sql(UPSERT)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("p256dh", p256dh)
                .param("authSecret", authSecret)
                .param("userAgent", userAgent)
                .update();
    }

    int delete(long userId, String endpoint) {
        return jdbc.sql(DELETE)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .update();
    }
}
