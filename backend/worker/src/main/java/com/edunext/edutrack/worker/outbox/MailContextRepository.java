package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.MergeTag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * D-029 · everything a ticket mail's merge tags need, in one read.
 *
 * <p>One query rather than a walk from a {@code Ticket} entity through its
 * project, client and assignee. The outbox worker renders one mail per row it
 * claims, in a loop, so a lazy-loaded graph here is four round trips per mail
 * multiplied by every recipient of every event — and none of it is needed
 * beyond building five strings.
 */
@Repository
class MailContextRepository {

    /**
     * <p>Every join is {@code LEFT}. A ticket legitimately has no client (an
     * internal one), no assignee (before D-026 nudges somebody into taking it)
     * and no planned close date, and a mail about a breach must not fail to
     * render because the ticket it describes is incomplete — that is precisely
     * the ticket somebody needs to be told about.
     */
    private static final String CONTEXT = """
            SELECT t.ticket_code   AS ticketCode,
                   t.title         AS title,
                   t.level         AS level,
                   t.status        AS status,
                   t.current_stage AS stage,
                   t.planned_close_date AS plannedClose,
                   t.current_cycle_no   AS cycleNo,
                   t.current_iteration  AS iteration,
                   p.name          AS projectName,
                   c.name          AS clientName,
                   a.full_name     AS assigneeName
              FROM tickets t
              LEFT JOIN projects p ON p.id = t.project_id
              LEFT JOIN clients  c ON c.id = t.client_id
              LEFT JOIN users    a ON a.id = t.assigned_to
             WHERE t.id = :id
            """;

    /**
     * Dates are rendered as a date, not an instant.
     *
     * <p>{@code {{planned_close}}} lands in a sentence a human reads — "due
     * 22 Aug 2026" — and a raw {@code 2026-08-22T18:00:00Z} in that position
     * reads as a system error even when it is correct. The time of day is
     * dropped deliberately: a Planned Close Date is a commitment to a day.
     */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final JdbcClient jdbc;
    private final String baseUrl;

    MailContextRepository(JdbcClient jdbc,
                          @Value("${edutrack.app.base-url:http://localhost:5173}") String baseUrl) {
        this.jdbc = jdbc;
        // Trailing slash stripped once here rather than guarded at every use:
        // "…/#/tickets" and "…//tickets" are both wrong and only one of them
        // looks wrong.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * @return the merge values for {@code ticketId}, or empty if the ticket has
     *         gone — a mail can outlive its subject, and rendering it with blank
     *         fields is better than not telling anybody anything
     */
    MailContext forTicket(long ticketId) {
        Optional<MailContext> found = jdbc.sql(CONTEXT)
                .param("id", ticketId)
                .query((rs, n) -> {
                    String code = rs.getString("ticketCode");
                    Timestamp plannedClose = rs.getTimestamp("plannedClose");
                    return MailContext.builder()
                            .put(MergeTag.TICKET_ID, code)
                            .put(MergeTag.TICKET_TITLE, rs.getString("title"))
                            .put(MergeTag.LEVEL, rs.getString("level"))
                            .put(MergeTag.STATUS, rs.getString("status"))
                            .put(MergeTag.STAGE, rs.getString("stage"))
                            .put(MergeTag.PROJECT, rs.getString("projectName"))
                            .put(MergeTag.CLIENT, rs.getString("clientName"))
                            .put(MergeTag.ASSIGNEE, rs.getString("assigneeName"))
                            .put(MergeTag.PLANNED_CLOSE,
                                    plannedClose == null ? null : DAY.format(plannedClose.toInstant()))
                            .put(MergeTag.CYCLE, String.valueOf(rs.getInt("cycleNo")))
                            .put(MergeTag.ITERATION, String.valueOf(rs.getInt("iteration")))
                            // The deep link behind D-030's "Open ticket" button.
                            // Keyed on the code, not the numeric id: the code is
                            // what a recipient can also paste into search.
                            .put(MergeTag.TICKET_URL, code == null ? null : baseUrl + "/tickets/" + code)
                            .build();
                })
                .optional();
        return found.orElseGet(MailContext::empty);
    }
}
