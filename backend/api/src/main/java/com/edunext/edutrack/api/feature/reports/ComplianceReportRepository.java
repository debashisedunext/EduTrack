package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A-068 · the two §7.8 reports that read neither a summary table nor
 * {@code tickets} — the audit trail and the mail delivery log.
 *
 * <h2>Why a third repository rather than a third method on the other two</h2>
 *
 * <p>{@link ReportRepository}'s own class note anticipated this split before
 * either report existed: reports read "the summary tables, never
 * {@code tickets}", {@link TicketReportRepository} takes "the ticket-level
 * ones", and "the audit log or the delivery log for reports still to come".
 * These are those two.
 *
 * <p>The line is not stylistic. {@code audit_logs} and {@code email_log} are
 * both <b>organisation-wide tables with no {@code project_id}</b>, which makes
 * §2's row rule a different problem here than anywhere else in this package —
 * see the two scope notes below. Putting them beside queries that scope by a
 * plain {@code t.project_id IN (…)} predicate is how somebody later copies that
 * predicate onto a table that has no such column and gets a compile error, or
 * worse, joins their way to one and quietly widens what a PM can read.
 */
@Repository
class ComplianceReportRepository {

    private final JdbcClient jdbc;

    ComplianceReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── A-068 report 16 · Audit & Compliance ─────────────────────────────────

    /**
     * §7.8's "full immutable trail export for a ticket or date range, including
     * every handoff".
     *
     * <h2>The trail is {@code ticket_history}, not {@code audit_logs}</h2>
     *
     * <p>Both tables record who did what, and picking the wrong one would look
     * right on screen. The deciding property is the catalogue's own promise —
     * "with the hash chain's own verdict on each entry". {@code ticket_history}
     * is hash-chained and trigger-protected; {@code audit_logs} is neither, and
     * A-071's own note says so in as many words: <i>"there is no fifth layer …
     * {@code audit_logs} is not hash-chained, so tampering that first defeats
     * the triggers is undetectable"</i>. A compliance export served from the
     * unchained table would carry a verdict column it could not honestly fill.
     *
     * <p>{@code audit_logs} keeps its own screen (S-16, A-071), which is the
     * right home for logins and permission changes — events that are not about
     * a ticket at all and so cannot appear in a per-ticket trail.
     *
     * <h2>"Including every handoff" is satisfied without joining the hops</h2>
     *
     * <p>{@code TransitionService.advance} appends a {@code STAGE_CHANGED}
     * history row in the same transaction as the hop it writes, so a handoff is
     * already in this table. Joining {@code ticket_stage_transitions} as well
     * would double every handoff — once as its history row and once as its hop —
     * and the second copy would carry no actor for the reader to hold to
     * account.
     *
     * <h2>Scope</h2>
     *
     * <p>{@code ticket_history} has no {@code project_id}; it reaches one
     * through {@code tickets}, which is the join below. That makes §2's rule
     * expressible exactly as it is everywhere else, and it is the reason this
     * report can be offered to a PM at all.
     *
     * <p>Ordered oldest-first within a ticket. A trail is read forwards, and
     * {@code id} ascending is also the chain's own order — the same order
     * {@code findByTicketIdOrderByIdAsc} and the verifier walk, so a reader
     * comparing this export against either sees the same sequence.
     */
    List<TrailRow> auditTrail(LocalDate from, LocalDate to, List<Long> projectIds,
                              boolean ownWork, long userId, Long resourceId, int limit) {
        return jdbc.sql("""
                        SELECT h.id                AS entry_id,
                               h.ticket_id         AS ticket_id,
                               t.ticket_code       AS ticket_code,
                               p.name              AS project_name,
                               h.created_at        AS created_at,
                               h.cycle_no          AS cycle_no,
                               h.event_type        AS event_type,
                               h.field_name        AS field_name,
                               h.old_value         AS old_value,
                               h.new_value         AS new_value,
                               h.actor_id          AS actor_id,
                               h.actor_type        AS actor_type,
                               h.remarks           AS remarks,
                               COALESCE(u.full_name, u.username, '(system)') AS actor_name,
                               h.is_correction     AS is_correction,
                               h.corrects_entry_id AS corrects_entry_id,
                               h.prev_hash         AS prev_hash,
                               h.row_hash          AS row_hash
                          FROM ticket_history h
                          JOIN tickets t    ON t.id = h.ticket_id
                          JOIN projects p   ON p.id = t.project_id
                          LEFT JOIN users u ON u.id = h.actor_id
                         WHERE h.created_at >= :fromTs
                           AND h.created_at < :toExclusiveTs
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR h.actor_id = :resourceId)
                      ORDER BY h.ticket_id, h.id
                         LIMIT :limit
                        """)
                .param("fromTs", from.atStartOfDay())
                .param("toExclusiveTs", to.plusDays(1).atStartOfDay())
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .param("limit", limit)
                .query((rs, n) -> {
                    // Hydrated rather than mapped field-by-field into a payload
                    // here: ChainPayloads decides which columns are covered by
                    // the digest, and that decision must have exactly one home.
                    // Rebuilding the map in this class would be a second
                    // statement of the rule that could drift from the writer's
                    // — and a chain check that drifts reports "verified" for a
                    // row it never actually covered.
                    TicketHistory entry = new TicketHistory();
                    entry.setId(rs.getLong("entry_id"));
                    entry.setTicketId(rs.getLong("ticket_id"));
                    entry.setCycleNo(rs.getObject("cycle_no", Short.class));
                    entry.setEventType(rs.getString("event_type"));
                    entry.setFieldName(rs.getString("field_name"));
                    entry.setOldValue(rs.getString("old_value"));
                    entry.setNewValue(rs.getString("new_value"));
                    entry.setActorId(rs.getObject("actor_id", Long.class));
                    entry.setActorType(rs.getString("actor_type"));
                    entry.setRemarks(rs.getString("remarks"));
                    entry.setCorrection(rs.getBoolean("is_correction"));
                    entry.setCorrectsEntryId(rs.getObject("corrects_entry_id", Long.class));
                    entry.setPrevHash(rs.getString("prev_hash"));
                    entry.setRowHash(rs.getString("row_hash"));

                    return new TrailRow(
                            rs.getLong("ticket_id"), rs.getString("ticket_code"),
                            rs.getString("project_name"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getString("actor_name"), entry);
                })
                .list();
    }

    // ── A-068 report 18 · Email Delivery Log ─────────────────────────────────

    /**
     * §7.8's "every alert sent per ticket with status — proof that the assignee
     * was notified, and a fast way to spot bounced addresses".
     *
     * <h2>Rows, not an aggregate, because of what it is for</h2>
     *
     * <p>The two uses §7.8 names are both lookups of a particular mail: proving
     * one person was told about one ticket, and finding the address that is
     * bouncing. A count per status would answer neither. This is the one report
     * in A-066…A-068 whose natural shape is a list, which is why its descriptor
     * carries no chart type.
     *
     * <h2>Scope, and the one report where §2 cannot be fully expressed</h2>
     *
     * <p>{@code email_log.ticket_id} is <b>nullable</b> — "NULL for non-ticket
     * mail", says the column's own comment, and password resets and digests are
     * exactly that. A row with no ticket has no project, so no
     * {@code project_id IN (…)} predicate can include it without widening, or
     * exclude it without hiding mail that was really sent.
     *
     * <p>Resolved by <b>excluding non-ticket mail from this report entirely</b>
     * rather than by leaking it or by guessing. §7.8 asks for "every alert sent
     * per ticket", so ticket mail is the whole of what was requested; a password
     * reset is not an alert about a ticket and belongs to the audit trail, where
     * the login events already are. The runner states this on the row set rather
     * than leaving the total to be misread as all mail the system has sent.
     *
     * <p>Newest first — a bounce is looked for immediately after it happens.
     */
    List<MailRow> deliveryLog(LocalDate from, LocalDate to, List<Long> projectIds,
                              boolean ownWork, long userId, int limit) {
        return jdbc.sql("""
                        SELECT e.id          AS log_id,
                               t.ticket_code AS ticket_code,
                               p.name        AS project_name,
                               e.event_code  AS event_code,
                               e.to_email    AS to_email,
                               COALESCE(u.full_name, u.username, '(external)') AS recipient,
                               e.subject     AS subject,
                               e.status      AS status,
                               e.retry_count AS retry_count,
                               e.queued_at   AS queued_at,
                               e.sent_at     AS sent_at,
                               e.error_text  AS error_text
                          FROM email_log e
                          JOIN tickets t    ON t.id = e.ticket_id
                          JOIN projects p   ON p.id = t.project_id
                          LEFT JOIN users u ON u.id = e.to_user_id
                         WHERE e.queued_at >= :fromTs
                           AND e.queued_at < :toExclusiveTs
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                      ORDER BY e.queued_at DESC, e.id DESC
                         LIMIT :limit
                        """)
                .param("fromTs", from.atStartOfDay())
                .param("toExclusiveTs", to.plusDays(1).atStartOfDay())
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, n) -> new MailRow(
                        rs.getLong("log_id"), rs.getString("ticket_code"),
                        rs.getString("project_name"), rs.getString("event_code"),
                        rs.getString("to_email"), rs.getString("recipient"),
                        rs.getString("subject"), rs.getString("status"),
                        rs.getInt("retry_count"),
                        rs.getObject("queued_at", LocalDateTime.class),
                        rs.getObject("sent_at", LocalDateTime.class),
                        rs.getString("error_text")))
                .list();
    }

    /**
     * One trail entry, with the joined display values beside the entity itself.
     *
     * @param actorName resolved here rather than in the runner because it needs
     *                  a join, and it follows {@code TransitionUserRefs}' rule
     *                  verbatim — {@code full_name}, falling back to the
     *                  username, then to {@code (system)} for a null actor,
     *                  which §8.2 says means the row was written by the
     *                  application rather than by a person.
     * @param entry     the row as the writer built it, hydrated so that
     *                  {@code ChainPayloads} can be handed the same object shape
     *                  it hashed. Verification is <b>not</b> done in SQL and not
     *                  done from loose columns: the digest and the payload
     *                  composition live in {@code domain.journal} and are used
     *                  from there, so this report and the nightly verifier
     *                  cannot reach different verdicts about the same row.
     */
    record TrailRow(long ticketId, String ticketCode, String projectName,
                    LocalDateTime createdAt, String actorName, TicketHistory entry) {
    }

    record MailRow(long logId, String ticketCode, String projectName, String eventCode,
                   String toEmail, String recipient, String subject, String status,
                   int retryCount, LocalDateTime queuedAt, LocalDateTime sentAt,
                   String errorText) {
    }
}
