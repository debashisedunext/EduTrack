package com.edunext.edutrack.api.feature.reports;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-068 · §7.8 report 15, Resource Contribution — "the §4A.4 per-resource-
 * per-stage roll-up across any ticket set".
 *
 * <h2>The same roll-up as the ticket detail page, widened</h2>
 *
 * <p>§4A.4 defines the roll-up and C-058 built it for one ticket
 * ({@code JourneyRepository.perResource}). §7.8's phrase "across any ticket set"
 * is the whole of what this report adds: the same grouping, bounded by a date
 * range and a scope rather than by a ticket id. Somebody reading a person's row
 * here and the same person's row on a ticket should see figures that agree,
 * which they will, because both sum the same column of the same table.
 *
 * <h2>Why this one works when its two neighbours are empty</h2>
 *
 * <p>Reports 13 and 14 read {@code ticket_stage_transitions}, which no running
 * application writes to. This one reads {@code ticket_effort_logs}, which
 * {@code EffortLogService.append} and {@code QuickUpdateService.appendEffort}
 * write on every effort entry, and which carries {@code stage_code} and
 * {@code iteration_no} on the row itself. So the per-stage half of "per resource
 * per stage" needs no hop at all.
 *
 * <p>{@code AssignService}'s javadoc is where that independence is spelled out:
 * {@code user_id} is stamped from whoever logged the hours, never from
 * {@code tickets.assigned_to}, so the roll-up stays correct across a
 * reassignment without consulting the ribbon.
 *
 * <h2>Effort with no stage is its own row, not dropped</h2>
 *
 * <p>{@code ticket_effort_logs.stage_code} is nullable — hours can be logged
 * against a ticket that is not standing in a stage, and with nothing opening
 * first hops that is currently the ordinary case rather than the exception.
 * Dropping those rows would make this report's total disagree with the ticket's
 * own effort roll-up and with the effort summary two cards over, for no reason
 * a reader could discover. They are grouped under {@code (no stage)} — the
 * repository's {@code COALESCE} — which is a visible fact rather than a silent
 * subtraction.
 *
 * <h2>Hours per ticket is the column that makes the table readable</h2>
 *
 * <p>Neither §7.8 nor the catalogue asks for it, and it is here because the two
 * figures either side of it are hard to compare without it: forty hours over
 * twenty tickets and forty over two are different contributions, and a reader
 * comparing two people otherwise has to do the division in their head for every
 * row. Null rather than zero when a row somehow carries no ticket, which the
 * {@code DISTINCT} count makes impossible — the guard is there because the
 * division is, not because the case is expected.
 */
@Component
class ResourceContributionRunner implements ReportRunner {

    static final String KEY = "resource-contribution";

    private final TicketReportRepository tickets;

    ResourceContributionRunner(TicketReportRepository tickets) {
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
                new ReportDtos.Column("stage", "Stage", STRING),
                new ReportDtos.Column("hours", "Hours", NUMBER),
                new ReportDtos.Column("tickets", "Tickets", NUMBER),
                new ReportDtos.Column("entries", "Log entries", NUMBER),
                new ReportDtos.Column("hoursPerTicket", "Hours per ticket", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketReportRepository.ContributionRow r : tickets.resourceContribution(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(), resourceSubject)) {

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("project", r.projectName());
            row.put("stage", r.stageCode());
            row.put("hours", ResourceScorecardRunner.round(r.hours()));
            row.put("tickets", r.tickets());
            row.put("entries", r.entries());
            row.put("hoursPerTicket", r.tickets() == 0 || r.hours() == null
                    ? null
                    : ResourceScorecardRunner.round(r.hours().divide(
                            java.math.BigDecimal.valueOf(r.tickets()), 2,
                            java.math.RoundingMode.HALF_UP)));
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }
}
