package com.edunext.edutrack.api.feature.onboarding.reports;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-119 · plan §10's "CSAT summary" — average score and response count, per
 * product, over the go-live survey B-119 also built the capture surface for.
 *
 * <h2>Built now, not held as OB4b</h2>
 *
 * <p>{@code ObReportCatalogue}'s own comment lists this among "five held
 * pending a product decision" — PHASE-2-BUILD-PLAN §3 #8's OB4b group,
 * "straightforward reads over data that will already exist". B-119's backlog
 * line is explicit that CSAT ships with "a public one-question page,
 * storage, <b>and a summary</b>" in one task, so this is that summary rather
 * than a sixth held card — the other four (breach log, escalation log, owner
 * workload, communication audit) are unaffected and stay exactly as
 * {@code ObReportCatalogue} declares them.
 *
 * <h2>The average is arithmetic, not a duration — CLAUDE.md's calendar rule
 * does not reach it</h2>
 *
 * <p>"Every duration and SLA figure... routes through the working-hours
 * service" is about elapsed time between two instants. A CSAT score is a
 * number a client typed, and averaging five of them is ordinary division —
 * there is no calendar to consult and no {@code WorkingHoursService} call
 * that would mean anything here. {@code TatComplianceRunner}'s on-time
 * percentage, one file over, makes the identical distinction for the
 * identical reason.
 *
 * <h2>Null, not zero, for a product nobody has rated</h2>
 *
 * <p>{@code TatComplianceRunner}'s own call for "with a TAT" applies again:
 * zero would claim every response was the worst possible score, and there
 * were none. {@link ObReportRepository#csatSummary} only returns rows that
 * already have at least one response, so this only guards the arithmetic —
 * but the guard stays because a division by a count that can be zero must
 * never be written as though it cannot.
 */
@Component
class CsatSummaryRunner implements ObReportRunner {

    static final String KEY = "csat-summary";

    private final ObReportRepository repository;

    CsatSummaryRunner(ObReportRepository repository) {
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
                new ObReportDtos.Column("responses", "Responses", NUMBER),
                new ObReportDtos.Column("averageScore", "Average score", NUMBER),
                new ObReportDtos.Column("score1", "1", NUMBER),
                new ObReportDtos.Column("score2", "2", NUMBER),
                new ObReportDtos.Column("score3", "3", NUMBER),
                new ObReportDtos.Column("score4", "4", NUMBER),
                new ObReportDtos.Column("score5", "5", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.CsatRow row : repository.csatSummary(scope, from, to, filters.productId())) {

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("product", row.product());
            out.put("responses", row.responses());
            out.put("averageScore", average(row.scoreTotal(), row.responses()));
            out.put("score1", row.score1());
            out.put("score2", row.score2());
            out.put("score3", row.score3());
            out.put("score4", row.score4());
            out.put("score5", row.score5());
            rows.add(out);
        }
        return new Result(columns, rows);
    }

    /** One decimal place — the score is 1-5, and a second decimal would be noise over five responses. */
    private static BigDecimal average(long scoreTotal, long responses) {
        if (responses == 0) {
            return null;
        }
        return BigDecimal.valueOf(scoreTotal)
                .divide(BigDecimal.valueOf(responses), 1, RoundingMode.HALF_UP);
    }
}
