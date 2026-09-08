package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * B-125 · the task's conversation and its chain — the comment thread read
 * and written here, the hash chain read only.
 *
 * <h2>Why the comments are plain SQL and the history is not</h2>
 *
 * <p>Both tables are append-only, and only one is hash-chained. That
 * difference decides how each is reached, and C-112 set the pattern one
 * feature over: {@code ob_step_communications} has no domain repository at
 * all — a plain {@code JdbcClient} class in the feature package, with the
 * {@code BEFORE UPDATE}/{@code BEFORE DELETE} triggers holding the line.
 * {@code ob_prereq_comments} is the same kind of table and gets the same
 * treatment.
 *
 * <p>{@code ob_prereq_history} is the other kind. It carries a chain, so
 * every append has to take the per-client lock and hash the row — and
 * {@code ObPrereqHistoryRepository} therefore extends {@code AppendOnly},
 * which makes {@code ObPrereqJournal} its only legal caller.
 * {@code AppendOnlyRulesTest} states that over the type rather than the
 * verb, so even a read from here would violate it. That is the right rule:
 * the moment a feature package holds the interface, the guarantee is one
 * autocomplete away from being written through. So the history read goes
 * around it in SQL, which cannot insert.
 *
 * <p>Both listings are joins in any case — a comment needs its author's
 * display name from either {@code users} or {@code ob_client_contacts}, and a
 * history entry the same — so a JPA read would be two more mappings of
 * tables other features own.
 *
 * <h2>Keyset on {@code id}, not on the timestamp</h2>
 *
 * <p>{@code ObCommunicationRepository} pages on {@code occurred_at|id}
 * because its rows are backdated. These are not: both tables are strictly
 * append-only with an auto-increment id, so id order <em>is</em> insertion
 * order and a single-column keyset is exact. The {@link Cursor}'s
 * {@code sortKey} carries the id as text so the shared encoder is still the
 * one in use.
 */
@Repository
class ObPrereqThreadRepository {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcClient jdbc;

    ObPrereqThreadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ObClientPrereqDtos.ObPrereqCommentListResponse comments(long prereqTaskId, String cursor, Integer limit) {
        int size = pageSize(limit);
        Long after = afterId(cursor);

        List<ObClientPrereqDtos.ObPrereqComment> rows = jdbc.sql("""
                        SELECT c.id, c.prereq_task_id, c.author_type, c.body, c.is_system, c.created_at,
                               u.id AS staffId, u.full_name AS staffName,
                               k.id AS contactId, k.name AS contactName, k.email AS contactEmail
                          FROM ob_prereq_comments c
                          LEFT JOIN users u ON u.id = c.author_user_id
                          LEFT JOIN ob_client_contacts k ON k.id = c.author_contact_id
                         WHERE c.prereq_task_id = :taskId
                           AND (:afterId IS NULL OR c.id > :afterId)
                         ORDER BY c.id ASC
                         LIMIT :limit
                        """)
                .param("taskId", prereqTaskId)
                .param("afterId", after)
                .param("limit", size + 1)
                .query((rs, n) -> new ObClientPrereqDtos.ObPrereqComment(
                        rs.getLong("id"),
                        rs.getLong("prereq_task_id"),
                        ObPrereqActorType.valueOf(rs.getString("author_type")),
                        rs.getObject("staffId") == null ? null
                                : new ObClientPrereqDtos.UserRef(rs.getLong("staffId"), rs.getString("staffName")),
                        rs.getObject("contactId") == null ? null
                                : new ObClientPrereqDtos.ObContactRef(rs.getLong("contactId"),
                                        rs.getString("contactName"), rs.getString("contactEmail")),
                        rs.getString("body"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();

        boolean hasMore = rows.size() > size;
        List<ObClientPrereqDtos.ObPrereqComment> page = trim(rows, size);
        String next = hasMore && !page.isEmpty()
                ? new Cursor(String.valueOf(page.get(page.size() - 1).id()),
                        page.get(page.size() - 1).id()).encode()
                : null;
        return new ObClientPrereqDtos.ObPrereqCommentListResponse(page, new PageMeta(next, hasMore));
    }

    ObClientPrereqDtos.ObPrereqHistoryListResponse history(long prereqTaskId, String cursor, Integer limit) {
        int size = pageSize(limit);
        Long after = afterId(cursor);

        List<ObClientPrereqDtos.ObPrereqHistoryEntry> rows = jdbc.sql("""
                        SELECT h.id, h.prereq_task_id, h.occurred_at, h.actor_type,
                               h.from_status, h.to_status, h.reason,
                               h.is_correction, h.corrects_entry_id,
                               u.id AS staffId, u.full_name AS staffName,
                               k.id AS contactId, k.name AS contactName, k.email AS contactEmail
                          FROM ob_prereq_history h
                          LEFT JOIN users u ON u.id = h.actor_user_id
                          LEFT JOIN ob_client_contacts k ON k.id = h.actor_contact_id
                         WHERE h.prereq_task_id = :taskId
                           AND (:afterId IS NULL OR h.id > :afterId)
                         ORDER BY h.id ASC
                         LIMIT :limit
                        """)
                .param("taskId", prereqTaskId)
                .param("afterId", after)
                .param("limit", size + 1)
                .query((rs, n) -> new ObClientPrereqDtos.ObPrereqHistoryEntry(
                        rs.getLong("id"),
                        rs.getLong("prereq_task_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        ObPrereqActorType.valueOf(rs.getString("actor_type")),
                        rs.getObject("staffId") == null ? null
                                : new ObClientPrereqDtos.UserRef(rs.getLong("staffId"), rs.getString("staffName")),
                        rs.getObject("contactId") == null ? null
                                : new ObClientPrereqDtos.ObContactRef(rs.getLong("contactId"),
                                        rs.getString("contactName"), rs.getString("contactEmail")),
                        rs.getString("from_status") == null ? null
                                : ObPrereqTaskStatus.valueOf(rs.getString("from_status")),
                        ObPrereqTaskStatus.valueOf(rs.getString("to_status")),
                        rs.getString("reason"),
                        rs.getBoolean("is_correction"),
                        rs.getObject("corrects_entry_id") == null ? null
                                : rs.getLong("corrects_entry_id")))
                .list();

        boolean hasMore = rows.size() > size;
        List<ObClientPrereqDtos.ObPrereqHistoryEntry> page = trim(rows, size);
        String next = hasMore && !page.isEmpty()
                ? new Cursor(String.valueOf(page.get(page.size() - 1).id()),
                        page.get(page.size() - 1).id()).encode()
                : null;
        return new ObClientPrereqDtos.ObPrereqHistoryListResponse(page, new PageMeta(next, hasMore));
    }

    /**
     * Append one comment. The only write in this class, and it is an insert:
     * the table's triggers refuse UPDATE and DELETE outright, so there is
     * nothing else it could offer.
     *
     * @return the generated id
     */
    long insertComment(long prereqTaskId, ObPrereqActorType authorType,
                       Long authorUserId, Long authorContactId, String body, boolean system) {

        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO ob_prereq_comments
                               (prereq_task_id, author_type, author_user_id,
                                author_contact_id, body, is_system)
                        VALUES (:taskId, :authorType, :userId, :contactId, :body, :system)
                        """)
                .param("taskId", prereqTaskId)
                .param("authorType", authorType.name())
                .param("userId", authorUserId)
                .param("contactId", authorContactId)
                .param("body", body)
                .param("system", system)
                .update(keys);
        return keys.getKey().longValue();
    }

    /** One comment, for the 201 body. */
    ObClientPrereqDtos.ObPrereqComment comment(long id) {
        return jdbc.sql("""
                        SELECT c.id, c.prereq_task_id, c.author_type, c.body, c.is_system, c.created_at,
                               u.id AS staffId, u.full_name AS staffName,
                               k.id AS contactId, k.name AS contactName, k.email AS contactEmail
                          FROM ob_prereq_comments c
                          LEFT JOIN users u ON u.id = c.author_user_id
                          LEFT JOIN ob_client_contacts k ON k.id = c.author_contact_id
                         WHERE c.id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new ObClientPrereqDtos.ObPrereqComment(
                        rs.getLong("id"),
                        rs.getLong("prereq_task_id"),
                        ObPrereqActorType.valueOf(rs.getString("author_type")),
                        rs.getObject("staffId") == null ? null
                                : new ObClientPrereqDtos.UserRef(rs.getLong("staffId"), rs.getString("staffName")),
                        rs.getObject("contactId") == null ? null
                                : new ObClientPrereqDtos.ObContactRef(rs.getLong("contactId"),
                                        rs.getString("contactName"), rs.getString("contactEmail")),
                        rs.getString("body"),
                        rs.getBoolean("is_system"),
                        rs.getTimestamp("created_at").toInstant()))
                .single();
    }

    private static <T> List<T> trim(List<T> rows, int size) {
        return rows.size() > size ? new ArrayList<>(rows.subList(0, size)) : rows;
    }

    private static int pageSize(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Malformed or absent is the first page rather than a 400 —
     * {@code Cursor}'s own convention, which {@code ObCommunicationRepository}
     * follows for the same reason: a cursor is an opaque token the client
     * echoes back, so a bad one is far more likely to be a stale bookmark
     * than an attack, and starting over is what the reader wanted anyway.
     */
    private static Long afterId(String cursor) {
        Cursor decoded = Cursor.decode(cursor);
        return decoded == null ? null : decoded.id();
    }
}
