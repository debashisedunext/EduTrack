package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.NUMBER;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.PERCENT;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-067 · §7.8 report 10, Workload &amp; Capacity — "assigned load per person
 * against their working calendar".
 *
 * <h2>Capacity is hours that exist, not hours somebody imagines</h2>
 *
 * <p>The load half is stock from {@code resource_daily_stats}: what each person
 * is carrying at the latest snapshot. The capacity half comes from
 * {@link WorkingHoursService}, which knows the organisation's weekly-off
 * pattern (B-023), its holidays, and that individual's approved leave.
 *
 * <p>A flat "days × 8" would be wrong in the direction that matters most here.
 * This report exists to answer "who is overloaded", and the person most likely
 * to be is also the one whose leave got cancelled or whose week contained a
 * holiday — precisely where a flat figure misreports. Somebody on leave for
 * half the window should show as carrying a lot against very little capacity,
 * not as comfortably half-utilised.
 *
 * <h2>Tickets per available day, not a percentage</h2>
 *
 * <p>There is no honest way to turn "eleven open tickets" into a percentage of
 * capacity — that would need an estimate per ticket, and the estimate is the
 * thing least reliably filled in. So the ratio is stated in its own units:
 * open tickets per available working day. It is comparable across people and
 * across windows, and it makes no claim it cannot support.
 *
 * <h2>B-061 · the third half: what was promised, against what exists</h2>
 *
 * <p>Load and capacity answer "is this person busy". They do not answer "did we
 * commit them to more than one of them", which is the question an assigner asks
 * before adding a ticket — blueprint §17 item 22, and the figure B-017
 * explicitly could not compute from the Team tab and flagged for this task:
 * <i>"the figure that would actually be a warning is a resource&#39;s total
 * across their projects, and this screen holds one project&#39;s rows"</i>.
 *
 * <p>So three columns come from {@code project_members}: how many projects the
 * person is on, what their stated allocations add up to, and — the one that
 * keeps the second honest — how many of those projects stated one at all.
 *
 * <h2>Why the allocation total is not made to look complete</h2>
 *
 * <p>{@code allocation_pct} is nullable and B-017 fought for that: {@code NULL}
 * means "not stated", {@code 0} means "no capacity committed", and the two are
 * different facts. A sum therefore covers only the memberships that stated a
 * figure, which makes it a <b>floor</b> and not a total — somebody reading 90%
 * across four projects where two stated nothing is not looking at a person with
 * room.
 *
 * <p>Three ways to hide that were available and all of them lie. Treating
 * unstated as 100 is the backfill B-017 refused, and invents a resourcing
 * crisis out of a data-entry gap. Treating it as 0 asserts a commitment nobody
 * made. Suppressing the row entirely withholds the load figures, which are
 * fine. Publishing the count of projects that actually stated one costs a
 * column and lets the reader see the difference — the same call the client
 * report made about naming the figure it does not have.
 */
@Component
class WorkloadCapacityRunner implements ReportRunner {

    static final String KEY = "workload-capacity";

    /** §12.1's working day, used only to turn capacity hours into days for the ratio. */
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(8);

    private final ReportRepository summaries;
    private final WorkingHoursService workingHours;

    WorkloadCapacityRunner(ReportRepository summaries, WorkingHoursService workingHours) {
        this.summaries = summaries;
        this.workingHours = workingHours;
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
                new ReportDtos.Column("assignedOpen", "Open", NUMBER),
                new ReportDtos.Column("assignedInProgress", "In progress", NUMBER),
                new ReportDtos.Column("assignedCritical", "Critical", NUMBER),
                new ReportDtos.Column("assignedDelayed", "Delayed", NUMBER),
                new ReportDtos.Column("capacityHours", "Capacity", DURATION),
                new ReportDtos.Column("perAvailableDay", "Open per working day", NUMBER),
                // B-061 · the three from project_members. `projects` and
                // `allocationStated` are counts and not percentages: reading
                // "120%" next to "stated on 2 of 4" is the difference between a
                // total and a floor, and the row cannot say which without both.
                new ReportDtos.Column("projects", "Projects", NUMBER),
                new ReportDtos.Column("allocationPct", "Allocated", PERCENT),
                new ReportDtos.Column("allocationStated", "Allocation stated on", NUMBER));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportRepository.WorkloadRow r : summaries.workload(
                // B-061 · resourceSubject, not scope.resourceSubject(null). The
                // report declares a RESOURCE filter and the query had no
                // parameter for it, so picking somebody changed nothing.
                from, to, scope.ownWorkOnly(), scope.userId(), resourceSubject, projectIds)) {

            BigDecimal capacity = workingHours.workingHoursBetween(
                    from.atStartOfDay().toInstant(ZoneOffset.UTC),
                    to.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC),
                    null,
                    r.userId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("resource", r.fullName());
            row.put("assignedOpen", r.assignedOpen());
            row.put("assignedInProgress", r.assignedInProgress());
            row.put("assignedCritical", r.assignedCritical());
            row.put("assignedDelayed", r.assignedDelayed());
            row.put("capacityHours", capacity == null ? null : capacity.setScale(1, RoundingMode.HALF_UP));
            row.put("perAvailableDay", perAvailableDay(r.assignedOpen(), capacity));
            row.put("projects", r.projectCount());
            // Passed through as the repository read it: null when no membership
            // stated one, which the table renders as an em dash. Not
            // BigDecimal.ZERO — see the class javadoc.
            row.put("allocationPct", r.allocationPct());
            row.put("allocationStated", r.allocatedCount());
            rows.add(row);
        }

        return new Result(columns, rows, null);
    }

    /**
     * Null when there is no capacity at all — a window of weekends, or somebody
     * on leave throughout.
     *
     * <p>Zero would say "carrying nothing per day", and dividing by zero would
     * say infinity; both are claims about workload. The truth is that the
     * question has no answer for that window, and an em dash says so.
     */
    private static Object perAvailableDay(long open, BigDecimal capacityHours) {
        if (capacityHours == null || capacityHours.signum() == 0) {
            return null;
        }
        BigDecimal days = capacityHours.divide(HOURS_PER_DAY, 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(open).divide(days, 2, RoundingMode.HALF_UP);
    }
}
