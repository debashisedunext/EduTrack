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
 * A-066 · §7.8 report 5, Task Type Analysis — "volume + avg resolution time per
 * type, reveals if e.g. Server Issues eat the team".
 *
 * <h2>Volume and resolution are two different populations, on purpose</h2>
 *
 * <p>Volume counts tickets <b>raised</b> in the window. Average resolution
 * averages those <b>closed</b> in it. Using one population for both is the
 * obvious simplification and it inverts the report's own finding: measure
 * resolution over tickets raised in the window and only the quick ones will have
 * closed in time to be counted, so the type that genuinely eats the team — the
 * one whose tickets take three months — reports the fastest average of all.
 *
 * <p>Both columns are shown side by side so the difference is visible rather
 * than assumed, and "still open" is beside them because that is where the
 * evidence of a slow type actually accumulates.
 */
@Component
class TaskTypeAnalysisRunner implements ReportRunner {

    static final String KEY = "task-type-analysis";

    private final TicketReportRepository tickets;

    TaskTypeAnalysisRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("taskType", "Task type", STRING),
                new ReportDtos.Column("raised", "Raised", NUMBER),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("avgResolutionHours", "Avg resolution", DURATION),
                new ReportDtos.Column("stillOpen", "Still open", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.TaskTypeRow r : tickets.taskTypeAnalysis(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), null)) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskType", r.taskType());
            row.put("raised", r.raised());
            row.put("closed", r.closed());
            // Null when nothing of this type closed in the window. Zero would
            // claim they were resolved instantly, which is the opposite of what
            // an absence of closures means.
            row.put("avgResolutionHours", ResourceScorecardRunner.round(r.avgResolutionHours()));
            row.put("stillOpen", r.stillOpen());
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
