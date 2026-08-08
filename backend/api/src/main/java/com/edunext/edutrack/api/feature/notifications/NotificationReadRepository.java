package com.edunext.edutrack.api.feature.notifications;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * D-041 · the read side of the bell.
 *
 * <p>Named for reading because {@code NotificationWriter} in {@code domain}
 * already owns the write side and is shared with the worker. Two classes rather
 * than one is not an accident: scanners raise notifications without ever
 * listing them, and this screen lists them without ever raising one.
 *
 * <p>Every statement is scoped by {@code user_id}. A notification is addressed
 * to exactly one person, so there is no role reasoning to do and no reason to
 * wait for A-034 — but equally no query here may ever omit it.
 */
@Repository
class NotificationReadRepository {

    /**
     * {@code ix_notifications_unread (user_id, is_read, created_at)} is the
     * index the baseline built for this. Ordering is by {@code id} rather than
     * {@code created_at}: two notifications raised in the same fan-out share a
     * microsecond, and a cursor over a non-unique key either repeats a row or
     * skips one.
     */
    private static final String LIST = """
            SELECT n.id, n.event_code, n.title, n.body, n.is_read, n.created_at,
                   n.link_url, t.ticket_code
              FROM notifications n
              LEFT JOIN tickets t ON t.id = n.ticket_id
             WHERE n.user_id = :userId
               AND (:cursor IS NULL OR n.id < :cursor)
            """;

    private static final String UNREAD_COUNT = """
            SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = 0
            """;

    /**
     * {@code is_read = 0} in the WHERE, not just the SET: re-reading something
     * already read must not restamp {@code read_at}, or "when did you see this"
     * becomes "when did you last look at the list".
     */
    private static final String MARK_READ = """
            UPDATE notifications
               SET is_read = 1, read_at = NOW(6)
             WHERE id = :id AND user_id = :userId AND is_read = 0
            """;

    private static final String MARK_ALL_READ = """
            UPDATE notifications
               SET is_read = 1, read_at = NOW(6)
             WHERE user_id = :userId AND is_read = 0
            """;

    /** Scoped by user, so it answers "is this mine" and "does it exist" at once. */
    private static final String EXISTS = """
            SELECT COUNT(*) FROM notifications WHERE id = :id AND user_id = :userId
            """;

    private final JdbcClient jdbc;

    NotificationReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param eventCodes empty means no filter at all, which is not the same as
     *                   filtering on every code this build knows — see
     *                   {@link NotificationTab#eventCodes()}
     * @param limit      pass one more than the page size; the extra row is how
     *                   {@code hasMore} is answered without a second COUNT
     */
    List<NotificationRow> list(long userId, Long cursor, boolean unreadOnly,
                               List<String> eventCodes, int limit) {
        String sql = LIST
                + (unreadOnly ? " AND n.is_read = 0" : "")
                + (eventCodes.isEmpty() ? "" : " AND n.event_code IN (:eventCodes)")
                + " ORDER BY n.id DESC LIMIT :limit";

        var spec = jdbc.sql(sql)
                .param("userId", userId)
                .param("cursor", cursor)
                .param("limit", limit);
        if (!eventCodes.isEmpty()) {
            spec = spec.param("eventCodes", eventCodes);
        }
        return spec.query(NotificationRow.class).list();
    }

    int unreadCount(long userId) {
        Integer count = jdbc.sql(UNREAD_COUNT).param("userId", userId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** @return true if this call was the one that marked it */
    boolean markRead(long id, long userId) {
        return jdbc.sql(MARK_READ).param("id", id).param("userId", userId).update() == 1;
    }

    /** @return how many were still unread */
    int markAllRead(long userId) {
        return jdbc.sql(MARK_ALL_READ).param("userId", userId).update();
    }

    boolean exists(long id, long userId) {
        Integer count = jdbc.sql(EXISTS).param("id", id).param("userId", userId)
                .query(Integer.class).single();
        return count != null && count > 0;
    }

    record NotificationRow(
            long id,
            String eventCode,
            String title,
            String body,
            boolean isRead,
            Timestamp createdAt,
            String linkUrl,
            String ticketCode) {
    }
}
