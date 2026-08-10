package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * D-042 · the write side of the matrix.
 *
 * <p>Reading lives in {@code domain}'s {@code NotificationPreferences} because
 * the worker consults it too; only the screen writes, so only the API does.
 */
@Repository
class PreferenceRepository {

    /**
     * One statement for both cases.
     *
     * <p>Read-then-insert-or-update would race a second tab saving the same
     * row: both see nothing, both insert, and the unique key turns a save into
     * a 500. {@code ON DUPLICATE KEY UPDATE} makes the database settle it.
     *
     * <p>{@code updated_at} is restamped deliberately — unlike {@code read_at}
     * in D-041, this is "when did you last express this preference", and
     * re-affirming it is a real event.
     */
    private static final String UPSERT = """
            INSERT INTO notification_preferences (user_id, event_code, channel, enabled)
            VALUES (:userId, :eventCode, :channel, :enabled)
            ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
            """;

    private final JdbcClient jdbc;

    PreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void upsert(long userId, String eventCode, NotificationChannel channel, boolean enabled) {
        jdbc.sql(UPSERT)
                .param("userId", userId)
                .param("eventCode", eventCode)
                .param("channel", channel.name())
                .param("enabled", enabled)
                .update();
    }
}
