package com.edunext.edutrack.api.feature.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * D-050 · the read and write side of chat.
 *
 * <p>Plain SQL through {@link JdbcClient} for the same reason as
 * {@code AuthUserRepository}: B-005 owns the entities and has not started, and
 * chat does not need them.
 *
 * <p><strong>Every query is scoped by participation.</strong> Not as a
 * convenience — a thread you are not in must be indistinguishable from a thread
 * that does not exist. That is the same rule as A-034's row scoping, applied
 * here without waiting for it, because chat membership is explicit in
 * {@code chat_participants} and needs no role reasoning to enforce.
 */
@Repository
class ChatRepository {

    /**
     * Threads this user is in.
     *
     * <p>{@code unread_count} is derived from the participant's
     * {@code last_read_message_id} cursor rather than a per-user-per-message
     * row: "messages with a higher id" is one indexed comparison, and the
     * baseline chose that shape deliberately.
     *
     * <p>{@code COALESCE(last_read_message_id, 0)} matters — a participant who
     * has never opened the thread has NULL there, and {@code id > NULL} is
     * NULL, so without it every brand-new thread would report zero unread.
     */
    private static final String THREADS_FOR_USER = """
            SELECT t.id, t.thread_type, t.subject, t.ticket_id, t.project_id,
                   tk.ticket_code,
                   p.name       AS project_name,
                   (SELECT COUNT(*) FROM chat_messages m
                     WHERE m.thread_id = t.id
                       AND m.id > COALESCE(cp.last_read_message_id, 0)
                       AND m.sender_id <> :userId)          AS unread_count,
                   (SELECT MAX(m2.created_at) FROM chat_messages m2
                     WHERE m2.thread_id = t.id)             AS last_message_at
              FROM chat_threads t
              JOIN chat_participants cp
                ON cp.thread_id = t.id AND cp.user_id = :userId
              LEFT JOIN tickets  tk ON tk.id = t.ticket_id
              LEFT JOIN projects p  ON p.id  = t.project_id
             WHERE t.is_active = 1
            """;

    private static final String MESSAGES_PAGE = """
            SELECT m.id, m.thread_id, m.body, m.kind, m.sender_id, m.created_at,
                   m.edited_at, m.deleted_at, u.full_name AS sender_name
              FROM chat_messages m
              LEFT JOIN users u ON u.id = m.sender_id
             WHERE m.thread_id = :threadId
               AND (:cursor IS NULL OR m.id < :cursor)
             ORDER BY m.id DESC
             LIMIT :limit
            """;

    private static final String INSERT_MESSAGE = """
            INSERT INTO chat_messages (thread_id, sender_id, body, kind, is_system, mentioned_user_ids)
            VALUES (:threadId, :senderId, :body, :kind, :isSystem, NULL)
            """;

    private static final String PARTICIPANTS = """
            SELECT cp.thread_id, cp.user_id, u.full_name
              FROM chat_participants cp
              JOIN users u ON u.id = cp.user_id
             WHERE cp.thread_id IN (:threadIds)
            """;

    /**
     * The membership check, and the anchor the STOMP destination is built from.
     *
     * <p>Deliberately one query: asking "may I?" and "where does it go?"
     * separately invites the second to be answered for a thread the first
     * rejected.
     */
    private static final String THREAD_FOR_PARTICIPANT = """
            SELECT t.id, t.thread_type, t.ticket_id, t.project_id
              FROM chat_threads t
              JOIN chat_participants cp
                ON cp.thread_id = t.id AND cp.user_id = :userId
             WHERE t.id = :threadId AND t.is_active = 1
            """;

    private static final String PARTICIPANT_IDS = """
            SELECT user_id FROM chat_participants WHERE thread_id = :threadId
            """;

    /**
     * D-051 · every participant's read cursor for one thread.
     *
     * <p>Read receipts are derived from the cursor rather than stored per
     * message: "who has read message 42" is "whose cursor is at least 42". A
     * row per reader per message would be the same information at hundreds of
     * times the write cost, and the baseline chose the cursor deliberately.
     *
     * <p>One query per page, not per message. The alternative — a correlated
     * subquery inside the message list — turns a fifty-message page into fifty
     * extra reads for information the client renders as a row of small avatars.
     */
    private static final String READ_CURSORS = """
            SELECT user_id, COALESCE(last_read_message_id, 0) AS lastReadMessageId
              FROM chat_participants
             WHERE thread_id = :threadId
            """;

    /**
     * D-057 · everything needed to decide whether an edit is allowed, in one
     * query, evaluated by the database.
     *
     * <p><strong>The window is computed in SQL on purpose.</strong> Comparing
     * {@code created_at} against an application clock makes the five minutes
     * mean something slightly different on every instance, and a message that
     * is editable on one pod and frozen on another is the kind of inconsistency
     * that only shows up in production. The row's own clock decides.
     *
     * <p>{@code sender_id = :userId} is the author check and the join to
     * {@code chat_participants} is the membership check. Both are here rather
     * than in Java so that "no such message" and "not yours" come back
     * indistinguishable — the caller cannot tell them apart, and neither can
     * anyone probing for message ids.
     */
    private static final String EDITABILITY = """
            SELECT m.id,
                   m.deleted_at IS NOT NULL                                AS deleted,
                   m.created_at > NOW(6) - INTERVAL 5 MINUTE               AS withinWindow
              FROM chat_messages m
              JOIN chat_participants cp
                ON cp.thread_id = m.thread_id AND cp.user_id = :userId
             WHERE m.id = :messageId
               AND m.thread_id = :threadId
               AND m.sender_id = :userId
            """;

    /**
     * The window is re-checked here, not just in {@link #EDITABILITY}. The two
     * run in the same transaction so nothing can change between them today —
     * but a later refactor that splits them would silently lose the limit, and
     * this is the guarantee that must not erode.
     */
    private static final String EDIT_BODY = """
            UPDATE chat_messages
               SET body = :body, edited_at = NOW(6)
             WHERE id = :messageId
               AND thread_id = :threadId
               AND sender_id = :userId
               AND deleted_at IS NULL
               AND created_at > NOW(6) - INTERVAL 5 MINUTE
            """;

    /**
     * The tombstone. {@code body} is deliberately left intact: withholding it
     * on read is a presentation decision, and destroying it would throw away
     * the evidence §7.6 exists to preserve.
     *
     * <p>{@code deleted_at IS NULL} makes a repeat delete a no-op rather than
     * restamping the time, so the record shows when it was actually removed.
     */
    private static final String SOFT_DELETE = """
            UPDATE chat_messages
               SET deleted_at = NOW(6), deleted_by = :userId
             WHERE id = :messageId
               AND thread_id = :threadId
               AND sender_id = :userId
               AND deleted_at IS NULL
            """;

    private static final String MESSAGE_BY_ID = """
            SELECT m.id, m.thread_id, m.body, m.kind, m.sender_id, m.created_at,
                   m.edited_at, m.deleted_at, u.full_name AS sender_name
              FROM chat_messages m
              LEFT JOIN users u ON u.id = m.sender_id
             WHERE m.id = :messageId
            """;

    /** Advance the read cursor. Never rewinds — see the WHERE clause. */
    private static final String ADVANCE_READ_CURSOR = """
            UPDATE chat_participants
               SET last_read_message_id = :messageId
             WHERE thread_id = :threadId
               AND user_id = :userId
               AND (last_read_message_id IS NULL OR last_read_message_id < :messageId)
            """;

    private final JdbcClient jdbc;

    ChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ThreadRow> threadsFor(long userId, ChatKind kind) {
        String sql = THREADS_FOR_USER
                + (kind == null ? "" : " AND t.thread_type = :kind")
                + " ORDER BY last_message_at IS NULL, last_message_at DESC, t.id DESC";
        var spec = jdbc.sql(sql).param("userId", userId);
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        return spec.query(ThreadRow.class).list();
    }

    List<ParticipantRow> participantsOf(List<Long> threadIds) {
        if (threadIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(PARTICIPANTS)
                .param("threadIds", threadIds)
                .query(ParticipantRow.class)
                .list();
    }

    /** Empty when the thread does not exist <em>or</em> the user is not in it. */
    Optional<ThreadAnchor> threadForParticipant(long threadId, long userId) {
        return jdbc.sql(THREAD_FOR_PARTICIPANT)
                .param("threadId", threadId)
                .param("userId", userId)
                .query(ThreadAnchor.class)
                .optional();
    }

    List<MessageRow> messages(long threadId, Long cursor, int limit) {
        return jdbc.sql(MESSAGES_PAGE)
                .param("threadId", threadId)
                .param("cursor", cursor)
                .param("limit", limit)
                .query(MessageRow.class)
                .list();
    }

    long insertMessage(long threadId, Long senderId, String body, MessageKind kind) {
        jdbc.sql(INSERT_MESSAGE)
                .param("threadId", threadId)
                .param("senderId", senderId)
                .param("body", body)
                .param("kind", kind.name())
                .param("isSystem", kind == MessageKind.SYSTEM ? 1 : 0)
                .update();
        // LAST_INSERT_ID() is per-connection and JdbcClient holds one for the
        // duration of the transaction, so this is the row just written even
        // under concurrent posts.
        Long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return id;
    }

    List<ReadCursor> readCursors(long threadId) {
        return jdbc.sql(READ_CURSORS)
                .param("threadId", threadId)
                .query(ReadCursor.class)
                .list();
    }

    List<Long> participantIds(long threadId) {
        return jdbc.sql(PARTICIPANT_IDS).param("threadId", threadId).query(Long.class).list();
    }

    /** Empty when there is no such message, or it is not the caller's own. */
    Optional<Editability> editability(long threadId, long messageId, long userId) {
        return jdbc.sql(EDITABILITY)
                .param("threadId", threadId)
                .param("messageId", messageId)
                .param("userId", userId)
                .query(Editability.class)
                .optional();
    }

    /** @return true if the row was actually updated */
    boolean editBody(long threadId, long messageId, long userId, String body) {
        return jdbc.sql(EDIT_BODY)
                .param("threadId", threadId)
                .param("messageId", messageId)
                .param("userId", userId)
                .param("body", body)
                .update() == 1;
    }

    /** @return true if this call was the one that deleted it */
    boolean softDelete(long threadId, long messageId, long userId) {
        return jdbc.sql(SOFT_DELETE)
                .param("threadId", threadId)
                .param("messageId", messageId)
                .param("userId", userId)
                .update() == 1;
    }

    Optional<MessageRow> messageById(long messageId) {
        return jdbc.sql(MESSAGE_BY_ID)
                .param("messageId", messageId)
                .query(MessageRow.class)
                .optional();
    }

    /** @return true if the cursor actually moved, so callers can avoid a needless broadcast */
    boolean advanceReadCursor(long threadId, long userId, long messageId) {
        return jdbc.sql(ADVANCE_READ_CURSOR)
                .param("threadId", threadId)
                .param("userId", userId)
                .param("messageId", messageId)
                .update() == 1;
    }

    // ------------------------------------------------------------------ rows

    record ThreadRow(
            long id,
            String threadType,
            String subject,
            Long ticketId,
            Long projectId,
            String ticketCode,
            String projectName,
            int unreadCount,
            Timestamp lastMessageAt) {

        Instant lastMessageInstant() {
            return lastMessageAt == null ? null : lastMessageAt.toInstant();
        }
    }

    record ParticipantRow(long threadId, long userId, String fullName) {
    }

    record MessageRow(
            long id,
            long threadId,
            String body,
            String kind,
            Long senderId,
            Timestamp createdAt,
            Timestamp editedAt,
            Timestamp deletedAt,
            String senderName) {
    }

    /** How far one participant has read. Zero means never opened. */
    record ReadCursor(long userId, long lastReadMessageId) {
    }

    /** Why an edit may or may not proceed, as the database sees it. */
    record Editability(long id, boolean deleted, boolean withinWindow) {
    }

    /** Just enough of a thread to authorise a post and address its broadcast. */
    record ThreadAnchor(long id, String threadType, Long ticketId, Long projectId) {

        ChatKind kind() {
            // ASK_STATUS threads predate the contract's model and behave as
            // ticket threads; see MessageKind for why that stayed.
            return "ASK_STATUS".equals(threadType) ? ChatKind.TICKET : ChatKind.valueOf(threadType);
        }
    }
}
