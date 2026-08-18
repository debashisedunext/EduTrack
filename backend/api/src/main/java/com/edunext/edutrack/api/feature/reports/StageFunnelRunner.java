package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-067 · §7.8 report 11, the Stage Funnel — "how many tickets sit at each
 * ribbon stage, and where they stop".
 *
 * <h2>Two counts, and the relationship between them is the report</h2>
 *
 * <p><b>Passed through</b> is how many tickets entered the stage during the
 * window. <b>Sitting</b> is how many open tickets are there now. A stage where
 * those two numbers are close is a stage work arrives at and does not leave —
 * which is what "where they stop" means, and is invisible from either count on
 * its own.
 *
 * <p>The retained share makes it comparable between a stage that saw four
 * tickets and one that saw four hundred.
 *
 * <h2>Not from {@code wip_by_stage}</h2>
 *
 * <p>A-050 declared that column and deliberately left it NULL — "a
 * point-in-time column cannot be backfilled" — and A-058, which fills it, has
 * not landed. Every row of it is NULL today, so a funnel reading it would draw
 * an empty chart and present it as data.
 *
 * <p>So this reads {@code tickets.current_stage} for the standing count and
 * {@code ticket_stage_transitions} for the flow. When A-058 does land there
 * will be two sources for one figure, and this comment is where whoever
 * reconciles them should start.
 */
@Component
class StageFunnelRunner implements ReportRunner {

    static final String KEY = "stage-funnel";

    private final TicketReportRepository tickets;

    StageFunnelRunner(TicketReportRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds) {
        List<ReportDtos.Column> columns = List.of(
                new ReportDtos.Column("stage", "Stage", STRING),
                new ReportDtos.Column("passedThrough", "Entered", NUMBER),
                new ReportDtos.Column("sitting", "Sitting there now", NUMBER),
                new ReportDtos.Column("retained", "Still there", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.FunnelRow r : tickets.stageFunnel(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId())) {

            // A stage nothing entered and nothing sits at is noise on a funnel —
            // every workflow template's stages are declared, not all are used.
            if (r.passedThrough() == 0 && r.sitting() == 0) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", r.displayName() == null ? r.stageCode() : r.displayName());
            row.put("passedThrough", r.passedThrough());
            row.put("sitting", r.sitting());
            row.put("retained", ResourceScorecardRunner.percent(r.sitting(), r.passedThrough()));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
