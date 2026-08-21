package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-068 · §7.8 report 14, the Deployment Report — "deployments per week,
 * success vs rollback, avg deploy duration".
 *
 * <h2>Weekly rows, because the report is a trend</h2>
 *
 * <p>§7.8 asks for deployments <em>per week</em>, and the reason is in the
 * question people bring: whether release cadence and release quality are moving.
 * A single total over the window answers neither. The week is keyed by its
 * Monday, so the label sorts, groups and reads as the same thing.
 *
 * <h2>Success is the default and rollback is the exception</h2>
 *
 * <p>{@code succeeded} and {@code rolledBack} partition {@code deployments} by
 * construction — the repository derives one as "left by {@code DEPLOY_FAILED}"
 * and the other as everything else, over the same sealed visits. That matters
 * because the descriptor draws these as a bar: a stacked pair whose segments do
 * not partition the bar makes an arithmetic claim that is false, which is the
 * defect A-056's widget 10 note describes at length.
 *
 * <p>The rollback <em>rate</em> is the column worth reading, and it is the one
 * §7.8 does not name — four rollbacks is a different fact in a week of five
 * deployments and a week of two hundred. Null, never 0%, when the week saw no
 * deployment at all: {@code ResourceScorecardRunner.percent} withholds on a zero
 * denominator, and "0% rolled back" for a week nothing shipped is a claim of
 * flawless delivery from an idle week.
 *
 * <h2>Duration is working minutes, and is not wall clock</h2>
 *
 * <p>It comes from {@code duration_mins}, which {@code TransitionService} sets
 * on seal through {@code WorkingHoursService} — so a deployment held over a
 * weekend does not report two extra days, per CLAUDE.md's rule that all
 * duration maths uses the working calendar. Typed {@code DURATION} rather than
 * {@code NUMBER} so the client formats it as time and A-064's exporter writes it
 * as time, instead of printing a bare minute count that a reader will take for
 * hours.
 *
 * <h2>What this returns today</h2>
 *
 * <p>It reads {@code ticket_stage_transitions}, which has no rows in a running
 * application — see {@code TicketReportRepository.reworkAnalysis} for why, and
 * why that is a true empty answer rather than a broken one. Unlike rework
 * analysis there is no second half read from a populated table, so there is
 * nothing here that could disagree with itself: the report is empty, uniformly,
 * until a ticket's first hop is written.
 */
@Component
class DeploymentReportRunner implements ReportRunner {

    static final String KEY = "deployment-report";

    private final TicketReportRepository tickets;

    DeploymentReportRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("weekStart", "Week", DATE),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("deployments", "Deployments", NUMBER),
                new ReportDtos.Column("succeeded", "Shipped", NUMBER),
                new ReportDtos.Column("rolledBack", "Rolled back", NUMBER),
                new ReportDtos.Column("rollbackRate", "Rollback rate", PERCENT),
                new ReportDtos.Column("avgDuration", "Avg time in deployment", DURATION));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.DeploymentRow r : tickets.deploymentReport(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId())) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("weekStart", r.weekStart());
            row.put("project", r.projectName());
            row.put("deployments", r.deployments());
            row.put("succeeded", r.succeeded());
            row.put("rolledBack", r.rolledBack());
            row.put("rollbackRate", ResourceScorecardRunner.percent(r.rolledBack(), r.deployments()));
            row.put("avgDuration", ResourceScorecardRunner.round(r.avgMinutes()));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
