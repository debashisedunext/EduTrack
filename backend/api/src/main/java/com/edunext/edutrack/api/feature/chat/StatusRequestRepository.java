package com.edunext.edutrack.api.feature.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * D-055 / D-056 · the read and write side of "Ask Status".
 *
 * <p>Plain SQL through {@link JdbcClient}, for the same reason
 * {@link ChatRepository} does: B-005 owns the entities and this feature does
 * not need them.
 */
@Repository
class StatusRequestRepository {

    /**
     * The ticket, and the person a status request would be aimed at.
     *
     * <p>{@code assigned_to} is nullable, and that is the case this feature has
     * to answer for rather than assume away — §11 addresses this notification to
     * the Assignee, and a ticket in the triage queue has none.
     */
    private static final String TICKET = """
            SELECT t.id, t.ticket_code, t.title, t.project_id, t.assigned_to
              FROM tickets t
             WHERE t.ticket_code = :ticketCode
            """;

    /**
     * May this person ask for a status on this ticket?
     *
     * <p>Blueprint §7.6 gives the button to "a Reporting Manager/PM", and §7.5's
     * action bar gives Admin everything. Three ways to qualify, and the query
     * says which rather than the caller assuming:
     *
     * <ol>
     *   <li>the assignee reports to you ({@code users.reporting_manager_id}),</li>
     *   <li>you are a PM on the ticket's project, or</li>
     *   <li>you are an Admin.</li>
     * </ol>
     *
     * <p><strong>This is not a substitute for A-034.</strong> It only ever
     * narrows: whether the caller can <em>see</em> the ticket is still decided
     * by chat membership in {@link ChatRepository#threadForParticipant} —
     * explicit rows in {@code chat_participants}, needing no role reasoning —
     * and this asks the separate question of whether seeing it also means being
     * entitled to demand an update. A check that can only refuse cannot open a
     * hole; CLAUDE.md's warning is about inventing a filter that decides what
     * you may <em>read</em>, and this decides nothing of the kind.
     *
     * <p>{@code COALESCE(pm.role_in_project, r.code)} follows A-003, as D-026's
     * Support Desk query does: a Developer globally may be mapped as PM on one
     * project, and the project's answer wins where it has one.
     */
    private static final String MAY_ASK = """
            SELECT EXISTS (
              SELECT 1
                FROM tickets t
               WHERE t.id = :ticketId
                 AND (
                   EXISTS (SELECT 1 FROM users a
                            WHERE a.id = t.assigned_to
                              AND a.reporting_manager_id = :userId)
                   OR EXISTS (SELECT 1
                                FROM project_members pm
                                JOIN users u ON u.id = pm.user_id
                                JOIN roles r ON r.id = u.role_id
                               WHERE pm.project_id = t.project_id
                                 AND pm.user_id    = :userId
                                 AND pm.is_active  = 1
                                 AND u.is_active   = 1
                                 AND COALESCE(pm.role_in_project, r.code) = 'PM')
                   OR EXISTS (SELECT 1 FROM users u
                                JOIN roles r ON r.id = u.role_id
                               WHERE u.id = :userId
                                 AND u.is_active = 1
                                 AND r.code = 'ADMIN')))
            """;

    /**
     * The ticket's own chat thread.
     *
     * <p>{@code thread_type IN ('TICKET', 'ASK_STATUS')} because the baseline
     * models Ask Status as a thread type and {@link MessageKind} explains why we
     * do not: §7.6 posts the card "into that ticket's thread", so a status
     * request is one message in the ordinary conversation. A pre-existing
     * ASK_STATUS thread on a ticket is that same conversation under an older
     * name, and creating a second one beside it would split the scrollback.
     */
    private static final String TICKET_THREAD = """
            SELECT id FROM chat_threads
             WHERE ticket_id = :ticketId
               AND thread_type IN ('TICKET', 'ASK_STATUS')
               AND is_active = 1
             ORDER BY id
             LIMIT 1
            """;

    private static final String CREATE_TICKET_THREAD = """
            INSERT INTO chat_threads (thread_type, ticket_id, created_by)
            VALUES ('TICKET', :ticketId, :createdBy)
            """;

    /**
     * Idempotent, and it has to be: the requester may already be in the thread,
     * the assignee usually is, and a second Ask Status must not fail on a
     * duplicate key.
     */
    private static final String ADD_PARTICIPANT = """
            INSERT IGNORE INTO chat_participants (thread_id, user_id) VALUES (:threadId, :userId)
            """;

    private static final String OPEN_REQUEST_FROM = """
            SELECT r.id
              FROM ticket_status_requests r
             WHERE r.ticket_id       = :ticketId
               AND r.requested_by_id = :userId
               AND r.answered_at IS NULL
            """;

    private static final String INSERT_REQUEST = """
            INSERT INTO ticket_status_requests
                   (ticket_id, thread_id, request_message_id,
                    requested_by_id, asked_of_id, requested_at)
            VALUES (:ticketId, :threadId, :messageId, :requestedBy, :askedOf, :requestedAt)
            """;

    /**
     * Is anybody waiting on an answer in this thread?
     *
     * <p>Asked before {@link #ANSWERABLE} on every single chat post, which is
     * why it is an {@code EXISTS} on an index rather than the fuller query. The
     * overwhelmingly common answer is no, and a thread with nothing outstanding
     * should not pay for a join to {@code tickets} to find that out — still less
     * take row locks for an UPDATE that would match nothing.
     */
    private static final String HAS_OPEN = """
            SELECT EXISTS (
              SELECT 1 FROM ticket_status_requests
               WHERE thread_id = :threadId AND answered_at IS NULL)
            """;

    /**
     * The open requests this message answers.
     *
     * <p><strong>Who counts as having answered</strong> is the one real design
     * decision in D-056, because the metric is only as honest as this clause.
     *
     * <ul>
     *   <li>{@code asked_of_id = :senderId} — the person we asked has spoken.
     *       The obvious case.</li>
     *   <li>{@code t.assigned_to = :senderId} — the ticket was reassigned after
     *       the ask and the new owner replied. Without this, a reassignment
     *       strands the request open forever: the person we asked no longer has
     *       the ticket and has no reason to answer, while the person who does
     *       cannot close it. The manager's list would fill with rows nobody can
     *       clear.</li>
     *   <li>{@code requested_by_id <> :senderId} — the manager cannot answer
     *       their own question. They routinely post follow-ups in the same
     *       thread ("any update?"), and each one would otherwise close the
     *       request it was chasing and record a flattering response time.</li>
     * </ul>
     *
     * <p>{@code asked_of_id} is still recorded and never rewritten, so the
     * metric can always say who was asked even when somebody else replied.
     */
    private static final String ANSWERABLE = """
            SELECT r.id, r.ticket_id, r.thread_id, r.requested_by_id, r.asked_of_id,
                   r.requested_at, t.project_id, t.ticket_code
              FROM ticket_status_requests r
              JOIN tickets t ON t.id = r.ticket_id
             WHERE r.thread_id = :threadId
               AND r.answered_at IS NULL
               AND r.requested_by_id <> :senderId
               AND (r.asked_of_id = :senderId OR t.assigned_to = :senderId)
            """;

    /**
     * Close one request.
     *
     * <p>{@code answered_at IS NULL} stays in the WHERE so the row count is the
     * answer to "was this call the one that closed it". Two replies landing
     * together must raise one notification to the manager, not two — the same
     * claim-by-rowcount discipline D-020 and D-022 use, and the D-022 defect
     * that taught it.
     */
    private static final String CLOSE_REQUEST = """
            UPDATE ticket_status_requests
               SET answer_message_id     = :messageId,
                   answered_by_id        = :answeredBy,
                   answered_at           = :answeredAt,
                   response_working_mins = :workingMins
             WHERE id = :id
               AND answered_at IS NULL
            """;

    /**
     * One request with everything a client renders, including the note as it
     * currently stands.
     *
     * <p>The note is read from {@code chat_messages} rather than copied into
     * this table, and {@code deleted_at} is honoured here exactly as it is on a
     * message read. A manager may delete what they asked; a copy of it living on
     * in the awaiting-response list would be the one place the §7.6 tombstone
     * does not reach — the same argument D-052 makes for a mention notification
     * carrying no message text.
     */
    private static final String SELECT_REQUESTS = """
            SELECT r.id, r.ticket_id, t.ticket_code, t.title AS ticket_title,
                   r.thread_id, r.request_message_id,
                   r.requested_by_id, rb.full_name AS requested_by_name,
                   r.asked_of_id,     ao.full_name AS asked_of_name,
                   r.requested_at,
                   r.answer_message_id, r.answered_by_id, r.answered_at,
                   r.response_working_mins,
                   CASE WHEN m.deleted_at IS NULL THEN m.body END AS note
              FROM ticket_status_requests r
              JOIN tickets       t  ON t.id  = r.ticket_id
              JOIN users         rb ON rb.id = r.requested_by_id
              JOIN users         ao ON ao.id = r.asked_of_id
              LEFT JOIN chat_messages m ON m.id = r.request_message_id
            """;

    private final JdbcClient jdbc;

    StatusRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * By ticket CODE, because that is what the contract's {@code TicketId} is
     * and therefore what the route receives. The row carries {@code id}, so
     * every query after this one still works by row id — the code is resolved
     * exactly once, here.
     */
    Optional<TicketRow> ticket(String ticketCode) {
        return jdbc.sql(TICKET).param("ticketCode", ticketCode).query(TicketRow.class).optional();
    }

    boolean mayAsk(long userId, long ticketId) {
        return Boolean.TRUE.equals(jdbc.sql(MAY_ASK)
                .param("userId", userId)
                .param("ticketId", ticketId)
                .query(Boolean.class)
                .single());
    }

    /**
     * D-037 · a recipient's real address, for the mandatory status-request mail.
     *
     * <p>Looked up rather than derived. A composed {@code user-7@…} would put a
     * plausible, undeliverable address into {@code email_log}, and every one of
     * those rows reads as an attempt that failed at the provider — which turns
     * D-033's delivery proof into noise. Same reasoning as
     * {@code SlaRepository.emailsOf}.
     *
     * <p>Inactive users are excluded, so they keep the bell entry and get no
     * mail: their address may since have been reassigned to somebody else, and
     * a status request is not a thing to send to the wrong person.
     *
     * @return empty when the user is gone or deactivated — the caller sends no
     *         mail rather than inventing a destination
     */
    Optional<String> activeEmailOf(long userId) {
        return jdbc.sql("SELECT email FROM users WHERE id = :id AND is_active = 1")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    /**
     * The ticket's thread, created if this is the first thing ever said on it.
     *
     * <p>Nothing else in the system creates one. D-050 built the engine and left
     * thread creation to whoever first needed a thread to exist, and that is
     * this task — a manager cannot be told "you may not ask for a status because
     * nobody has chatted about this ticket yet".
     *
     * <p><strong>Membership is minimal on purpose.</strong> The two people this
     * exchange is between are added, and nobody else. Who else belongs in a
     * ticket's conversation — the reporter, the PM, watchers — is a product
     * question that S-25's ticket chat tab has to answer, and guessing at it
     * here would quietly decide it: every person added is one who can read
     * everything subsequently said, and that is not a default to set as a side
     * effect of a button somebody pressed for another reason.
     */
    long ensureTicketThread(long ticketId, long createdBy) {
        Optional<Long> existing = jdbc.sql(TICKET_THREAD)
                .param("ticketId", ticketId).query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql(CREATE_TICKET_THREAD)
                .param("ticketId", ticketId)
                .param("createdBy", createdBy)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    void addParticipant(long threadId, long userId) {
        jdbc.sql(ADD_PARTICIPANT).param("threadId", threadId).param("userId", userId).update();
    }

    /** The caller's own unanswered request on this ticket, if they already have one. */
    Optional<Long> openRequestFrom(long ticketId, long userId) {
        return jdbc.sql(OPEN_REQUEST_FROM)
                .param("ticketId", ticketId)
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    long insert(long ticketId, long threadId, long messageId,
                long requestedBy, long askedOf, Instant requestedAt) {
        jdbc.sql(INSERT_REQUEST)
                .param("ticketId", ticketId)
                .param("threadId", threadId)
                .param("messageId", messageId)
                .param("requestedBy", requestedBy)
                .param("askedOf", askedOf)
                .param("requestedAt", Timestamp.from(requestedAt))
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    boolean hasOpenRequests(long threadId) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_OPEN)
                .param("threadId", threadId)
                .query(Boolean.class)
                .single());
    }

    List<AnswerableRow> answerable(long threadId, long senderId) {
        return jdbc.sql(ANSWERABLE)
                .param("threadId", threadId)
                .param("senderId", senderId)
                .query(AnswerableRow.class)
                .list();
    }

    /** @return true if this call was the one that closed it */
    boolean close(long id, long messageId, long answeredBy, Instant answeredAt, int workingMins) {
        return jdbc.sql(CLOSE_REQUEST)
                .param("id", id)
                .param("messageId", messageId)
                .param("answeredBy", answeredBy)
                .param("answeredAt", Timestamp.from(answeredAt))
                .param("workingMins", workingMins)
                .update() == 1;
    }

    /** D-056 · what this manager is still waiting on, longest wait first. */
    List<RequestRow> awaiting(long requestedBy, int limit) {
        return jdbc.sql(SELECT_REQUESTS + """
                 WHERE r.requested_by_id = :requestedBy
                   AND r.answered_at IS NULL
                 ORDER BY r.requested_at, r.id
                 LIMIT :limit
                """)
                .param("requestedBy", requestedBy)
                .param("limit", limit)
                .query(RequestRow.class)
                .list();
    }

    /** D-056 · the badge — every request still open on one ticket, whoever asked. */
    List<RequestRow> openOnTicket(long ticketId) {
        return jdbc.sql(SELECT_REQUESTS + """
                 WHERE r.ticket_id = :ticketId
                   AND r.answered_at IS NULL
                 ORDER BY r.requested_at, r.id
                """)
                .param("ticketId", ticketId)
                .query(RequestRow.class)
                .list();
    }

    Optional<RequestRow> byId(long id) {
        return jdbc.sql(SELECT_REQUESTS + " WHERE r.id = :id")
                .param("id", id)
                .query(RequestRow.class)
                .optional();
    }

    // ------------------------------------------------------------------ rows

    record TicketRow(long id, String ticketCode, String title, long projectId, Long assignedTo) {
    }

    /** An open request a reply might close, with what the duration maths needs. */
    record AnswerableRow(long id,
                         long ticketId,
                         long threadId,
                         long requestedById,
                         long askedOfId,
                         Timestamp requestedAt,
                         long projectId,
                         String ticketCode) {
    }

    record RequestRow(long id,
                      long ticketId,
                      String ticketCode,
                      String ticketTitle,
                      long threadId,
                      long requestMessageId,
                      long requestedById,
                      String requestedByName,
                      long askedOfId,
                      String askedOfName,
                      Timestamp requestedAt,
                      Long answerMessageId,
                      Long answeredById,
                      Timestamp answeredAt,
                      Integer responseWorkingMins,
                      String note) {
    }
}
