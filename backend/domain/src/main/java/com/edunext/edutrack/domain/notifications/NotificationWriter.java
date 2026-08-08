package com.edunext.edutrack.domain.notifications;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Raises in-app notifications.
 *
 * <p>In {@code domain} for the same reason as
 * {@link com.edunext.edutrack.domain.outbox.OutboxEnqueuer}: {@code api} writes
 * them from request handling and {@code worker} writes them from scanners, and
 * neither module depends on the other. D-040 builds the full event catalogue on
 * top of this; D-033 is its first caller.
 *
 * <p>{@code @Lazy} so contexts that run without infrastructure — api's
 * {@code ApplicationSmokeTest} excludes the datasource deliberately — still
 * start.
 */
@Component
@Lazy
public class NotificationWriter {

    private static final String INSERT = """
            INSERT INTO notifications (user_id, ticket_id, event_code, title, body, link_url)
            VALUES (:userId, :ticketId, :eventCode, :title, :body, :linkUrl)
            """;

    /**
     * Active users holding a given role. Used to reach "an Admin" when a
     * notification has no single obvious owner.
     */
    private static final String ACTIVE_USERS_IN_ROLE = """
            SELECT u.id
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE r.code = :roleCode
               AND u.is_active = 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the id of the row just written
     *
     * <p>Returned rather than discarded because a realtime push has to name the
     * notification it is announcing: D-043's toast offers Open, Snooze and
     * Dismiss, and every one of those is an action on a specific row. A push
     * carrying only the text would leave the client with nothing to mark read.
     */
    public long write(NewNotification notification) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("userId", notification.userId())
                .addValue("ticketId", notification.ticketId())
                .addValue("eventCode", notification.event().name())
                .addValue("title", notification.title())
                .addValue("body", notification.body())
                .addValue("linkUrl", notification.linkUrl()), key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException(
                    "notifications: no generated key for " + notification.event());
        }
        return id.longValue();
    }

    /** @return ids of every active user with {@code roleCode}, possibly empty */
    public List<Long> activeUsersInRole(String roleCode) {
        return jdbc.queryForList(ACTIVE_USERS_IN_ROLE,
                new MapSqlParameterSource("roleCode", roleCode), Long.class);
    }
}
