package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-068 · §7.8 report 18, the Email Delivery Log — "every alert sent per ticket
 * with status — proof that the assignee was notified, and a fast way to spot
 * bounced addresses".
 *
 * <h2>A list, and the last of the eighteen to have no chart</h2>
 *
 * <p>Both uses §7.8 names are lookups of a particular mail: proving one person
 * was told about one ticket, and finding the address that is bouncing. Neither
 * is answered by a count per status, and a bar chart of four statuses would be
 * the kind of visual that looks like analysis and supports no decision. Its
 * descriptor carries a null chart type for that reason, which
 * {@code ReportViewerPage} already handles — {@code audit-compliance} is the
 * other.
 *
 * <h2>Ticket mail only, and the report says so rather than implying otherwise</h2>
 *
 * <p>{@code email_log.ticket_id} is nullable — password resets and digests are
 * mail with no ticket. §2's row rule is expressed through {@code tickets}, so a
 * row with no ticket has no project and cannot be scoped either way: including
 * it leaks, excluding it silently hides mail that was genuinely sent.
 *
 * <p>The repository resolves that by joining {@code tickets} inner, so
 * non-ticket mail is out of scope by construction rather than by a filter
 * somebody could relax. §7.8 asked for "every alert sent <em>per ticket</em>",
 * so nothing requested is missing — but the total on screen is not all mail the
 * system has sent, and a reader counting bounces here to estimate the health of
 * the mail engine would be reading a subset. That is what
 * {@link #SCOPE_NOTE_ROW} exists to say.
 *
 * <h2>The status vocabulary is the engine's, unmapped</h2>
 *
 * <p>{@code QUEUED|SENT|BOUNCED|FAILED} are shown as they are stored. Prettying
 * them into "Delivered" would be a claim the column cannot support:
 * {@code SENT} means the provider accepted it, and D-036's bounce webhook is
 * what later turns some of those into {@code BOUNCED}. "Sent" and "delivered"
 * are different facts and this report is evidence, so it uses the word the
 * system can actually stand behind.
 *
 * <p>{@code FAILED} carries {@code error_text} and {@code retry_count}; the
 * other three normally do not. Those columns are present on every row anyway
 * rather than the report splitting into two shapes — an empty cell reads as "no
 * error", which is true, and a second table would be a second thing to export.
 */
@Component
class EmailDeliveryLogRunner implements ReportRunner {

    static final String KEY = "email-delivery-log";

    /**
     * The cap, and the same argument as {@code AuditComplianceRunner.MAX_ENTRIES}
     * — generous enough that a ticket's whole mail history is never cut, bounded
     * enough that an unfiltered year is not attempted, and <b>announced</b> when
     * it bites rather than trailing off.
     */
    static final int MAX_ROWS = 5_000;

    static final String SCOPE_NOTE_ROW =
            "Ticket mail only. Password resets and digests carry no ticket, so they have no "
                    + "project to scope by and are not included here.";

    private final ComplianceReportRepository compliance;

    EmailDeliveryLogRunner(ComplianceReportRepository compliance) {
        this.compliance = compliance;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("ticket", "Ticket", STRING),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("event", "Alert", STRING),
                new ReportDtos.Column("recipient", "Recipient", STRING),
                new ReportDtos.Column("toEmail", "Address", STRING),
                new ReportDtos.Column("subject", "Subject", STRING),
                new ReportDtos.Column("status", "Status", STRING),
                new ReportDtos.Column("queuedAt", "Queued", DATE),
                new ReportDtos.Column("sentAt", "Sent", DATE),
                new ReportDtos.Column("retries", "Retries", NUMBER),
                new ReportDtos.Column("error", "Error", STRING));

        List<ComplianceReportRepository.MailRow> log = compliance.deliveryLog(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), MAX_ROWS + 1);

        boolean truncated = log.size() > MAX_ROWS;
        if (truncated) {
            log = log.subList(0, MAX_ROWS);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComplianceReportRepository.MailRow r : log) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket", r.ticketCode());
            row.put("project", r.projectName());
            row.put("event", r.eventCode());
            row.put("recipient", r.recipient());
            row.put("toEmail", r.toEmail());
            row.put("subject", r.subject());
            row.put("status", r.status());
            row.put("queuedAt", r.queuedAt());
            row.put("sentAt", r.sentAt());
            // Zero shown as blank: every mail that went first time has a zero
            // here, and a column of zeroes is noise in the one report whose job
            // is to make the handful of exceptions findable.
            row.put("retries", r.retryCount() == 0 ? null : r.retryCount());
            row.put("error", r.errorText());
            rows.add(row);
        }

        if (truncated) {
            rows.add(notice(columns, "— truncated —",
                    "The first " + MAX_ROWS + " mails are shown, newest first. Narrow the date "
                            + "range for a complete log."));
        }

        return new Result(columns, rows, null);
    }

    private static Map<String, Object> notice(List<ReportDtos.Column> columns,
                                              String event, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ReportDtos.Column c : columns) {
            row.put(c.key(), null);
        }
        row.put("event", event);
        row.put("subject", detail);
        return row;
    }
}
