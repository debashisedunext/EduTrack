package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-067 · §7.8 report 12, Stage Cycle Time — "average time per stage, split
 * into active work and idle waiting".
 *
 * <h2>The split is the whole report</h2>
 *
 * <p>Elapsed time comes from {@code ticket_stage_transitions.duration_mins}:
 * how long the ticket sat in the stage, weekends and all. Active time comes
 * from effort logged against that {@code stage_code}. <b>Idle is the
 * remainder</b> — time the ticket spent somewhere with nobody recorded as
 * working on it.
 *
 * <p>That difference is the finding. A stage averaging four days of which three
 * hours were worked is not slow because the work is hard; it is slow because
 * the ticket waited. Reporting only the average would put that stage and a
 * genuinely laborious one side by side with the same number.
 *
 * <h2>Only sealed visits, and why the average would otherwise fall</h2>
 *
 * <p>An unsealed transition is a ticket <em>still</em> in that stage: its
 * duration is not yet a fact. Including it would average a partial stay against
 * completed ones and drag every figure down — worst for the stages where work
 * is piling up right now, which are the ones this report exists to find.
 * Sealing is the single mutation A-008 permits on that table, and it is what
 * makes a row final.
 *
 * <h2>One limitation, stated rather than hidden</h2>
 *
 * <p>Effort is attributed by {@code (ticket_id, stage_code)} because the effort
 * log carries no transition reference. A stage entered twice on a rework loop
 * therefore has its logged hours counted against the stage rather than against
 * a single visit, so the <b>active share on a reworked stage is an upper
 * bound</b>. The idle figure is correspondingly a lower bound — which is the
 * safe direction for a report whose purpose is to find waiting.
 */
@Component
class StageCycleTimeRunner implements ReportRunner {

    static final String KEY = "stage-cycle-time";

    private final TicketReportRepository tickets;

    StageCycleTimeRunner(TicketReportRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("stage", "Stage", STRING),
                new ReportDtos.Column("visits", "Completed visits", NUMBER),
                new ReportDtos.Column("avgElapsedHours", "Avg time in stage", DURATION),
                new ReportDtos.Column("activeHours", "Worked", DURATION),
                new ReportDtos.Column("idleHours", "Waiting", DURATION),
                new ReportDtos.Column("activeShare", "Share worked", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.StageTimeRow r : tickets.stageCycleTime(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId())) {

            BigDecimal elapsed = r.totalElapsedHours() == null ? BigDecimal.ZERO : r.totalElapsedHours();
            BigDecimal active = r.activeHours() == null ? BigDecimal.ZERO : r.activeHours();

            // Floored at zero. Logged effort can exceed elapsed time on a stage
            // entered twice — see the class note — and a negative "waiting"
            // column would read as a bug rather than as the attribution limit
            // it actually is.
            BigDecimal idle = elapsed.subtract(active).max(BigDecimal.ZERO);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", r.stageCode());
            row.put("visits", r.visits());
            row.put("avgElapsedHours", ResourceScorecardRunner.round(r.avgElapsedHours()));
            row.put("activeHours", active.setScale(1, RoundingMode.HALF_UP));
            row.put("idleHours", idle.setScale(1, RoundingMode.HALF_UP));
            row.put("activeShare", share(active, elapsed));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }

    /** Null when nothing elapsed — see {@code percent}'s reasoning on empty denominators. */
    private static Object share(BigDecimal active, BigDecimal elapsed) {
        if (elapsed.signum() == 0) {
            return null;
        }
        // Capped with a scaled literal. BigDecimal.valueOf(100) has scale 0, so
        // a capped value rendered as "100" beside every other row's "20.0" —
        // one column, two formats, for the rows most worth looking at.
        return active.multiply(BigDecimal.valueOf(100))
                .divide(elapsed, 1, RoundingMode.HALF_UP)
                .min(new BigDecimal("100.0"));
    }
}
