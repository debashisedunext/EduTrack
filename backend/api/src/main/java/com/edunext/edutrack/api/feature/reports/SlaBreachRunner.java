package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-066 · §7.8 report 4, Delayed / SLA Breach — "every breach, days overdue,
 * escalation level, reason".
 *
 * <h2>A list, and the only one of the six that names individual tickets</h2>
 *
 * <p>The other five aggregate. This one must not: "our SLA compliance is 87%"
 * is a number to discuss, and "these nineteen tickets breached, this one by
 * eleven days" is a list to act on. Collapsing it to counts would make it the
 * gauge A-057 already draws.
 *
 * <p>Ordered by how far overdue, worst first — the opposite of chronological
 * and deliberately so. A compliance list is read from the top and abandoned
 * partway, so the top must be the part that matters.
 *
 * <h2>Overdue is reported in hours, and keeps counting while a ticket is open</h2>
 *
 * <p>A closed ticket's overdue is measured to its close; an open one to now. So
 * the figure on an open breach grows between two runs of the same report, which
 * is correct — the breach is still happening — and is why this report carries no
 * ETag.
 *
 * <p><b>Not working hours.</b> Every SLA computation in the product uses the
 * working calendar, and this one does not, because "eleven days overdue" is a
 * statement about the client's experience of waiting rather than about the
 * team's capacity to work. A client waiting over a weekend waited over a
 * weekend. The distinction is why the column is labelled plainly.
 */
@Component
class SlaBreachRunner implements ReportRunner {

    static final String KEY = "sla-breach";

    private final TicketReportRepository tickets;

    SlaBreachRunner(TicketReportRepository tickets) {
        this.tickets = tickets;
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
                new ReportDtos.Column("level", "Level", STRING),
                new ReportDtos.Column("assignee", "Assignee", STRING),
                new ReportDtos.Column("status", "Status", STRING),
                new ReportDtos.Column("overdueHours", "Overdue by", DURATION),
                new ReportDtos.Column("escalations", "Escalations", NUMBER),
                new ReportDtos.Column("reason", "Latest remark", STRING));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.BreachRow r : tickets.slaBreaches(
                // B-060 · level and task type were hardcoded null here from
                // A-066 until the runner had somewhere to read them from. This
                // descriptor has declared both controls since it was written,
                // so the viewer drew two filters that changed nothing.
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(),
                filters.level(), filters.taskTypeId())) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket", r.ticketCode());
            row.put("project", r.projectName());
            row.put("level", r.level());
            // Null rather than "(unassigned)" — the table renders a null as an
            // em dash, and an invented label would sort among the names.
            row.put("assignee", r.assignee());
            row.put("status", r.status());
            row.put("overdueHours", r.overdueHours());
            row.put("escalations", r.escalations());
            // Labelled "latest remark" and not "reason", because that is what it
            // is: the most recent human note on the ticket. §7.8 asks for a
            // reason and no column records one — inventing a category here would
            // put a made-up cause on a compliance report.
            row.put("reason", r.reason());
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
