package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;
import static com.edunext.edutrack.api.feature.reports.ReportEntityKind.CLIENT;
import static com.edunext.edutrack.api.feature.reports.ResourceScorecardRunner.percent;
import static com.edunext.edutrack.api.feature.reports.ResourceScorecardRunner.round;

/**
 * B-060 · §7.8 report 17, the Client Report — "volume, open versus closed, SLA
 * compliance, avg resolution time and satisfaction per client; drills into the
 * client 360 view".
 *
 * <h2>Satisfaction has no column, because it has no data</h2>
 *
 * <p>Four of the five figures §7.8 names are computable today. The fifth is
 * not: there is no CSAT, rating or feedback column anywhere in this schema, in
 * the contract, or in any of the forty-odd migrations. Blueprint §17 item 19 —
 * <i>"CSAT / feedback on closure — a 1–5 rating drives the client-satisfaction
 * report"</i> — is a <b>phase 2–3</b> item that has not been built, and until
 * a rating is captured on closure there is nothing to average.
 *
 * <p>So the column is absent rather than present-and-empty, and the descriptor
 * says so in a sentence. Three alternatives were available and each is worse:
 *
 * <ul>
 *   <li>A column of em dashes reads as "we asked and they did not answer",
 *       which is a claim about the clients rather than about the schema.</li>
 *   <li>Reopen rate relabelled as satisfaction would put a plausible number
 *       under the word — and reopens measure our own quality, not whether the
 *       client was content with how they were treated.</li>
 *   <li>SLA compliance standing in for it collapses two of §7.8's five figures
 *       into one and would make the report agree with itself by construction.</li>
 * </ul>
 *
 * <p>This is the report §7.8 describes as shaped to be sent to a client. An
 * invented figure costs more here than anywhere else in the product, and the
 * gap is small, nameable and closed by one migration whenever CSAT lands.
 *
 * <h2>Six columns, and why the ones that look redundant are not</h2>
 *
 * <p>{@code raised}, {@code closed} and {@code openNow} do not reconcile, and
 * are not meant to. The first two are <em>flow</em> bounded by the window; the
 * third is <em>stock</em> read at request time and deliberately unbounded,
 * because the backlog a client is unhappy about is mostly older than any window
 * they would think to ask about. {@link TicketReportRepository#clientReport}
 * states the same split from the query's side. A reader who subtracts one from
 * another gets a number that means nothing, which is why the labels are
 * "Raised", "Closed" and "Open now" rather than three variations on "tickets".
 *
 * <h2>The drill-in</h2>
 *
 * <p>§7.8's own sentence ends "drills into the client 360 view", and the client
 * column declares it: {@link ReportEntityKind#CLIENT} plus the row key holding
 * the id. The runner names an <em>entity</em> and never a path — routes belong
 * to the frontend's {@code entityLinks.ts}, and a server emitting
 * {@code /clients/42} would be a second copy of the router that nothing keeps
 * in step.
 *
 * <p>S-32 itself is a {@code ScreenPlaceholder} in {@code App.tsx} today. That
 * is the pre-existing and deliberate arrangement C-019 set up for all three
 * unbuilt 360 screens: a link lands on a named "not built yet" page rather than
 * the catch-all Not found, because a user cannot tell an unbuilt screen from a
 * broken link and only one of the two is worth reporting.
 */
@Component
class ClientReportRunner implements ReportRunner {

    static final String KEY = "client-report";

    private final TicketReportRepository tickets;

    ClientReportRunner(TicketReportRepository tickets) {
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
                // The one linked column on any report so far. `clientId` is the
                // row key it reads the destination from, and is carried in the
                // row without a column of its own — an internal id is not a
                // figure, and A-064's exporter iterates columns, so it stays out
                // of the spreadsheet a client might be sent.
                ReportDtos.Column.linking("client", "Client", STRING, CLIENT, "clientId"),
                new ReportDtos.Column("clientCode", "Code", STRING),
                new ReportDtos.Column("raised", "Raised", NUMBER),
                new ReportDtos.Column("closed", "Closed", NUMBER),
                new ReportDtos.Column("openNow", "Open now", NUMBER),
                new ReportDtos.Column("slaPct", "SLA %", PERCENT),
                new ReportDtos.Column("avgResolutionHours", "Avg resolution", DURATION));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.ClientRow r : tickets.clientReport(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), filters.clientId())) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client", r.clientName());
            row.put("clientId", r.clientId());
            row.put("clientCode", r.clientCode());
            row.put("raised", r.raised());
            row.put("closed", r.closed());
            row.put("openNow", r.openNow());
            // Denominator is tickets that carried a planned close date, so a
            // client whose closed work was never date-committed reads as an em
            // dash rather than as 0% or 100%. `percent` returns null for a zero
            // denominator, which is the whole reason the repository carries both
            // halves instead of dividing in SQL.
            row.put("slaPct", percent(r.slaMet(), r.slaCommitted()));
            row.put("avgResolutionHours", round(r.avgResolutionHours()));
            rows.add(row);
        }

        // No asOf, so ReportService issues no ETag. This reads `tickets` live
        // rather than a summary table — `openNow` in particular changes with the
        // next status write — and there is no computed_at to validate against.
        // Same as sla-breach and the scorecard beside it.
        return new Result(columns, rows, null);
    }
}
