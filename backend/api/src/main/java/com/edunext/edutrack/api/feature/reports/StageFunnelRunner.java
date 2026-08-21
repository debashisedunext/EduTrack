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
 * <h2>Not from {@code wip_by_stage} — and A-058 has now landed, so here is the
 * reconciliation this comment promised</h2>
 *
 * <p>A-050 declared that column and left it NULL against A-058 by name. A-058
 * fills it, so there are now two sources for "how many sit in each stage" and
 * they are <b>deliberately different</b>:
 *
 * <ul>
 *   <li><b>This report reads live</b>, from {@code tickets.current_stage}. A
 *       report is a question asked once and answered now, and §7.8's readers
 *       export it and act on it — a figure five minutes old inside a document
 *       stamped with today's date is worse here than the query cost.</li>
 *   <li><b>Widget 16 reads the summary</b>, because CLAUDE.md forbids a live
 *       {@code COUNT(*)} behind a dashboard and a dashboard repaints on every
 *       filter change.</li>
 * </ul>
 *
 * <p>They can therefore disagree by up to one refresh interval, and the widget
 * carries {@code asOf} so its staleness is visible. That is the intended
 * outcome and not drift to be repaired by pointing both at one table: making
 * this report read the summary would date it, and making the widget read
 * {@code tickets} would put a live aggregate behind ten charts.
 *
 * <p>What they must <em>not</em> disagree about is the definition, and they do
 * not: both count tickets not yet closed, and
 * {@code DailyStatsRepository.refreshWipByStage} names this runner as the
 * reason it excludes closed tickets from the funnel.
 *
 * <p>One difference in kind is worth knowing about. A-058 derives the summary
 * from {@code ticket_stage_transitions} as at the end of each day, so
 * <b>widget 16 can answer for a past day and this report cannot</b> —
 * {@code current_stage} carries no history. A "where was work sitting last
 * Tuesday" report would have to read the transitions the way the worker does.
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
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
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
