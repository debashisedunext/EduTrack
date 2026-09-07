package com.edunext.edutrack.api.feature.onboarding.reports;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's journey-counted funnel. The prototype's first tab —
 * "Clients at each journey step. A healthy funnel drains left to right."
 *
 * <h2>Journey-counted, which is the plan's word and not an incidental one</h2>
 *
 * <p>§10 says "journey-counted funnel (per product)", and the distinction it is
 * drawing is against a client-counted one. A client who bought three products
 * has three journeys, at three different steps, and counting clients would put
 * them at whichever step happened to be read first — or at three steps at once,
 * which is the same arithmetic failure B-121 had to declare on the dashboard's
 * client cards. A journey sits at exactly one step, so this figure is exact and
 * needs no {@code countIsUpperBound} caveat.
 *
 * <h2>Per product, in rows rather than only in the filter</h2>
 *
 * <p>Two products' templates have different steps with different names, so a
 * funnel that mixed them would be adding "Data migration" to "Device
 * provisioning" and drawing a bar. The product is therefore a column and the
 * rows are ordered by product then sequence — selecting one product narrows to
 * its block, and selecting none gives every block in turn rather than a
 * meaningless total.
 */
@Component
class JourneyFunnelRunner implements ObReportRunner {

    static final String KEY = "journey-funnel";

    private final ObReportRepository repository;

    JourneyFunnelRunner(ObReportRepository repository) {
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
                new ObReportDtos.Column("stepNo", "Step", NUMBER),
                new ObReportDtos.Column("service", "Service", STRING),
                new ObReportDtos.Column("journeys", "Journeys here", NUMBER),
                // Named for what it means rather than for the column it comes
                // from. "Locked" is plan §5.3's gate state and would read on a
                // funnel as though the step were locked; what is locked is the
                // journey, and what it is waiting for is the client.
                new ObReportDtos.Column("locked", "Of which awaiting prerequisites", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.FunnelRow row : repository.funnel(
                scope, from, to, filters.productId())) {

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("product", row.product());
            out.put("stepNo", row.stepNo());
            out.put("service", row.service());
            out.put("journeys", row.journeys());
            out.put("locked", row.locked());
            rows.add(out);
        }
        return new Result(columns, rows);
    }
}
