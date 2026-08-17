package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;

/**
 * A-063 · the reference report: §7.8's <b>Date-wise Report</b> — "Created vs
 * Closed vs Reopened per day, with net backlog line".
 *
 * <h2>Why this one is implemented in the hub's task</h2>
 *
 * <p>A hub with no working report cannot be verified against a real backend,
 * which is the first line of the definition of done, and cannot be shown to
 * anybody. This one earns the slot because it needs <b>no new schema</b>: it
 * reads {@code daily_ticket_stats}, which A-050 built and A-051 has been
 * filling every five minutes since, so it returns real numbers on the day it
 * lands and exercises the whole path — catalogue, scope, runner, ETag, columns,
 * chart and table — with nothing stubbed.
 *
 * <p>It belongs to <b>A-067</b>, which still owns the other five reports in its
 * group. Implementing it here does not complete that task and the commit says
 * so in its body rather than its subject.
 *
 * <h2>Net backlog is a column, not a subtraction the client makes</h2>
 *
 * <p>§7.8 asks for a "net backlog line". Two readings: cumulative
 * {@code created - closed}, or the open stock A-051 already records. This uses
 * <b>the recorded stock</b>, because a cumulative sum over the window is only
 * correct if the window starts at the beginning of time — start it on the 1st
 * of the month and the line begins at zero, implying an empty backlog that was
 * never empty. The recorded value is what was actually true at the end of each
 * day, whatever the window.
 *
 * <p>It is a column rather than something the chart derives, so the table and
 * the chart cannot disagree and so A-064 exports the same number the user saw.
 */
@Component
class DateWiseReportRunner implements ReportRunner {

    static final String KEY = "date-wise";

    private final ReportRepository repository;

    DateWiseReportRunner(ReportRepository repository) {
        this.repository = repository;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("date", "Date", DATE),
                new ReportDtos.Column("created", "Created", NUMBER),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("reopened", "Reopened", NUMBER),
                new ReportDtos.Column("openTotal", "Net backlog", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportRepository.DayRow day : repository.dailyFlow(from, to, projectIds)) {
            // LinkedHashMap so the JSON object's key order matches the column
            // order. Not load-bearing — the client renders by column key — but
            // a response whose fields arrive in a different order than the
            // header it belongs to is needlessly confusing to read in a
            // network tab, which is where this gets debugged.
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.date().toString());
            row.put("created", day.created());
            row.put("closed", day.closed());
            row.put("reopened", day.reopened());
            row.put("openTotal", day.openTotal());
            rows.add(row);
        }

        return new Result(columns, rows, repository.computedAt(from, to, projectIds).orElse(null));
    }
}
