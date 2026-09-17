package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProductRef;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoard;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardCards;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardClientRef;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardRow;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProjectBoardSchedule;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.UserRef;
import com.edunext.edutrack.api.feature.onboarding.projects.ObRunningProject;
import com.edunext.edutrack.api.feature.onboarding.projects.ObRunningProjectReader;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * OB-02's project board — the counters, the schedule split and the rows behind
 * `GET /onboarding/dashboard/project-board`.
 *
 * <h2>One pass over one set of rows, which is the whole point</h2>
 *
 * <p>The card band, the schedule donut and the three Summary lists are three
 * readings of the <b>same</b> projects, computed here in one request from one
 * list. The card board this screen sits above cannot make that promise: its
 * counts come from {@code ob_dashboard_summary} at journey-per-product grain
 * while its slide-over lists steps and prerequisite tasks, and this feature's
 * README opens with the bug that produced — a card reading 0 above a list of
 * 17. Nothing here is pre-aggregated, so nothing here can drift.
 *
 * <h2>Every bucket is the project's own completion date</h2>
 *
 * <p>{@code tentativeCompletion} — the start date plus the critical-path TAT,
 * walked through the working calendar — is read from
 * {@link ObRunningProjectReader} rather than recomputed, so the board and the
 * Projects grid cannot one day disagree about when a project is due.
 *
 * <p><b>{@code delayedByDays} is not the same fact and is never folded into the
 * bucket.</b> It is the earliest overdue <em>task</em>; a project can be three
 * weeks from its completion date with a task a fortnight late, and colouring it
 * red would answer a question nobody asked of this screen. Both travel on the
 * row, and the design shows the delay as a chip beside a bucket it did not set.
 *
 * <h2>Working days, not calendar days</h2>
 *
 * <p>CLAUDE.md's rule, and the one number a naive subtraction gets visibly
 * wrong: a project due Friday and still running on Saturday morning is not
 * late, and on Monday it is one day late rather than three. The conversion is
 * {@link WorkingHoursService} over the org calendar, ceiling-rounded on
 * {@code ObDelayedProjectsService}'s own argument — an hour into Monday is a
 * fraction of a working day accrued, and reporting zero until a whole one
 * elapses would agree with the naive answer exactly where it is wrong.
 */
@Service
class ObProjectBoardService {

    /**
     * Seven working days is the line between "late" and "at risk".
     *
     * <p>The threshold the screen was specified with. Named rather than typed
     * into the comparison twice, because it appears once in the bucket rule and
     * once in the card that counts the bucket, and two literals is how those
     * come to disagree.
     */
    static final int AT_RISK_AFTER_WORKING_DAYS = 7;

    private final ObRunningProjectReader projects;
    private final ObProjectBoardRepository counts;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final Clock clock;

    @Autowired
    ObProjectBoardService(ObRunningProjectReader projects, ObProjectBoardRepository counts,
                          WorkingHoursService workingHours, WorkingCalendarRepository calendars) {
        this(projects, counts, workingHours, calendars, Clock.systemUTC());
    }

    /** Test seam — every figure on this board is measured against "now". */
    ObProjectBoardService(ObRunningProjectReader projects, ObProjectBoardRepository counts,
                          WorkingHoursService workingHours, WorkingCalendarRepository calendars,
                          Clock clock) {
        this.projects = projects;
        this.counts = counts;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.clock = clock;
    }

    ObProjectBoard board(CallerIdentity caller) {
        ObClientScope scope = ObClientScope.of(caller);
        /*
          Two scope objects, one rule. `ObClientScope` carries the SQL predicate
          the projects read applies; `ObDashboardScope` carries the sentence
          every other route on this controller sends as `appliedScope`, and
          taking it from there rather than writing a fourth copy of "all
          clients" / "clients you created" is what keeps the two boards on this
          screen describing their scope in the same words.
        */
        String appliedScope = ObDashboardScope.of(caller).appliedScope();
        Instant now = clock.instant();
        Calendar calendar = calendar();

        /*
          "Today" is the calendar date in the *working calendar's* timezone, not
          the server's and not the caller's. Storage is UTC everywhere
          (CLAUDE.md), and a board that read `LocalDate.now()` off a UTC clock
          would tell an IST office at 03:00 that yesterday's deliveries are
          today's. The response carries the date it used so a screen can say so.
        */
        LocalDate today = now.atZone(calendar.zone()).toLocalDate();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<ObRunningProject> running = projects.runningProjects(scope);
        List<Long> ids = running.stream().map(ObRunningProject::id).toList();
        Map<Long, ObProjectBoardRepository.TaskCounts> tasks = counts.taskCountsByProject(ids);
        Map<Long, Integer> escalations = counts.openEscalationsByProject(ids);

        List<ObProjectBoardRow> rows = running.stream()
                .map(project -> row(project,
                        tasks.getOrDefault(project.id(), ObProjectBoardRepository.TaskCounts.NONE),
                        escalations.getOrDefault(project.id(), 0),
                        now, calendar))
                .toList();

        return new ObProjectBoard(now, today, weekStart, weekEnd, appliedScope,
                cards(rows, today, weekStart, weekEnd), schedule(rows), rows,
                ObRunningProjectReader.hitCeiling(running));
    }

    /**
     * The board an unidentifiable caller gets — no rows, every count zero.
     *
     * <p>Not a 404 and not an exception: the route is legitimately theirs, and
     * the controller has already had {@code @PreAuthorize} refuse the
     * anonymous case, so this branch exists for the record a token could
     * describe but this process cannot resolve. The dates are the ones a reader
     * would otherwise have no way to interpret the zeroes against.
     */
    static ObProjectBoard empty() {
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneId.of("UTC")).toLocalDate();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        return new ObProjectBoard(now, today, weekStart, weekStart.plusDays(6),
                "no clients", new ObProjectBoardCards(0, 0, 0, 0, 0, 0),
                new ObProjectBoardSchedule(0, 0, 0, 0, 0), List.of(), false);
    }

    // ------------------------------------------------------------------
    // One row
    // ------------------------------------------------------------------

    private ObProjectBoardRow row(ObRunningProject project, ObProjectBoardRepository.TaskCounts tasks,
                                  int openEscalations, Instant now, Calendar calendar) {
        Integer daysPast = daysPastCompletion(project.tentativeCompletion(), now, calendar);
        Integer budgetUsed = budgetUsedPercent(project.startDate(), project.totalTatDays(), now, calendar);
        String bucket = bucket(project, daysPast, budgetUsed, tasks);

        return new ObProjectBoardRow(
                project.id(), project.name(),
                new ObProjectBoardClientRef(project.clientId(), project.clientName(),
                        project.clientCode(), project.clientCity()),
                new ObProductRef(project.productId(), project.productCode(), project.productName()),
                project.startDate(),
                UserRef.of(project.salesPersonId(), project.salesPersonName()),
                UserRef.of(project.implementorId(), project.implementorName()),
                project.gateStatus(), project.currentStage(), bucket,
                project.tentativeCompletion(), daysPast, project.delayedByDays(),
                tasks.tasksTotal(), tasks.tasksDone(), budgetUsed, openEscalations);
    }

    /**
     * Which of the five buckets this project is in.
     *
     * <p>Order matters and is the order the rules are read in: a project
     * without a completion date cannot be measured against one, a project past
     * its date is late whatever its progress looks like, and only what is left
     * can be called ahead.
     *
     * <p><b>{@code AHEAD} is a claim, so it is made from evidence.</b> Three
     * things together: more of the work is done than of the budget is spent,
     * nothing is overdue, and there is work to have finished — which is why
     * {@code tasksDone} must be positive rather than the fraction merely being
     * defined. A project one day old with nothing done is on time, not ahead,
     * and a project with no tasks at all is neither.
     */
    private static String bucket(ObRunningProject project, Integer daysPast, Integer budgetUsed,
                                 ObProjectBoardRepository.TaskCounts tasks) {
        if (project.tentativeCompletion() == null) {
            return "NOT_SCHEDULED";
        }
        if (daysPast != null) {
            return daysPast > AT_RISK_AFTER_WORKING_DAYS ? "AT_RISK" : "DELAYED";
        }
        boolean nothingOverdue = project.delayedByDays() == null;
        boolean aheadOfBudget = budgetUsed != null && tasks.tasksTotal() > 0 && tasks.tasksDone() > 0
                && (tasks.tasksDone() * 100 / tasks.tasksTotal()) > budgetUsed;
        return nothingOverdue && aheadOfBudget ? "AHEAD" : "ON_TIME";
    }

    /**
     * Ceiling working days between the close of business on the completion date
     * and now, or null.
     *
     * <p>Measured from the <b>end</b> of that working day, which is the half of
     * this that is easy to get wrong: a project due today is not late at nine in
     * the morning, and measuring from the start of the day would report it a
     * fraction of a day overdue before anyone had begun work on it.
     *
     * <p>Null when the project is not past its date, and null when the ceiling
     * lands on zero — the same distinction {@code ObProjectService} draws for
     * {@code delayedByDays}, and for the same reason: zero would be a claim
     * that the project is on time today, where null says the question does not
     * apply.
     */
    private Integer daysPastCompletion(LocalDate completion, Instant now, Calendar calendar) {
        if (completion == null) {
            return null;
        }
        Instant dueAtClose = completion.atTime(calendar.dayEnd()).atZone(calendar.zone()).toInstant();
        if (!dueAtClose.isBefore(now)) {
            return null;
        }
        if (calendar.hoursPerWorkingDay().signum() <= 0) {
            // A broken calendar row, not a project with no delay. Saying
            // nothing beats dividing by zero — ObProjectService's own choice.
            return null;
        }
        int days = workingHours.workingHoursBetween(dueAtClose, now)
                .divide(calendar.hoursPerWorkingDay(), 0, RoundingMode.CEILING)
                .intValueExact();
        return days < 1 ? null : days;
    }

    /**
     * Working hours elapsed since the project opened, as a percentage of its
     * TAT budget.
     *
     * <p>Measured from the opening of the working day on the start date, which
     * is where {@code ObProjectService} measures the budget <em>to</em> — the
     * two are the same clock read from both ends, so a project reading 100%
     * here is a project standing on its completion date.
     *
     * <p>Not capped at 100: a project three weeks over budget should say so,
     * and a bar that stops at full tells the reader nothing about how far past
     * it went. Null when there is no budget to be a share of.
     */
    private Integer budgetUsedPercent(LocalDate startDate, int totalTatDays, Instant now, Calendar calendar) {
        if (totalTatDays <= 0 || calendar.hoursPerWorkingDay().signum() <= 0) {
            return null;
        }
        Instant from = startDate.atTime(calendar.dayStart()).atZone(calendar.zone()).toInstant();
        BigDecimal budget = BigDecimal.valueOf(totalTatDays).multiply(calendar.hoursPerWorkingDay());
        return workingHours.workingHoursBetween(from, now)
                .multiply(BigDecimal.valueOf(100))
                .divide(budget, 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    // ------------------------------------------------------------------
    // The counters
    // ------------------------------------------------------------------

    /**
     * The six cards, every one counted from {@code rows}.
     *
     * <p>{@code thisWeeksDeadlines} includes the days already gone: a Monday
     * deadline is still this week's on Thursday, it is simply also overdue.
     * Counting only what is left would make the card shrink as the week
     * progressed without anybody delivering anything, which reads as progress.
     */
    private static ObProjectBoardCards cards(List<ObProjectBoardRow> rows, LocalDate today,
                                             LocalDate weekStart, LocalDate weekEnd) {
        return new ObProjectBoardCards(
                rows.size(),
                (int) rows.stream().filter(r -> within(r.tentativeCompletion(), weekStart, weekEnd)).count(),
                (int) rows.stream().filter(r -> today.equals(r.tentativeCompletion())).count(),
                (int) rows.stream().filter(r -> "DELAYED".equals(r.bucket())).count(),
                (int) rows.stream().filter(r -> "AT_RISK".equals(r.bucket())).count(),
                (int) rows.stream().filter(r -> r.openEscalations() > 0).count());
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** The donut's five slices. They sum to {@code cards.ongoingProjects} — the contract says so, and this is why. */
    private static ObProjectBoardSchedule schedule(List<ObProjectBoardRow> rows) {
        return new ObProjectBoardSchedule(
                count(rows, "ON_TIME"), count(rows, "AHEAD"), count(rows, "DELAYED"),
                count(rows, "AT_RISK"), count(rows, "NOT_SCHEDULED"));
    }

    private static int count(List<ObProjectBoardRow> rows, String bucket) {
        return (int) rows.stream().filter(r -> bucket.equals(r.bucket())).count();
    }

    // ------------------------------------------------------------------
    // The calendar, read once per request
    // ------------------------------------------------------------------

    /**
     * The four calendar facts this board needs, fetched once.
     *
     * <p>{@code getCalendar()} is a query, and asking it per project would make
     * a two-hundred-row board two hundred reads of a single-row table for a
     * value that cannot change mid-request — {@code ObProjectService}'s own
     * note, on the same table.
     */
    private record Calendar(ZoneId zone, LocalTime dayStart, LocalTime dayEnd, BigDecimal hoursPerWorkingDay) {
    }

    private Calendar calendar() {
        var calendar = calendars.getCalendar();
        BigDecimal hours = BigDecimal.valueOf(calendar.workDayLength().toMinutes())
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return new Calendar(calendar.zone(), calendar.getWorkDayStart(), calendar.getWorkDayEnd(), hours);
    }
}
