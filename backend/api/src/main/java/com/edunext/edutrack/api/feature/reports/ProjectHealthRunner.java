package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-067 · §7.8 report 8, Project Health — "open/closed, backlog trend,
 * burn-down, critical count, team load".
 *
 * <h2>Flow and stock in one row, which is the thing to get right</h2>
 *
 * <p>Created, closed and reopened are <b>flow</b>: they are summed over the
 * window, and a month's figure is the sum of its days. Open, critical and
 * delayed are <b>stock</b> and are read at the window's last summarised day.
 * Summing stock over thirty days gives a backlog thirty times too large that
 * still moves plausibly — A-050's migration header warns about exactly this,
 * and it is invisible without a second source to check against.
 *
 * <p>The net column is the honest version of §7.8's "backlog trend": closed
 * minus created over the window. Positive means the project ate into its
 * backlog; negative means it grew. A burn-down line proper needs a point per
 * day, which is the date-wise report one card over — this row is the summary
 * somebody scans across ten projects.
 */
@Component
class ProjectHealthRunner implements ReportRunner {

    static final String KEY = "project-health";

    private final ReportRepository summaries;

    ProjectHealthRunner(ReportRepository summaries) {
        this.summaries = summaries;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("openTotal", "Open now", NUMBER),
                new ReportDtos.Column("openCritical", "Critical", NUMBER),
                new ReportDtos.Column("openDelayed", "Delayed", NUMBER),
                new ReportDtos.Column("created", "Raised", NUMBER),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("reopened", "Reopened", NUMBER),
                new ReportDtos.Column("net", "Net change", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportRepository.ProjectHealthRow r : summaries.projectHealth(from, to, projectIds)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", r.projectName());
            row.put("openTotal", r.openTotal());
            row.put("openCritical", r.openCritical());
            row.put("openDelayed", r.openDelayed());
            row.put("created", r.created());
            row.put("closed", r.closed());
            row.put("reopened", r.reopened());
            // Closed minus raised. Positive is a backlog shrinking, which is the
            // direction a reader expects "good" to point in.
            row.put("net", r.closed() - r.created());
            rows.add(row);
        }

        return new Result(columns, rows, summaries.computedAt(from, to, projectIds).orElse(null));
    }
}
