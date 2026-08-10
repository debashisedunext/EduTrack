package com.edunext.edutrack.domain.notifications;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D-042 · which events reach a person, on which channel · D-036 · and which
 * they are not allowed to switch off.
 *
 * <p>Lives in {@code domain} rather than in the API feature because both sides
 * of the wall consult it: the worker enqueues mail, the API writes bell
 * entries, and a preference honoured in one place and not the other is worse
 * than none at all — it makes "I turned that off" true on Tuesdays.
 *
 * <p><strong>Absence means enabled.</strong> §7.7 makes everything not
 * mandatory "opt-out", so a user who has never opened the screen receives
 * everything, and a newly declared event is deliverable immediately rather than
 * after a backfill. See the migration for why the table holds only deviations.
 *
 * <p><strong>D-036 is enforced here, not at the screen.</strong> A UI that
 * greys out the mandatory switches is a courtesy; this is the guarantee. The
 * check is on the send path, so a preference row saying otherwise — written by
 * an older build, a direct database edit, or a bug — still cannot silence a
 * breach mail.
 */
@Component
@Lazy
public class NotificationPreferences {

    /**
     * One indexed lookup on {@code uq_notification_preferences}, rather than
     * loading everything the user overrode to answer one question. A fan-out
     * asks this once per recipient per channel, so the difference is between a
     * point read and dragging the whole matrix back for each one.
     */
    private static final String ONE_OVERRIDE = """
            SELECT enabled
              FROM notification_preferences
             WHERE user_id = :userId
               AND event_code = :eventCode
               AND channel = :channel
            """;

    private static final String ALL_FOR_USER = """
            SELECT event_code, channel, enabled
              FROM notification_preferences
             WHERE user_id = :userId
            """;

    private final JdbcClient jdbc;

    NotificationPreferences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * May this event be delivered to this user on this channel?
     *
     * @param eventCode the raw stored code. Unknown codes are deliverable: a
     *                  producer from a newer build must not be silenced by an
     *                  older reader's vocabulary, and the failure mode of
     *                  guessing wrong here is a missed alert.
     */
    public boolean allows(long userId, String eventCode, NotificationChannel channel) {
        NotificationEvent event = NotificationEvent.of(eventCode).orElse(null);

        if (channel == NotificationChannel.EMAIL && event != null && event.isMandatoryMail()) {
            // D-036. Answered before the lookup, so there is no row whose
            // contents could change it.
            return true;
        }

        return jdbc.sql(ONE_OVERRIDE)
                .param("userId", userId)
                .param("eventCode", eventCode)
                .param("channel", channel.name())
                .query(Boolean.class)
                .optional()
                // Never switched off, so it is on.
                .orElse(true);
    }

    /** Everything this user has deliberately changed — the screen's read. */
    public List<ChannelPreference> overridesFor(long userId) {
        return jdbc.sql(ALL_FOR_USER)
                .param("userId", userId)
                .query(ChannelPreference.class)
                .list();
    }

    /**
     * One deliberate change.
     *
     * <p>Named for what it is rather than {@code Override}, which would shadow
     * the annotation inside this file and read as a mistake everywhere else.
     *
     * @param channel kept as the stored string rather than the enum: a row
     *                naming a channel this build has dropped must not fail the
     *                whole read, and it simply matches nothing.
     */
    public record ChannelPreference(String eventCode, String channel, boolean enabled) {
    }
}
