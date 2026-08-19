package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-066 · §7.8 report 1, the Resource Performance Scorecard.
 *
 * <p>Ten columns: assigned, closed, closed on time, SLA %, average cycle time,
 * total effort, estimated-versus-actual variance, reopen rate, utilisation %,
 * and a trend against the preceding window.
 *
 * <h2>Utilisation is measured against the working calendar, not a flat day</h2>
 *
 * <p>The tempting version divides effort by {@code days × 8}. It is wrong in
 * every direction that matters and wrong invisibly: a person who took a week's
 * leave shows as half-utilised, a month containing Diwali reads as
 * over-committed for everyone, and a Saturday deployment pushes somebody past
 * 100% with no explanation on the row.
 *
 * <p>{@link WorkingHoursService} already answers "how many working hours are
 * there between these two instants for this person", accounting for the
 * organisation's weekly-off pattern (B-023), its holidays, and that individual's
 * approved leave. Using it means utilisation is a figure somebody can be shown
 * in a review without having to caveat it.
 *
 * <h2>The trend is against the window before this one, of the same length</h2>
 *
 * <p>Not against a fixed month, and not against all time. A user comparing a
 * fortnight wants the previous fortnight; anything else answers a question they
 * did not ask, and a trend arrow that means something different depending on the
 * range is worse than no arrow.
 */
@Component
class ResourceScorecardRunner implements ReportRunner {

    static final String KEY = "resource-scorecard";

    private final TicketReportRepository tickets;
    private final WorkingHoursService workingHours;

    ResourceScorecardRunner(TicketReportRepository tickets, WorkingHoursService workingHours) {
        this.tickets = tickets;
        this.workingHours = workingHours;
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
                new ReportDtos.Column("openNow", "Assigned now", NUMBER),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("onTime", "On time", NUMBER),
                new ReportDtos.Column("slaPct", "SLA %", PERCENT),
                new ReportDtos.Column("avgCycleHours", "Avg cycle", DURATION),
                new ReportDtos.Column("effortHours", "Effort", DURATION),
                new ReportDtos.Column("variance", "Est vs actual", DURATION),
                new ReportDtos.Column("reopenRate", "Reopen rate", PERCENT),
                new ReportDtos.Column("utilisation", "Utilisation", PERCENT),
                new ReportDtos.Column("trend", "Closed vs previous", NUMBER));

        Long subject = scope.resourceSubject(null);
        List<TicketReportRepository.ScorecardRow> current =
                tickets.scorecard(from, to, projectIds, scope.ownWorkOnly(), scope.userId(), subject);

        // The preceding window of the same length, for the trend column. One
        // extra query rather than a second pass over a wider range: widening the
        // range would change every other figure on the row.
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        Map<Long, Long> previousClosed = new LinkedHashMap<>();
        for (TicketReportRepository.ScorecardRow row :
                tickets.scorecard(from.minusDays(days), from.minusDays(1), projectIds,
                        scope.ownWorkOnly(), scope.userId(), subject)) {
            previousClosed.put(row.userId(), row.closed());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.ScorecardRow r : current) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("openNow", r.assignedNow());
            row.put("closed", r.closed());
            row.put("onTime", r.onTime());
            // Against tickets that carried a commitment, not against everything
            // closed. A ticket with no planned close date can neither meet nor
            // breach an SLA, and counting it as met would inflate the figure for
            // whoever happens to work on unplanned tickets — A-057 made the same
            // split for the gauge, which is why it has two columns.
            row.put("slaPct", percent(r.onTime(), r.committed()));
            row.put("avgCycleHours", round(r.avgCycleHours()));
            row.put("effortHours", round(r.actualHours()));
            // Signed: positive means it took longer than estimated. Reported as
            // hours rather than a percentage because an estimate of zero is
            // common on small tickets and would make the percentage infinite.
            row.put("variance", round(r.actualHours().subtract(r.estimatedHours())));
            row.put("reopenRate", percent(r.reopened(), r.closed()));
            row.put("utilisation", utilisation(r, from, to));
            row.put("trend", r.closed() - previousClosed.getOrDefault(r.userId(), 0L));
            rows.add(row);
        }

        // No asOf: this reads tickets rather than a summary table, so there is
        // no computed_at to validate against. ReportService therefore issues no
        // ETag, which is correct — the answer can change with the next write.
        return new Result(columns, rows, null);
    }

    /**
     * Effort logged as a percentage of the working hours that existed for that
     * person over the window.
     *
     * <p>Null rather than zero when the window contains no working hours at all
     * — a range covering only a weekend, or somebody on leave throughout. Zero
     * would read as "did nothing", which is a different statement from "there
     * was no time in which to do it".
     */
    private Object utilisation(TicketReportRepository.ScorecardRow row, LocalDate from, LocalDate to) {
        BigDecimal available = workingHours.workingHoursBetween(
                from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC),
                null,
                row.userId());

        if (available == null || available.signum() == 0) {
            return null;
        }
        return row.actualHours()
                .multiply(BigDecimal.valueOf(100))
                .divide(available, 1, RoundingMode.HALF_UP);
    }

    /** Null rather than 0% when the denominator is zero — see {@link #utilisation}. */
    static Object percent(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    static Object round(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP);
    }
}
