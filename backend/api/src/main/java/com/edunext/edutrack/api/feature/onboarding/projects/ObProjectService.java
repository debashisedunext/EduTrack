package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObClientRef;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProductRef;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectDetail;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectListResponse;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectServiceRef;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectStage;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.ObProjectSummary;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectDtos.UserRef;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectReadRepository.Row;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectReadRepository.ServiceRow;
import com.edunext.edutrack.api.feature.onboarding.projects.ObProjectReadRepository.StageRow;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObProjectStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assembles the Projects grid and the project header.
 *
 * <h2>The two figures SQL could not produce</h2>
 *
 * <p>CLAUDE.md: every duration and SLA figure in the system routes through the
 * working calendar, and SQL cannot call it. So
 * {@link ObProjectReadRepository} hands back two raw inputs and this class
 * turns them into the columns people read:
 *
 * <ul>
 *   <li><b>{@code delayedByDays}</b> — ceiling working days between the
 *       earliest overdue task's due date and now. Ceiling rather than floor,
 *       on {@code ObDelayedProjectsService}'s own argument: a task due Friday
 *       and still open a minute into Monday has accrued a fraction of a
 *       working day, and reporting zero until a whole one elapses would make
 *       this grid agree with a naive calendar subtraction for the exact case
 *       that subtraction gets wrong.</li>
 *   <li><b>{@code tentativeCompletion}</b> — the project's start date plus its
 *       total TAT, walked forward through weekends and org holidays by
 *       {@link WorkingHoursService#addWorkingHours}. A Friday start with a
 *       four-day budget lands on Thursday, not on Tuesday.</li>
 * </ul>
 *
 * <h2>Null means "not late", and it is not the same as zero</h2>
 *
 * <p>{@code delayedByDays} is null for a project with nothing overdue, and for
 * every project whose status does not accrue delay — see
 * {@link ObProjectStatus#accruesDelay()}. A completed project that finished a
 * fortnight after its last due date must not keep counting, and a dropped one
 * has a clock somebody stopped on purpose. Zero would be a claim that the
 * project is on time today; null says the question does not apply.
 */
@Service
class ObProjectService {

    private final ObProjectReadRepository reads;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final Clock clock;

    @Autowired
    ObProjectService(ObProjectReadRepository reads, WorkingHoursService workingHours,
                     WorkingCalendarRepository calendars) {
        this(reads, workingHours, calendars, Clock.systemUTC());
    }

    /** Test seam — both computed figures are measured against "now", which a fixed clock lets a test pin. */
    ObProjectService(ObProjectReadRepository reads, WorkingHoursService workingHours,
                     WorkingCalendarRepository calendars, Clock clock) {
        this.reads = reads;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // The grid
    // ------------------------------------------------------------------

    ObProjectListResponse list(ObClientScope scope, String q, Long clientId, Long productId,
                               String status, Long implementorId, Long salesPersonId,
                               String cursor, Integer limitParam) {

        if (scope.deniesEverything()) {
            return new ObProjectListResponse(List.of(), com.edunext.edutrack.common.pagination.PageMeta.last());
        }
        int limit = PageLimit.clamp(limitParam);
        Instant now = clock.instant();

        List<Row> fetched = reads.list(scope, q, clientId, productId, status, implementorId,
                salesPersonId, cursor, PageLimit.fetchSize(limit), now);

        CursorPage<Row> page = CursorPage.of(fetched, limit,
                row -> new Cursor(row.startDate().toString(), row.id()));

        /*
          The roll-up arrives at journey grain — one row per service per stage,
          which is what the detail page's tree needs. The grid needs the
          project's own figures, so each project's rows are folded here. Summing
          is exactly what the SQL used to do in its GROUP BY, moved up one
          level so both readings come from one query.
        */
        List<Long> ids = page.data().stream().map(Row::id).toList();
        Map<Long, List<StageRow>> stages = reads.stagesByProject(ids);
        /*
          One more query for the page, not one per row: the project's TAT is the
          heaviest chain through its Module Services and a chain is a graph walk
          — see `ObProjectTatPath` for why that is not a scalar subquery.
        */
        Map<Long, Integer> tat = reads.criticalPathTatByProject(ids);

        Calendar calendar = calendar();
        List<ObProjectSummary> data = page.data().stream()
                .map(row -> summary(row, foldToProject(stages.getOrDefault(row.id(), List.of())),
                        tat.getOrDefault(row.id(), 0), now, calendar))
                .toList();

        return new ObProjectListResponse(data, page.meta());
    }

    // ------------------------------------------------------------------
    // The header
    // ------------------------------------------------------------------

    Optional<ObProjectDetail> findDetail(ObClientScope scope, long projectId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return reads.findDetail(scope, projectId, now).map(row -> {
            List<StageRow> journeyRows = reads.stagesByProject(List.of(row.id()))
                    .getOrDefault(row.id(), List.of());
            /*
              One query, read two ways. `stageRows` is the project's own
              roll-up, for the header and the summary strip; `byJourney` is the
              same rows kept at service grain, for the tree. Fetching the second
              separately is what would let the header disagree with the branch
              underneath it.
            */
            List<StageRow> stageRows = foldToProject(journeyRows);
            Map<Long, List<StageRow>> byJourney = journeyRows.stream()
                    .collect(Collectors.groupingBy(StageRow::journeyId, LinkedHashMap::new,
                            Collectors.toList()));
            List<ServiceRow> serviceRows = reads.servicesByProject(List.of(row.id()))
                    .getOrDefault(row.id(), List.of());
            int totalTatDays = reads.criticalPathTatByProject(List.of(row.id()))
                    .getOrDefault(row.id(), 0);
            ObProjectSummary summary = summary(row, stageRows, totalTatDays, now, calendar());
            return new ObProjectDetail(
                    summary.id(), summary.name(), summary.client(), summary.product(),
                    summary.startDate(), summary.salesPerson(), summary.implementor(),
                    summary.implementorManager(),
                    summary.status(), row.statusReason(), summary.gateStatus(),
                    summary.currentStage(), summary.stagesComplete(), summary.stagesTotal(),
                    summary.journeyCount(), summary.delayedByDays(), summary.tentativeCompletion(),
                    summary.totalTatDays(),
                    stages(stageRows),
                    serviceRows.stream()
                            .map(s -> new ObProjectServiceRef(s.journeyId(), s.templateId(),
                                    s.serviceName(), s.gateStatus(), s.completedAt() != null,
                                    stages(byJourney.getOrDefault(s.journeyId(), List.of()))))
                            .toList(),
                    UserRef.of(row.createdBy(), row.createdByName()),
                    row.createdAt());
        });
    }

    // ------------------------------------------------------------------
    // One row
    // ------------------------------------------------------------------

    private ObProjectSummary summary(Row row, List<StageRow> stageRows, int totalTatDays,
                                     Instant now, Calendar calendar) {
        ObProjectStatus status = parseStatus(row.status());
        return new ObProjectSummary(
                row.id(),
                row.name(),
                new ObClientRef(row.clientId(), row.clientName(), row.clientCode(), row.clientCity()),
                new ObProductRef(row.productId(), row.productCode(), row.productName()),
                row.startDate(),
                UserRef.of(row.salesPersonId(), row.salesPersonName()),
                UserRef.of(row.implementorId(), row.implementorName()),
                UserRef.of(row.implementorManagerId(), row.implementorManagerName()),
                row.status(),
                row.gateStatus(),
                currentStageName(stageRows),
                // `taskCount > 0` as well as nothing outstanding — see
                // STAGE_ROLLUP. A stage the template publishes but schedules
                // nothing into has no outstanding work either, and counting it
                // complete would report six of seven stages done on a project
                // where one was finished and five were never set up.
                (int) stageRows.stream()
                        .filter(s -> s.taskCount() > 0 && s.tasksOutstanding() == 0).count(),
                stageRows.size(),
                row.journeyCount(),
                delayedByDays(row.earliestOverdueAt(), now, status, calendar),
                tentativeCompletion(row.startDate(), totalTatDays, calendar),
                totalTatDays);
    }

    /**
     * The stage holding the lowest-sequence task that is actually running.
     *
     * <p>Not "the first incomplete stage", which would name a stage whose tasks
     * are all still {@code PENDING} behind a dependency and report a project as
     * being at a stage nobody has started. A task still on {@code PENDING}
     * counts as running here if any of its check list items has been answered —
     * {@code STAGE_ROLLUP}'s {@code minActiveSequence} — so a task somebody has
     * been ticking through without formally starting it still surfaces as the
     * project's current stage. Null where nothing runs at all — a project held
     * behind a sibling service, or every task blocked.
     */
    private static String currentStageName(List<StageRow> stageRows) {
        return stageRows.stream()
                .filter(s -> s.minActiveSequence() != null)
                .min(Comparator.comparingInt(StageRow::minActiveSequence))
                .map(StageRow::stageName)
                .orElse(null);
    }

    /**
     * Journey-grain roll-up rows, summed onto the project.
     *
     * <p>{@code STAGE_ROLLUP} groups by journey so each Module Service can
     * carry its own stages. Everything that speaks for the project as a whole —
     * the grid row, the header, {@code ObProjectDetail.stages} — wants them
     * folded, and this is the one place that happens.
     *
     * <p>The three aggregates fold the way the SQL's own {@code GROUP BY} did:
     * counts add, and {@code minActiveSequence} takes the minimum across
     * services, because the running stage of a project is the earliest one
     * running in any of them. Two services sharing a template therefore report
     * one Configuration, exactly as before this was grouped finer.
     *
     * <p>Returned in ribbon order — sequence, then key — so callers that index
     * by position are reading the same order the query gave them.
     */
    // Package-private, not private: this is the one piece of arithmetic that
    // decides every project's "Stages 2/7", and it is asserted directly in
    // ObProjectStageFoldTest rather than through a container.
    static List<StageRow> foldToProject(List<StageRow> journeyRows) {
        Map<Long, StageRow> byStage = new LinkedHashMap<>();
        for (StageRow row : journeyRows) {
            byStage.merge(row.stageKey(), row, (a, b) -> new StageRow(
                    a.projectId(),
                    // The fold has no single journey, and saying it does would
                    // invite a caller to believe it. Zero is the "no journey"
                    // value, and nothing downstream of the fold reads it.
                    0L,
                    a.stageKey(),
                    a.stageName(),
                    a.stageSequence(),
                    a.taskCount() + b.taskCount(),
                    a.tasksOutstanding() + b.tasksOutstanding(),
                    minActive(a.minActiveSequence(), b.minActiveSequence())));
        }
        return byStage.values().stream()
                .sorted(Comparator.comparingInt(StageRow::stageSequence)
                        .thenComparingLong(StageRow::stageKey))
                .toList();
    }

    /** The earlier of two running-task sequences, either of which may be absent. */
    private static Integer minActive(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static List<ObProjectStage> stages(List<StageRow> stageRows) {
        Integer earliestActive = stageRows.stream()
                .map(StageRow::minActiveSequence)
                .filter(java.util.Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
        return stageRows.stream()
                // `isComplete` needs a task to have been completed — an empty
                // stage reports 0 outstanding and has finished nothing. Same
                // test as the summary's count above, for the same reason.
                .map(s -> new ObProjectStage(s.stageKey(), s.stageName(), s.stageSequence(),
                        s.taskCount(), s.tasksOutstanding(),
                        s.taskCount() > 0 && s.tasksOutstanding() == 0,
                        earliestActive != null && earliestActive.equals(s.minActiveSequence())))
                .toList();
    }

    /**
     * Ceiling working days between the earliest overdue due date and now, or
     * null.
     *
     * <p>Null both when nothing is overdue and when the status does not accrue
     * delay — see the class note on why that is not zero. Also null when the
     * ceiling lands on zero, which a task that went overdue moments ago can
     * still do: a project is not "0 days late", it is on time.
     */
    private Integer delayedByDays(Instant earliestOverdueAt, Instant now,
                                  ObProjectStatus status, Calendar calendar) {
        if (earliestOverdueAt == null || !status.accruesDelay()) {
            return null;
        }
        if (calendar.hoursPerWorkingDay().signum() <= 0) {
            // ck_working_calendar_weekly_off guarantees a working day exists; a
            // non-positive length means the calendar row is broken, not that
            // this project has no delay to report. Saying nothing beats
            // dividing by zero.
            return null;
        }
        int days = workingHours.workingHoursBetween(earliestOverdueAt, now)
                .divide(calendar.hoursPerWorkingDay(), 0, RoundingMode.CEILING)
                .intValueExact();
        return days < 1 ? null : days;
    }

    /**
     * {@code startDate} walked forward by the project's TAT budget, through the
     * working calendar.
     *
     * <p>Measured from the <b>opening of the working day</b> on the start date
     * rather than from midnight: a budget consumed from 00:00 would spend its
     * first hours in the middle of the night, and
     * {@link WorkingHoursService#addWorkingHours} would carry them to the next
     * working morning anyway — arriving at the same answer by a route that is
     * harder to explain when it is off by one.
     *
     * <p>Null for a project with no instantiated task, where there is no budget
     * and a date would be a guess presented as a commitment.
     */
    private LocalDate tentativeCompletion(LocalDate startDate, int totalTatDays, Calendar calendar) {
        if (totalTatDays <= 0) {
            return null;
        }
        Instant from = startDate.atTime(calendar.dayStart()).atZone(calendar.zone()).toInstant();
        BigDecimal hours = BigDecimal.valueOf(totalTatDays).multiply(calendar.hoursPerWorkingDay());
        return workingHours.addWorkingHours(from, hours).atZone(calendar.zone()).toLocalDate();
    }

    /**
     * An unrecognised status reads as {@link ObProjectStatus#RUNNING}.
     *
     * <p>{@code ck_ob_projects_status} makes this unreachable from the database
     * side; it exists so a value added to the enum and not yet to the check —
     * or the reverse — degrades into "still running, still reporting" rather
     * than a 500 on a grid of fifty rows because one of them is new.
     */
    private static ObProjectStatus parseStatus(String status) {
        try {
            return ObProjectStatus.valueOf(status);
        } catch (IllegalArgumentException unrecognised) {
            return ObProjectStatus.RUNNING;
        }
    }

    // ------------------------------------------------------------------
    // The calendar, read once per request
    // ------------------------------------------------------------------

    /**
     * The three calendar facts both computations need, fetched once for the
     * whole page.
     *
     * <p>{@code WorkingCalendarRepository.getCalendar()} is a query, and asking
     * it per row would make a fifty-row grid fifty reads of a single-row table
     * for a value that cannot change mid-request.
     */
    private record Calendar(ZoneId zone, java.time.LocalTime dayStart, BigDecimal hoursPerWorkingDay) {
    }

    private Calendar calendar() {
        var calendar = calendars.getCalendar();
        BigDecimal hours = BigDecimal.valueOf(calendar.workDayLength().toMinutes())
                .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        return new Calendar(calendar.zone(), calendar.getWorkDayStart(), hours);
    }
}
