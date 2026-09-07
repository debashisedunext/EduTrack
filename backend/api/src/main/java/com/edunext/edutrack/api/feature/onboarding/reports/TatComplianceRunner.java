package com.edunext.edutrack.api.feature.onboarding.reports;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's "TAT compliance by step/owner". The prototype's second tab
 * — "% of steps completed within TAT. Waiting-on-client time excluded, so this
 * measures <i>our</i> speed."
 *
 * <h2>The exclusion the caption promises is not implemented here, on purpose</h2>
 *
 * <p>It is implemented in {@code due_at}. Plan §1.1 pauses the TAT clock while
 * a step waits on the client and attributes the wait to them, and the way that
 * pause becomes visible is the deadline moving out — C-105's clock owns that
 * column. So "on time" is {@code finished_at <= due_at} and the client's time
 * is already out of it.
 *
 * <p>Recomputing it here from {@code ob_step_clock_events} would be a second
 * definition of the same exclusion, in a report, disagreeing with the ribbon
 * and the scanner the first time either side changed. The first TAT dispute
 * would then be about which screen to believe, which is the outcome §1.1 exists
 * to prevent.
 *
 * <h2>Two denominators, and the report shows both</h2>
 *
 * <p>{@code completed} is every step finished in the window. {@code measured}
 * is those that had a {@code due_at} to be judged against. A step with no
 * deadline is not on time and not late — it is unmeasured — and the two ways of
 * hiding that are both worse than a column: dropping the rows makes a
 * percentage over three steps look like one over thirty, and counting them as
 * on time flatters every owner and every service.
 *
 * <p>This is not hypothetical while C-105 is unmerged. {@code due_at} is
 * nullable in A-107's schema and nothing fills it yet, so on today's data
 * {@code measured} is legitimately zero and the on-time column reads "—". That
 * is the report saying it cannot answer, which is the answer.
 *
 * <h2>Worst first</h2>
 *
 * <p>Sorted by on-time percentage ascending, unmeasured rows last. The
 * prototype's caption — "Data migration is the bottleneck" — is the sentence
 * this report exists to produce, and a reader scanning an alphabetical list for
 * the smallest number is doing the sort by hand. Unmeasured rows sort to the
 * bottom rather than to the top with a notional zero, which would put "we have
 * no data" where "we are failing" belongs.
 */
@Component
class TatComplianceRunner implements ObReportRunner {

    static final String KEY = "tat-compliance";

    /** Clients with no sales person, owners with no name — one label, one place. */
    private static final String UNASSIGNED = "Unassigned";

    private final ObReportRepository repository;

    TatComplianceRunner(ObReportRepository repository) {
        this.repository = repository;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                      Long ownerSubject, ObReportFilters filters) {

        List<ObReportDtos.Column> columns = List.of(
                new ObReportDtos.Column("product", "Product", STRING),
                new ObReportDtos.Column("service", "Service", STRING),
                new ObReportDtos.Column("owner", "Owner", STRING),
                new ObReportDtos.Column("completed", "Completed", NUMBER),
                new ObReportDtos.Column("measured", "With a TAT", NUMBER),
                new ObReportDtos.Column("onTime", "On time", NUMBER),
                new ObReportDtos.Column("onTimePct", "On time", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.TatRow row : repository.tatCompliance(
                scope, from, to, filters.productId(), ownerSubject)) {

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("product", row.product());
            out.put("service", row.service());
            out.put("owner", row.owner() == null ? UNASSIGNED : row.owner());
            out.put("completed", row.completed());
            out.put("measured", row.measured());
            out.put("onTime", row.onTime());
            out.put("onTimePct", percentage(row.onTime(), row.measured()));
            rows.add(out);
        }

        rows.sort(worstFirst());
        return new Result(columns, rows);
    }

    /**
     * On-time as a whole percentage, or null when nothing was measurable.
     *
     * <p>Null rather than zero, and the difference is the whole point of the
     * {@code measured} column: 0% is a claim that every measured step was late,
     * and there were none.
     */
    private static BigDecimal percentage(long onTime, long measured) {
        if (measured == 0) {
            return null;
        }
        return BigDecimal.valueOf(onTime)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(measured), 0, RoundingMode.HALF_UP);
    }

    /**
     * Ascending by on-time percentage, unmeasured last, then by product and
     * service so the order is total and a rerun over unchanged data cannot
     * reshuffle equal rows.
     */
    private static Comparator<Map<String, Object>> worstFirst() {
        Comparator<Map<String, Object>> byPct = Comparator.comparing(
                row -> (BigDecimal) row.get("onTimePct"),
                Comparator.nullsLast(Comparator.naturalOrder()));
        return byPct
                .thenComparing(row -> (String) row.get("product"))
                .thenComparing(row -> (String) row.get("service"));
    }
}
