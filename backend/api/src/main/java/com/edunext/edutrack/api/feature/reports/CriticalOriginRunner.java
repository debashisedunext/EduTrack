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
 * A-070 · "born critical versus became critical" — blueprint §6.
 *
 * <p>The blueprint asks for it by name and says why: <i>"Original level is
 * preserved in {@code original_level} so you can always report 'how many were
 * born critical vs became critical' — an insight managers ask for
 * immediately."</i> It is the nineteenth report, and the first that is not one
 * of §7.8's eighteen.
 *
 * <h2>What the split actually tells somebody</h2>
 *
 * <p>This is the reason the report is worth building, and it is not obvious
 * from the two numbers alone: <b>the two halves are statements about different
 * things.</b>
 *
 * <ul>
 *   <li><b>Born critical</b> is demand. It is what clients and colleagues send
 *       you, and a high count is a fact about the work rather than about the
 *       team. You manage it with capacity.</li>
 *   <li><b>Became critical</b> is mostly self-inflicted. §6 raises a ticket to
 *       CRITICAL when its Planned Close Date passes — so a ticket that became
 *       critical usually did so because it was <em>late</em>, not because it
 *       was urgent. You manage it by finishing things.</li>
 * </ul>
 *
 * <p>A team reading only "we have 40 critical tickets" cannot tell those apart,
 * and the two have opposite remedies. Hiring for the second one does not help.
 *
 * <h2>The four quadrants, and the one nobody asks for</h2>
 *
 * <p>{@code original_level} against {@code level} gives four states, and the
 * report shows all of them. <b>De-escalated</b> — arrived critical, is not any
 * more — is the one nobody requests and the one whose absence gets noticed: it
 * is where "we downgraded it" hides, and a column of zeroes is a useful thing
 * to be able to point at.
 *
 * <h2>🔴 Three outcomes for "became", because "no record" is not "a person"</h2>
 *
 * <p>The first version had two — the scanner's escalations, counted, and
 * everything else as a remainder labelled "raised by a person". The arithmetic
 * held and the label was a small lie, which running it against the B-007
 * fixture corpus made plain: 77 of its tickets are critical without having
 * arrived that way and only 7 carry a {@code LEVEL_CHANGED} row, so seventy
 * would have been reported as somebody's decision when nothing recorded one.
 *
 * <p>So both actors are counted from the same subquery — mutually exclusive,
 * since a row has one {@code actor_type} — and <b>"not recorded" is the
 * remainder</b>. The three partition {@code becameCritical} exactly.
 *
 * <p>That third column should read zero in production, and is worth a place for
 * saying so: every real level change is journalled, by
 * {@code PriorityChangeController} as a person or by {@code SlaEscalation} as
 * SYSTEM. A number there means something moved {@code level} without writing
 * history — which is a thing to investigate, not to quietly attribute.
 *
 * <h2>Grouped by project and type, filtered by resource</h2>
 *
 * <p>Resource is a filter and deliberately <b>not</b> a grouping column, unlike
 * {@code ReopenAnalysisRunner} which groups by all three. A reopen is something
 * a person did; an escalation is frequently something that happened <em>to</em>
 * a ticket while nobody was looking — an unassigned ticket breaches exactly as
 * readily as an assigned one. A column of names against automatic escalations
 * reads as a blame list for a number people often do not control, so the filter
 * is there for whoever wants to narrow to one person and the default view does
 * not put anybody's name against it.
 */
@Component
class CriticalOriginRunner implements ReportRunner {

    static final String KEY = "critical-origin";

    private final TicketReportRepository tickets;

    CriticalOriginRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("taskType", "Task type", STRING),
                new ReportDtos.Column("tickets", "Tickets raised", NUMBER),
                new ReportDtos.Column("bornCritical", "Born critical", NUMBER),
                new ReportDtos.Column("becameCritical", "Became critical", NUMBER),
                new ReportDtos.Column("escalatedBySla", "…by SLA breach", NUMBER),
                new ReportDtos.Column("raisedByPerson", "…raised by a person", NUMBER),
                new ReportDtos.Column("unrecorded", "…not recorded", NUMBER),
                new ReportDtos.Column("deEscalated", "De-escalated", NUMBER),
                new ReportDtos.Column("criticalNow", "Critical now", NUMBER),
                new ReportDtos.Column("becameShare", "Share we created", PERCENT));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.CriticalOriginRow r : tickets.criticalOrigin(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(),
                // B-061 · the resolved narrowing, read from the parameter and
                // never re-derived — see ReportRunner's note on the five
                // runners that called scope.resourceSubject(null) and made
                // ?resourceId= a control that changed nothing.
                resourceSubject,
                filters.taskTypeId())) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project", r.projectName());
            row.put("taskType", r.taskType());
            row.put("tickets", r.tickets());
            row.put("bornCritical", r.bornCritical());
            row.put("becameCritical", r.becameCritical());
            row.put("escalatedBySla", r.escalatedBySla());
            row.put("raisedByPerson", r.raisedByPerson());
            // What neither branch claimed: became critical with no
            // LEVEL_CHANGED row at all. Derived rather than counted, so the
            // three always sum to becameCritical — see the class note for why
            // this is a column rather than being folded into the one beside it.
            row.put("unrecorded",
                    r.becameCritical() - r.escalatedBySla() - r.raisedByPerson());
            row.put("deEscalated", r.deEscalated());
            row.put("criticalNow", r.criticalNow());
            // Of what is critical *now*, the share that did not arrive that
            // way. Against criticalNow rather than against tickets raised,
            // because the question is "how much of our critical load did we
            // create", and a percentage of the whole cohort answers a
            // different and less useful one — it moves when quiet work is
            // added, which has nothing to do with escalation.
            //
            // Blank, not zero, when nothing is critical now. Every row here had
            // something critical at some point — the HAVING sees to that — so a
            // row can legitimately arrive with criticalNow = 0 because all of it
            // was de-escalated. percent() returns null for a zero denominator
            // and the table renders that as empty, which is the honest answer:
            // there is no share of nothing, and printing "0%" would read as
            // "we created none of it" about a row that has no critical load at
            // all.
            row.put("becameShare", ResourceScorecardRunner.percent(r.becameCritical(), r.criticalNow()));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
