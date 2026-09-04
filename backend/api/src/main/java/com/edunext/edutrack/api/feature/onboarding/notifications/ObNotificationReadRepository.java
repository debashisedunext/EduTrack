package com.edunext.edutrack.api.feature.onboarding.notifications;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * B-112 · the read side of OB-13.
 *
 * <p>Named for reading because nothing here writes an entry. The one writer is
 * {@code worker}'s {@code ObInAppNotificationWriter}, draining the queue, and
 * anything that wants a bell entry enqueues an {@code IN_APP} row rather than
 * inserting one — which is how a notification comes to commit with the business
 * change that caused it. The two classes rather than one is not an accident:
 * the dispatcher writes without ever listing, and this screen lists without
 * ever writing.
 *
 * <p><strong>Every statement is scoped by {@code recipient_user_id}, and none
 * may ever omit it.</strong> An entry is addressed to exactly one person, so
 * there is no role reasoning to do and no dependence on A-034's scope guard —
 * but the flip side is that the scoping is entirely these five statements, and
 * a forgotten predicate is the whole distance between OB-13 and somebody
 * else's client list.
 *
 * <p>Ordering and paging are by {@code id}, never {@code created_at}: a fan-out
 * that notifies four owners of one gate opening writes four rows in the same
 * microsecond, and a cursor over a non-unique key either repeats a row or skips
 * one.
 */
@Repository
class ObNotificationReadRepository {

    /**
     * Served by {@code ix_ob_notifications_feed (recipient_user_id, category,
     * id)} when a tab is chosen and {@code ix_ob_notifications_unread} when the
     * unread filter is. Nothing is joined: the entry already carries its title,
     * body and link, because the wording was resolved at delivery from a
     * payload that has since been superseded — re-deriving it at read time
     * would make an old notification describe today's state.
     */
    private static final String LIST = """
            SELECT n.id, n.event_key, n.category, n.title, n.body, n.link_url,
                   n.ob_client_id, n.journey_id, n.step_id, n.is_read, n.created_at
              FROM ob_notifications n
             WHERE n.recipient_user_id = :userId
               AND (:cursor IS NULL OR n.id < :cursor)
            """;

    private static final String UNREAD_COUNT = """
            SELECT COUNT(*) FROM ob_notifications
             WHERE recipient_user_id = :userId AND is_read = 0
            """;

    /**
     * {@code is_read = 0} in the WHERE, not only the SET: re-reading something
     * already read must not restamp {@code read_at}, or "when did you see this"
     * becomes "when did you last open the list".
     */
    private static final String MARK_READ = """
            UPDATE ob_notifications
               SET is_read = 1, read_at = NOW(6)
             WHERE id = :id AND recipient_user_id = :userId AND is_read = 0
            """;

    private static final String MARK_ALL_READ = """
            UPDATE ob_notifications
               SET is_read = 1, read_at = NOW(6)
             WHERE recipient_user_id = :userId AND is_read = 0
            """;

    /** Scoped by user, so it answers "is this mine" and "does it exist" at once. */
    private static final String EXISTS = """
            SELECT COUNT(*) FROM ob_notifications
             WHERE id = :id AND recipient_user_id = :userId
            """;

    private final JdbcClient jdbc;

    ObNotificationReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param categories empty means no filter at all, which is not the same as
     *                   filtering on every category this build knows — see
     *                   {@link ObNotificationTab#categoryCodes()}
     * @param limit      pass one more than the page size; the extra row is how
     *                   {@code hasMore} is answered without a second COUNT over
     *                   the same predicate
     */
    List<ObNotificationRow> list(long userId, Long cursor, boolean unreadOnly,
                                 List<String> categories, int limit) {
        String sql = LIST
                + (unreadOnly ? " AND n.is_read = 0" : "")
                + (categories.isEmpty() ? "" : " AND n.category IN (:categories)")
                + " ORDER BY n.id DESC LIMIT :limit";

        var spec = jdbc.sql(sql)
                .param("userId", userId)
                .param("cursor", cursor)
                .param("limit", limit);
        if (!categories.isEmpty()) {
            spec = spec.param("categories", categories);
        }
        return spec.query(ObNotificationRow.class).list();
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
        Integer count = jdbc.sql(EXISTS)
                .param("id", id)
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * One row as stored. Deliberately the column names rather than a mapped
     * entity: {@code JdbcClient}'s record mapper binds by name, and there is
     * nothing here to persist through.
     */
    record ObNotificationRow(
            long id,
            String eventKey,
            String category,
            String title,
            String body,
            String linkUrl,
            Long obClientId,
            Long journeyId,
            Long stepId,
            boolean isRead,
            Timestamp createdAt) {
    }
}
