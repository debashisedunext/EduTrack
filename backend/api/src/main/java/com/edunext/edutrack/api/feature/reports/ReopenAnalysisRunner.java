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
 * A-066 · §7.8 report 6, Reopen Analysis — "reopen count by resource/project/
 * type — a quality signal".
 *
 * <h2>All three groupings in one table</h2>
 *
 * <p>§7.8's slash could be read as three reports. It is one, grouped by all
 * three at once, because the question people arrive with is "where do reopens
 * cluster" and the answer is usually a combination — one person on one type, or
 * one project's regressions — which three separate reports each average away.
 *
 * <h2>Reopens, not reopened tickets</h2>
 *
 * <p>A ticket reopened three times is three failures to resolve it. Counting
 * {@code is_reopened} would put it in the same bucket as one reopened once,
 * which is precisely the case this report exists to find. Both are shown: the
 * count of events, and the number of tickets they happened to.
 *
 * <p>The rate is against tickets raised in the window, so a small team with two
 * reopens out of five is visible next to a large one with ten out of four
 * hundred. Rows with no reopens are omitted entirely — a quality signal is a
 * list of the places something went wrong, not a roll call of everywhere it did
 * not.
 */
@Component
class ReopenAnalysisRunner implements ReportRunner {

    static final String KEY = "reopen-analysis";

    private final TicketReportRepository tickets;

    ReopenAnalysisRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("resource", "Resource", STRING),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("taskType", "Task type", STRING),
                new ReportDtos.Column("reopens", "Reopens", NUMBER),
                new ReportDtos.Column("reopenedTickets", "Tickets affected", NUMBER),
                new ReportDtos.Column("tickets", "Tickets raised", NUMBER),
                new ReportDtos.Column("reopenRate", "Reopen rate", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.ReopenRow r : tickets.reopenAnalysis(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(),
                // B-061 · was scope.resourceSubject(null) — see ReportRunner.
                resourceSubject)) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("project", r.projectName());
            row.put("taskType", r.taskType());
            row.put("reopens", r.reopens());
            row.put("reopenedTickets", r.reopenedTickets());
            row.put("tickets", r.tickets());
            // Tickets affected over tickets raised, not reopens over raised: a
            // rate above 100% would be arithmetically possible with the latter
            // and would read as a bug rather than as three reopens on one ticket.
            row.put("reopenRate", ResourceScorecardRunner.percent(r.reopenedTickets(), r.tickets()));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
