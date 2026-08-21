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
 * A-068 · §7.8 report 13, Rework Analysis — "rework rate by developer, QA
 * rejection rate, first-time-right %".
 *
 * <h2>One table, three of §7.8's questions</h2>
 *
 * <p>§7.8 names three figures and they are three readings of one grouping, not
 * three reports. A row is <i>this person, sending work back from this stage to
 * that one</i>. Read the QA rows and you have the QA rejection rate; read down
 * the person column and you have rework by developer; the pair columns answer
 * the catalogue's "how often the same pair repeats it", which either of the
 * other two readings averages away.
 *
 * <p>{@code ReopenAnalysisRunner} sits one report over doing the same thing for
 * the same reason, and the two are deliberately parallel: reopens are a cycle
 * failing after closure, rework is a stage failing before it.
 *
 * <h2>First-time-right is a separate row set, and is never invented</h2>
 *
 * <p>FTR is per project, not per stage pair, so it cannot share the grouping —
 * see {@code TicketReportRepository.firstTimeRight}. It arrives as its own rows
 * and is appended under a project name with the stage columns blank.
 *
 * <p><b>A project that closed nothing gets null, not 100%.</b>
 * {@code ResourceScorecardRunner.percent} already returns null on a zero
 * denominator and is reused rather than reimplemented, but the reason bears
 * stating here because this report is the one where the wrong answer is
 * flattering: "100% first-time-right" reads as a team that never makes
 * mistakes, and A-057's SLA gauge made the same call in the same words —
 * <i>nothing measured renders as a sentence, never as a needle at 0%</i>.
 *
 * <h2>What this returns today</h2>
 *
 * <p>The bounce rows read {@code ticket_stage_transitions}, which has no rows in
 * a running application — nothing opens a ticket's first hop, so
 * {@code TransitionService.advance} never runs and never writes one. The
 * repository's javadoc sets that out in full.
 *
 * <p>The consequence is deliberately <b>not</b> smoothed over. The FTR rows do
 * return figures, because they read {@code tickets}; so a reader would see
 * "100% first-time-right" beside an empty bounce table and conclude the team is
 * perfect, when the truth is that no backward move has ever been recorded. The
 * two halves come from different tables and only one of them is populated,
 * which is exactly the sort of quiet disagreement A-063's scope-note defect
 * was. So {@code firstTimeRight} is withheld — null rather than 100 — whenever
 * no bounce was observed anywhere in the window, and the column stays present
 * so the absence is visible rather than the row vanishing.
 */
@Component
class ReworkAnalysisRunner implements ReportRunner {

    static final String KEY = "rework-analysis";

    /**
     * The four {@code action_code} values that move a ticket backwards.
     *
     * <p>Mirrors {@code TransitionService.BACKWARD_ACTIONS}, which is
     * package-private in another feature package and stays there — feature
     * packaging is the point of the layout, and a report reaching into the
     * transitions package for a constant is the first step to reaching in for a
     * service. Duplicated deliberately and <b>checked</b>: the set is asserted
     * against the SQL literals in {@code ReworkAnalysisRunnerTest}, so the two
     * cannot drift silently.
     */
    static final List<String> BACKWARD_ACTIONS =
            List.of("REWORK", "VERIFY_FAILED", "DEPLOY_FAILED", "SIGNOFF_REJECTED");

    private final TicketReportRepository tickets;

    ReworkAnalysisRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("resource", "Sent back by", STRING),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("fromStage", "From stage", STRING),
                new ReportDtos.Column("toStage", "Back to", STRING),
                new ReportDtos.Column("bounces", "Times sent back", NUMBER),
                new ReportDtos.Column("ticketsAffected", "Tickets affected", NUMBER),
                new ReportDtos.Column("closed", "Closed in window", NUMBER),
                new ReportDtos.Column("firstTimeRight", "First-time-right", PERCENT));

        List<TicketReportRepository.ReworkRow> bounces = tickets.reworkAnalysis(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), resourceSubject);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.ReworkRow r : bounces) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("project", r.projectName());
            row.put("fromStage", r.fromStage());
            row.put("toStage", r.toStage());
            row.put("bounces", r.bounces());
            row.put("ticketsAffected", r.ticketsAffected());
            // Blank rather than repeated: the closed count and FTR are per
            // project, and putting a project's figure on each of its stage-pair
            // rows is an invitation to sum a number that is already a total.
            row.put("closed", null);
            row.put("firstTimeRight", null);
            rows.add(row);
        }

        // See the class note: with no bounce anywhere in the window we cannot
        // tell "nothing was sent back" from "backward moves are not being
        // recorded", and only one of those makes 100% a true statement.
        boolean reworkObserved = !bounces.isEmpty();

        for (TicketReportRepository.FirstTimeRightRow r : tickets.firstTimeRight(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), resourceSubject)) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", null);
            row.put("project", r.projectName());
            row.put("fromStage", null);
            row.put("toStage", null);
            row.put("bounces", null);
            row.put("ticketsAffected", null);
            row.put("closed", r.closed());
            row.put("firstTimeRight", reworkObserved
                    ? ResourceScorecardRunner.percent(r.firstTimeRight(), r.closed())
                    : null);
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
