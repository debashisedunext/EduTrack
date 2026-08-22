package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * B-063 · blueprint §21's timesheet, made stage-aware.
 *
 * <h2>Why this lives in {@code feature/reports}</h2>
 *
 * <p>Two reasons, and the first is a security one. "Whose timesheet may I open"
 * is not a new question — it is blueprint §2's <em>View team member history</em>
 * row, already written down once as
 * {@link Profile360Repository#isVisibleTo(long, long, String)}: Admin anyone, PM
 * their reportees <em>or</em> their projects, Support their projects, the three
 * delivery roles nobody but themselves. That method is package-private, so
 * building this in {@code feature/masters} would have meant a second copy of a
 * row-level access rule — and B-062 has just spent a whole task removing the
 * third copy of a security control from this codebase, for exactly the reason
 * that arrangement fails: fixing one leaves the others live and nothing says so.
 *
 * <p>The second is the ordinary one {@link Profile360Service} gives for itself:
 * the package is where the reporting arithmetic already is, and Spring maps
 * URLs rather than packages. This is Stream A's directory, touched under the M6
 * split B-060, B-061 and B-062 were written under, and flagged rather than done
 * quietly.
 *
 * <h2>What "stage-aware" changes</h2>
 *
 * <p>The row key is ticket <em>and</em> stage <em>and</em> iteration
 * <em>and</em> cycle, not ticket alone. A second pass through QA is a different
 * line from the first, which is the difference between a week that says "six
 * hours on CRM-26-00347" and one that says four of them were rework. All four
 * are needed for the reason PLAN.md §3.4 gives for the roll-up query: without
 * {@code cycleNo} a reopened ticket re-entering the same stage at the same
 * iteration folds cycle 1's hours into cycle 2's line.
 *
 * <h2>Capacity is the working calendar's answer, never seven times eight</h2>
 *
 * <p>Every day's {@code capacityHours} comes from {@link WorkingHoursService},
 * which is the whole of CLAUDE.md's "never write your own date maths": weekly
 * offs, org holidays and this resource's own approved leave are already in it,
 * and a half day is already half. A private implementation here would be the
 * fourth answer to a question the project has deliberately kept to one.
 *
 * <p><b>No {@code projectId} is passed.</b> A week spans projects and project
 * holidays are per project — asking for one person's availability through a
 * single project's calendar would produce a figure belonging to neither.
 * Org-wide plus their own leave is the honest answer at this grain; a
 * per-project timesheet, if it is ever wanted, can pass one.
 *
 * <h2>The approval step, since B-065</h2>
 *
 * <p>§21's "an approval step for the manager" is {@link TimesheetApprovalService},
 * a separate write path over its own table ({@code timesheet_approvals},
 * V20260822_1200) — {@code ticket_effort_logs} is append-only and hash-chained,
 * so an approval flag could never have lived on it. This class only reads the
 * answer: {@link #week} looks the current week up in {@link TimesheetApprovalRepository}
 * and carries it as {@code approval}, null until somebody reviews it.
 */
@Service
class TimesheetService {

    private final TimesheetRepository repository;
    private final Profile360Repository people;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final TimesheetApprovalRepository approvals;

    TimesheetService(TimesheetRepository repository, Profile360Repository people,
                     WorkingHoursService workingHours, WorkingCalendarRepository calendars,
                     TimesheetApprovalRepository approvals) {
        this.repository = repository;
        this.people = people;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.approvals = approvals;
    }

    /**
     * The week, or empty when the subject does not exist <b>or</b> the caller
     * may not see them.
     *
     * <p>One answer for both, which the controller reports as a 404 — the same
     * choice {@link Profile360Service} documents, for the same reason:
     * distinguishing them lets anyone walk user ids and learn who exists. A
     * timesheet is if anything the more sensitive of the two.
     */
    @Transactional(readOnly = true)
    Optional<TimesheetDtos.Timesheet> week(CallerIdentity caller, long userId, LocalDate weekOf) {

        if (!people.isVisibleTo(userId, caller.userId(), caller.roleCode())) {
            return Optional.empty();
        }

        Optional<Profile360Repository.Subject> subject = people.subject(userId);
        if (subject.isEmpty()) {
            return Optional.empty();
        }

        ZoneId zone = calendars.getCalendar().zone();
        LocalDate weekStart = mondayOf(weekOf != null ? weekOf : LocalDate.now(zone));
        LocalDate weekEnd = weekStart.plusDays(6);

        List<TimesheetRepository.Entry> entries = repository.entries(userId, weekStart, weekEnd);
        List<TimesheetDtos.Row> rows = pivot(entries);
        List<TimesheetDtos.Day> days = days(userId, weekStart, zone, entries);

        BigDecimal logged = days.stream()
                .map(TimesheetDtos.Day::loggedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capacity = days.stream()
                .map(TimesheetDtos.Day::capacityHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Profile360Repository.Subject s = subject.get();

        TimesheetDtos.TimesheetApproval approval = approvals.find(userId, weekStart)
                .map(a -> new TimesheetDtos.TimesheetApproval(
                        a.weekStart().toString(), a.approvedBy(), a.approvedAt(), a.note()))
                .orElse(null);

        return Optional.of(new TimesheetDtos.Timesheet(
                new TimesheetDtos.UserRef(s.id(), s.fullName(), s.roleCode(), s.username()),
                weekStart.toString(),
                weekEnd.toString(),
                days,
                rows,
                scaled(logged),
                scaled(capacity),
                utilisation(logged, capacity),
                approval));
    }

    /**
     * The Monday of the ISO week containing {@code date}.
     *
     * <p>Resolving <em>any</em> date to its Monday is what lets a client ask
     * "the week containing this" and never compute a boundary itself. A client
     * that had to would eventually compute a different one from the server, and
     * the screen would page through weeks that did not line up with its totals.
     *
     * <p>Package-private rather than {@code private}: {@link TimesheetApprovalService}
     * (B-065) resolves {@code weekOf} to the identical Monday before looking up
     * or writing an approval, and a second implementation of this one-line rule
     * is exactly the drift CLAUDE.md's "never write your own date maths" warns
     * about — small enough to look harmless, and the first place the two would
     * disagree is a week boundary nobody would think to test.
     */
    static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * The seven columns, whatever was logged.
     *
     * <p>Always seven, including the empty ones. A week that dropped its blank
     * days would render as a grid whose columns move week to week, and a
     * Tuesday holiday is precisely what a reader is looking for when a total
     * comes out thin.
     */
    private List<TimesheetDtos.Day> days(long userId, LocalDate weekStart, ZoneId zone,
                                         List<TimesheetRepository.Entry> entries) {

        Map<LocalDate, BigDecimal> loggedByDate = new LinkedHashMap<>();
        for (TimesheetRepository.Entry e : entries) {
            loggedByDate.merge(e.workDate(), e.hours(), BigDecimal::add);
        }

        List<TimesheetDtos.Day> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            BigDecimal capacity = capacityOn(userId, date, zone);
            days.add(new TimesheetDtos.Day(
                    date.toString(),
                    capacity,
                    scaled(loggedByDate.getOrDefault(date, BigDecimal.ZERO)),
                    capacity.signum() > 0));
        }
        return days;
    }

    /**
     * What the calendar makes available to this resource on this date.
     *
     * <p>The window is the local calendar day in the calendar's own zone, not a
     * UTC one: an org on {@code Asia/Kolkata} asked about a UTC day is being
     * asked about five and a half hours of the day before, and Monday would
     * quietly borrow capacity from Sunday.
     *
     * <p>Seven calls rather than one across the week, deliberately — the
     * per-day figure <em>is</em> the column header, and a week total that could
     * not be broken down would leave the grid unable to grey a holiday.
     */
    private BigDecimal capacityOn(long userId, LocalDate date, ZoneId zone) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant();
        return workingHours.workingHoursBetween(dayStart, nextDayStart, null, userId);
    }

    /**
     * Entries into grid lines, keyed by ticket, stage, iteration and cycle.
     *
     * <p>Keyed on the ticket's numeric id, which never leaves this method: the
     * row carries the code, because that is the ticket's name everywhere else
     * in the API, and the id is only ever the thing rows are grouped by.
     *
     * <p>Busiest first, then by ticket code, so a week reads top-down as where
     * the time actually went and two weeks of the same shape order the same
     * way. The date map is a {@link TreeMap} rather than a hash so the JSON
     * comes out in day order — a payload whose keys shuffle between requests is
     * a diff nobody can read.
     */
    private static List<TimesheetDtos.Row> pivot(List<TimesheetRepository.Entry> entries) {
        record Key(long ticketId, String stageCode, int iterationNo, int cycleNo) {
        }

        Map<Key, TimesheetDtos.Row> rows = new LinkedHashMap<>();
        for (TimesheetRepository.Entry e : entries) {
            Key key = new Key(e.ticketId(), e.stageCode(), e.iterationNo(), e.cycleNo());
            TimesheetDtos.Row existing = rows.get(key);

            Map<String, BigDecimal> hours = existing != null ? existing.hours() : new TreeMap<>();
            hours.merge(e.workDate().toString(), e.hours(), BigDecimal::add);

            BigDecimal total = existing != null ? existing.totalHours().add(e.hours()) : e.hours();

            rows.put(key, new TimesheetDtos.Row(
                    e.ticketCode(), e.ticketTitle(),
                    new TimesheetDtos.ProjectRef(e.projectId(), e.projectCode(), e.projectName()),
                    e.stageCode(), e.stageName(), e.iterationNo(), e.cycleNo(),
                    hours, total,
                    e.correction() || (existing != null && existing.hasCorrection())));
        }

        return rows.values().stream()
                .map(TimesheetService::scaleRow)
                .sorted(Comparator.comparing(TimesheetDtos.Row::totalHours).reversed()
                        .thenComparing(TimesheetDtos.Row::ticketId)
                        .thenComparing(r -> r.stage() == null ? "" : r.stage())
                        .thenComparing(TimesheetDtos.Row::iterationNo))
                .toList();
    }

    private static TimesheetDtos.Row scaleRow(TimesheetDtos.Row row) {
        Map<String, BigDecimal> hours = new TreeMap<>();
        row.hours().forEach((date, value) -> hours.put(date, scaled(value)));
        return new TimesheetDtos.Row(row.ticketId(), row.ticketTitle(),
                row.project(), row.stage(), row.stageName(), row.iterationNo(), row.cycleNo(),
                hours, scaled(row.totalHours()), row.hasCorrection());
    }

    /**
     * Null when the week offered no working hours at all, rather than 0% or a
     * division by zero — the same "null rather than zero" line
     * {@code ResourceScorecardRunner} draws, for the same reason: 0% is a claim
     * about how somebody spent a week, and a week nobody was expected to work
     * makes no claim.
     */
    private static BigDecimal utilisation(BigDecimal logged, BigDecimal capacity) {
        if (capacity.signum() <= 0) {
            return null;
        }
        return logged.multiply(BigDecimal.valueOf(100))
                .divide(capacity, 1, RoundingMode.HALF_UP);
    }

    /** Two places, matching {@code ticket_effort_logs.hours}' own {@code DECIMAL(5,2)}. */
    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
