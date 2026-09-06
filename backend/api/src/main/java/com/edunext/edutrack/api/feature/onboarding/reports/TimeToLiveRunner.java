package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's "time-to-live trend per product". The prototype's third
 * tab — "Average time to live. Trending down 6 months straight."
 *
 * <h2>Working hours, not working days, and that is a change from the prototype</h2>
 *
 * <p>The prototype's axis is working <em>days</em> and this reports working
 * <em>hours</em>. The reason is not pedantry: converting hours to days needs
 * the length of a working day, {@code WorkingCalendar} owns that and varies it
 * by org configuration, and dividing by a hardcoded eight here would be a
 * second calendar constant sitting next to the real one. The first org with a
 * seven-hour day would get a trend line that is wrong by an eighth and looks
 * entirely plausible.
 *
 * <p>The contract already settles which unit this surface speaks:
 * {@code duration} is working hours throughout the module, "already through the
 * calendar", and a client must not re-derive it. A line chart trends
 * identically in either unit, so nothing is lost but the axis label — and the
 * axis label is now true.
 *
 * <h2>Measured from when the journey was raised, including the gate wait</h2>
 *
 * <p>{@code created_at} to {@code completed_at}, not {@code started_at} to
 * {@code completed_at}. A journey is created LOCKED and starts when its
 * prerequisites clear (plan §5.3), so measuring from {@code started_at} would
 * quietly exclude however long the client took to send their documents — and
 * "how long did boarding take" is asked by somebody who counts from signing.
 *
 * <p>Whether that wait was ours or theirs is a real question and it is
 * {@code stuck-and-aging}'s, which carries the attribution column for exactly
 * this reason. Splitting it here would make a trend line that answers two
 * questions and neither cleanly.
 *
 * <h2>Averaged in Java over per-journey durations</h2>
 *
 * <p>An {@code AVG(TIMESTAMPDIFF(…))} in MySQL would be one round trip and a
 * calendar-blind duration — weekends and holidays included, which for a
 * multi-week boarding is most of the difference. So the query returns the
 * endpoints and the average is taken over calendar-aware values. The cost note
 * on {@link StuckAndAgingRunner} applies here too: one calendar call per
 * completed journey in the window.
 */
@Component
class TimeToLiveRunner implements ObReportRunner {

    static final String KEY = "time-to-live";

    private final ObReportRepository repository;
    private final WorkingHoursService workingHours;

    TimeToLiveRunner(ObReportRepository repository, WorkingHoursService workingHours) {
        this.repository = repository;
        this.workingHours = workingHours;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                      Long ownerSubject, ObReportFilters filters) {

        List<ObReportDtos.Column> columns = List.of(
                new ObReportDtos.Column("month", "Month", STRING),
                new ObReportDtos.Column("product", "Product", STRING),
                new ObReportDtos.Column("wentLive", "Went live", NUMBER),
                new ObReportDtos.Column("averageTimeToLive", "Average time to live", DURATION));

        // Keyed by month then product so the rows come out chronologically,
        // which is what a trend line needs and what a HashMap would not give.
        Map<String, Map<String, Accumulator>> byMonth = new TreeMap<>();

        for (ObReportRepository.CompletedJourney journey : repository.completedJourneys(
                scope, from, to, filters.productId())) {

            byMonth.computeIfAbsent(journey.month(), m -> new TreeMap<>())
                    .computeIfAbsent(journey.product(), p -> new Accumulator())
                    .add(workingHours.workingHoursBetween(journey.raisedAt(), journey.completedAt()));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byMonth.forEach((month, products) -> products.forEach((product, accumulator) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month", month);
            out.put("product", product);
            out.put("wentLive", accumulator.count);
            out.put("averageTimeToLive", accumulator.average());
            rows.add(out);
        }));

        return new Result(columns, rows);
    }

    /**
     * A running total for one (month, product) cell.
     *
     * <p>Two decimal places, matching {@code WorkingHoursService}'s own scale —
     * rounding the average to whole hours would make a six-month trend of
     * one-hour improvements look flat.
     */
    private static final class Accumulator {

        private BigDecimal total = BigDecimal.ZERO;
        private long count;

        void add(BigDecimal hours) {
            total = total.add(hours);
            count++;
        }

        BigDecimal average() {
            return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
    }
}
