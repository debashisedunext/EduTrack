package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;

/**
 * A-066 · §7.8 report 2, Resource Velocity — "tickets and effort-hours closed
 * per week, 4-week rolling average, multi-resource comparison line chart".
 *
 * <h2>Two shapes, chosen by whether a resource is selected</h2>
 *
 * <p>§7.8 asks for two things that cannot share a table. A multi-resource
 * comparison needs one column per person, and a rolling average of two different
 * measures needs one column each — do both at once and a team of twelve produces
 * thirty-six columns nobody can read.
 *
 * <p>So the filter decides. With no resource selected it is the comparison:
 * <b>one column per person</b>, tickets closed per week, which is exactly what a
 * line chart draws. With one selected it is that person in detail: closed,
 * effort, and the <b>4-week rolling average</b> of closed.
 *
 * <p>The generic column/row contract makes this free — the viewer and the
 * exporter both render whatever columns arrive, so no client knows there are two
 * shapes.
 *
 * <h2>Read from the summary table, unlike the other five</h2>
 *
 * <p>A-050 records closed and effort per person per day, both of them flow, so
 * summing into weeks is meaningful and correct. Going to {@code tickets} here
 * would be a live aggregate for a figure recomputed every five minutes anyway.
 *
 * <h2>Weeks with no activity are absent, not zero</h2>
 *
 * <p>A person earns a row on days they closed something or logged effort. A
 * missing week is missing data, not a week of nothing — and the chart draws a
 * break rather than a line to the axis, the same distinction A-056's velocity
 * widget makes with {@code connectNulls} off.
 */
@Component
class ResourceVelocityRunner implements ReportRunner {

    static final String KEY = "resource-velocity";

    /** §7.8's figure. Four weeks smooths a fortnight's leave without hiding a quarter's decline. */
    private static final int ROLLING_WEEKS = 4;

    private final TicketReportRepository tickets;

    ResourceVelocityRunner(TicketReportRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
        // B-061 · read, not re-derived — see ResourceScorecardRunner.
        Long subject = resourceSubject;

        List<TicketReportRepository.VelocityRow> raw = tickets.velocity(
                from, to, scope.ownWorkOnly(), scope.userId(), subject, projectIds);

        // Sorted by week, so the rolling average below is computed over
        // consecutive weeks rather than over whatever order the database chose.
        Map<LocalDate, Map<String, TicketReportRepository.VelocityRow>> byWeek = new TreeMap<>();
        Set<String> people = new LinkedHashSet<>();
        for (TicketReportRepository.VelocityRow r : raw) {
            byWeek.computeIfAbsent(r.weekStart(), w -> new LinkedHashMap<>()).put(r.fullName(), r);
            people.add(r.fullName());
        }

        return people.size() == 1
                ? oneResource(byWeek, people.iterator().next())
                : comparison(byWeek, people);
    }

    /** The detail view: closed, effort, and the rolling average §7.8 names. */
    private Result oneResource(Map<LocalDate, Map<String, TicketReportRepository.VelocityRow>> byWeek,
                               String person) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("week", "Week beginning", DATE),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("effortHours", "Effort", DURATION),
                new ReportDtos.Column("rolling", ROLLING_WEEKS + "-week average", NUMBER));

        List<Long> window = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map.Entry<LocalDate, Map<String, TicketReportRepository.VelocityRow>> week : byWeek.entrySet()) {
            TicketReportRepository.VelocityRow r = week.getValue().get(person);
            long closed = r == null ? 0 : r.closed();

            window.add(closed);
            if (window.size() > ROLLING_WEEKS) {
                window.remove(0);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week.getKey().toString());
            row.put("closed", closed);
            row.put("effortHours", r == null ? null : r.effortHours().setScale(1, RoundingMode.HALF_UP));
            // Averaged over the weeks actually seen, not always over four. The
            // alternative — dividing by 4 from the first week — draws a curve
            // that ramps up for a month at the start of every range and reads
            // as somebody getting faster.
            row.put("rolling", BigDecimal.valueOf(
                            window.stream().mapToLong(Long::longValue).sum() / (double) window.size())
                    .setScale(1, RoundingMode.HALF_UP));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }

    /** The comparison: one column per person, which is what the line chart plots. */
    private Result comparison(Map<LocalDate, Map<String, TicketReportRepository.VelocityRow>> byWeek,
                              Set<String> people) {
        List<ReportDtos.Column> columns = new ArrayList<>();
        columns.add(new ReportDtos.Column("week", "Week beginning", DATE));
        for (String person : people) {
            // Keyed by name. Names collide far less often than they are
            // ambiguous, and a column keyed by user id would need the client to
            // resolve it back — which the generic row shape has no way to do.
            columns.add(new ReportDtos.Column(person, person, NUMBER));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, TicketReportRepository.VelocityRow>> week : byWeek.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week.getKey().toString());
            for (String person : people) {
                TicketReportRepository.VelocityRow r = week.getValue().get(person);
                // Null rather than 0 — a week somebody closed nothing and a week
                // they were not there are different claims, and only one of them
                // should draw a point on the axis.
                row.put(person, r == null ? null : r.closed());
            }
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
