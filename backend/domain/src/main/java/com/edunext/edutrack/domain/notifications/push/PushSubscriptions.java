package com.edunext.edutrack.domain.notifications.push;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D-045 · the send side of {@code push_subscriptions}.
 *
 * <p><strong>Why this is not the same class as the opt-in repository.</strong>
 * {@code api.feature.notifications.PushSubscriptionRepository} owns subscribing
 * and unsubscribing: both are things a signed-in user does to their own browser,
 * and both are scoped to that user so nobody can unsubscribe somebody else by
 * guessing an endpoint. This class is the opposite — it runs with no request and
 * no caller, from {@code worker} as well as {@code api}, and its two operations
 * are deliberately <em>not</em> user-scoped, because the push service's verdict
 * on an endpoint has nothing to do with who is signed in.
 *
 * <p>It lives in {@code domain} for the reason {@link
 * com.edunext.edutrack.domain.notifications.NotificationWriter} does: scanners
 * raise notifications too, and {@code worker} cannot see {@code api}.
 *
 * <p>{@code @Lazy} like every other repository holder here — api's
 * {@code ApplicationSmokeTest} builds the context with no datasource on purpose.
 */
@Component
@Lazy
public class PushSubscriptions {

    /**
     * Every browser this user has granted permission on.
     *
     * <p>All of them, deliberately. Somebody with a desktop and a phone
     * subscribed both because they want the interrupt wherever they are, and
     * picking one would make the feature silently unreliable in a way nobody
     * could report — "I only get them sometimes" is not a bug anyone can chase.
     */
    private static final String FOR_USER = """
            SELECT endpoint, p256dh, auth_secret AS authSecret
              FROM push_subscriptions
             WHERE user_id = :userId
            """;

    /**
     * Delete by endpoint alone, with no user in the clause.
     *
     * <p>That looks unscoped and is correct. The caller is not a user — it is a
     * push service answering 404 or 410, which means <em>this browser will never
     * accept another message</em>. The endpoint is unique, so the row it names is
     * unambiguous; and the row may well belong to somebody else by now, since a
     * second person signing in on a shared machine reassigns it. Scoping this to
     * a user id would leave exactly those rows undeleteable, and the table would
     * fill with browsers that no longer exist — which is the failure this half of
     * D-045 exists to prevent.
     */
    private static final String DELETE_BY_ENDPOINT = """
            DELETE FROM push_subscriptions WHERE endpoint = :endpoint
            """;

    /** Proof of life, so a browser that stopped coming back is visible in the table. */
    private static final String TOUCH = """
            UPDATE push_subscriptions
               SET last_seen_at = CURRENT_TIMESTAMP(6)
             WHERE endpoint = :endpoint
            """;

    private final JdbcClient jdbc;

    PushSubscriptions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Subscription> forUser(long userId) {
        return jdbc.sql(FOR_USER).param("userId", userId).query(Subscription.class).list();
    }

    /** @return true if a row was actually removed */
    public boolean deleteByEndpoint(String endpoint) {
        return jdbc.sql(DELETE_BY_ENDPOINT).param("endpoint", endpoint).update() == 1;
    }

    public void touch(String endpoint) {
        jdbc.sql(TOUCH).param("endpoint", endpoint).update();
    }

    /** One browser, with the key material RFC 8291 needs. */
    public record Subscription(String endpoint, String p256dh, String authSecret) {
    }
}
