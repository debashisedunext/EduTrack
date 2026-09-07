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
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's sales pipeline. The prototype's fourth tab — "Clients
 * boarded per sales person. Live count shown beside each bar."
 *
 * <h2>A pipeline with no money in it</h2>
 *
 * <p>Plan §1.2 removes financial tracking from the module entirely: "no payment
 * tables, no amounts, no collection reports — removed entirely by product
 * decision." So this counts clients and their statuses and nothing else, and
 * the four status columns are what a pipeline report has instead of a value:
 * how many of what somebody brought in actually went live, and how many stalled
 * or were dropped.
 *
 * <p>That makes the DROPPED column the one worth having. A person with twenty
 * boarded and twelve dropped and a person with eight boarded and eight live
 * have the same-height bar on the prototype's chart, and the prototype's chart
 * is one number wide.
 *
 * <h2>The unassigned row is kept</h2>
 *
 * <p>{@code ob_clients.sales_person_id} is nullable, so clients boarded without
 * one group into a single row rather than disappearing. Dropping them would
 * make this report's total disagree with the client list — and a pipeline
 * report that quietly omits unattributed intake is hiding precisely the thing
 * somebody reading it would want to fix.
 */
@Component
class SalesPipelineRunner implements ObReportRunner {

    static final String KEY = "sales-pipeline";

    private static final String UNASSIGNED = "Unassigned";

    private final ObReportRepository repository;

    SalesPipelineRunner(ObReportRepository repository) {
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
                new ObReportDtos.Column("salesPerson", "Sales person", STRING),
                new ObReportDtos.Column("boarded", "Boarded", NUMBER),
                new ObReportDtos.Column("live", "Live", NUMBER),
                new ObReportDtos.Column("onboarding", "Onboarding", NUMBER),
                new ObReportDtos.Column("onHold", "On hold", NUMBER),
                new ObReportDtos.Column("dropped", "Dropped", NUMBER),
                new ObReportDtos.Column("livePct", "Went live", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.PipelineRow row : repository.salesPipeline(scope, from, to)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("salesPerson", row.salesPerson() == null ? UNASSIGNED : row.salesPerson());
            out.put("boarded", row.boarded());
            out.put("live", row.live());
            out.put("onboarding", row.onboarding());
            out.put("onHold", row.onHold());
            out.put("dropped", row.dropped());
            out.put("livePct", percentage(row.live(), row.boarded()));
            rows.add(out);
        }
        return new Result(columns, rows);
    }

    /**
     * The share that went live, or null with nothing boarded.
     *
     * <p>{@code boarded} is a {@code COUNT(*)} over a group, so it cannot
     * actually be zero — the null branch is here because a percentage helper
     * that divides without checking is one refactor away from being called
     * somewhere it can.
     */
    private static BigDecimal percentage(long live, long boarded) {
        if (boarded == 0) {
            return null;
        }
        return BigDecimal.valueOf(live)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(boarded), 0, RoundingMode.HALF_UP);
    }
}
