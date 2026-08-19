package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-067 · §7.8 report 9, the Aging Report — how long open work has been open.
 *
 * <h2>A snapshot, and the date range picks which one</h2>
 *
 * <p>Aging is stock. "How long has this been open" has exactly one answer at a
 * time, so the range selects the day to read rather than a span to add up.
 * Summing thirty days of bucket counts would count the same ticket thirty times
 * and still draw a chart that looked entirely reasonable.
 *
 * <p>The day is stamped in its own column rather than assumed to be today.
 * A-051 recomputes every five minutes but a project with no activity earns no
 * row, so the latest snapshot for a quiet project can be days old — and a
 * reader comparing two rows needs to know they are not from the same morning.
 *
 * <h2>The bucket edges are A-050's, repeated rather than corrected</h2>
 *
 * <p>0–2, 3–7, 8–30, 31+, exactly as the dashboard's widget 12 draws them.
 * §S-05 specifies 0–2 / 3–5 / 6–10 / >10 for its own chart and A-062 already
 * chose to keep A-050's edges over it: two charts sharing four labels but not
 * their boundaries produce figures that never reconcile and no way to see why.
 */
@Component
class AgingReportRunner implements ReportRunner {

    static final String KEY = "aging";

    private final ReportRepository summaries;

    AgingReportRunner(ReportRepository summaries) {
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
                new ReportDtos.Column("asOf", "As at", DATE),
                new ReportDtos.Column("bucket0to2", "0–2 days", NUMBER),
                new ReportDtos.Column("bucket3to7", "3–7 days", NUMBER),
                new ReportDtos.Column("bucket8to30", "8–30 days", NUMBER),
                new ReportDtos.Column("bucket31Plus", "31+ days", NUMBER),
                new ReportDtos.Column("openTotal", "Open", NUMBER),
                new ReportDtos.Column("staleShare", "Over 30 days", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportRepository.AgingRow r : summaries.aging(from, to, projectIds)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", r.projectName());
            row.put("asOf", r.asOf().toString());
            row.put("bucket0to2", r.bucket0to2());
            row.put("bucket3to7", r.bucket3to7());
            row.put("bucket8to30", r.bucket8to30());
            row.put("bucket31Plus", r.bucket31Plus());
            row.put("openTotal", r.openTotal());
            // The one derived column, and the reason to open this report: a
            // project with 400 open tickets and one with 40 are not comparable
            // by count, and the share over thirty days is what makes them so.
            row.put("staleShare", ResourceScorecardRunner.percent(r.bucket31Plus(), r.openTotal()));
            rows.add(row);
        }

        return new Result(columns, rows, summaries.computedAt(from, to, projectIds).orElse(null));
    }
}
