package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-066 · §7.8 report 3, the Effort Summary — "effort by resource × project ×
 * task type; pivot-style with drill-down to individual logs".
 *
 * <p>Delivered as the long form — one row per combination — rather than as a
 * pivot. A pivot needs a fixed column set, and the columns here are task types,
 * which an Admin adds at will (B-020): the table would silently change width
 * when somebody created a type, and a spreadsheet exported last month would no
 * longer line up with this month's. The long form is also what a reader pivots
 * themselves in Excel in two clicks, which is where they want it anyway.
 *
 * <p>The drill-down §7.8 asks for is the ticket count on each row: it says how
 * many tickets those hours were spread over, which is the question somebody
 * actually asks of a large number.
 */
@Component
class EffortSummaryRunner implements ReportRunner {

    static final String KEY = "effort-summary";

    private final TicketReportRepository tickets;

    EffortSummaryRunner(TicketReportRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      ReportFilters filters) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("resource", "Resource", STRING),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("taskType", "Task type", STRING),
                new ReportDtos.Column("hours", "Effort", DURATION),
                new ReportDtos.Column("tickets", "Tickets", NUMBER),
                new ReportDtos.Column("entries", "Log entries", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.EffortRow r : tickets.effortSummary(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(),
                // B-060 · the declared TASK_TYPE control, which reached the
                // repository as null from A-066 until ReportFilters existed.
                scope.resourceSubject(null), filters.taskTypeId())) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("project", r.projectName());
            row.put("taskType", r.taskType());
            row.put("hours", r.hours().setScale(1, RoundingMode.HALF_UP));
            row.put("tickets", r.tickets());
            row.put("entries", r.entries());
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
