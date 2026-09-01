package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dashboard Rework Dev 2, PR 12 · {@code GET /dashboard/weekly}'s four cards,
 * each against the same week seven days earlier — PR 2 stubbed this class to
 * echo the week back and answer no cards until PR 11's counters existed.
 *
 * <h2>The week is closed and fixed; the day read inside it is not</h2>
 *
 * <p>Every card is <b>stock</b> — what was true at a moment — so none of them
 * sums across the week, which would count a ticket once per day it stayed
 * open. {@link DashboardRepository}'s own header states the rule; what is
 * particular here is that two different days inside the same week are the
 * right ones to read:
 *
 * <ul>
 *   <li><b>Delayed and average delay</b> read the <em>latest</em> summarised
 *       day in the week — "how much is late", answered as late in the week as
 *       the data goes.</li>
 *   <li><b>Due this week</b> reads the week's <em>Monday</em>. {@code
 *       open_due_next_7} counts what is due in the seven days beginning on
 *       the day it was computed for, so on a Monday it is precisely
 *       Monday-to-Sunday — the ISO week — and on any other day it straddles
 *       the boundary into the next one.</li>
 *   <li><b>Average progress</b> reads the latest day carrying a measured
 *       figure, which is usually neither of the above — see {@link
 *       WeeklyStatsRepository#projectProgress}.</li>
 * </ul>
 *
 * <h2>A non-Monday {@code weekStart} is refused</h2>
 *
 * <p>The contract's own words: a Wednesday-to-Wednesday window "would return
 * figures that look ordinary and compare against the wrong seven days". A 400
 * rather than a silent shift to the containing Monday, because the second is
 * indistinguishable from correct at the call site and produces a deep link
 * whose label and contents disagree.
 *
 * <h2>Deltas are null, never zero, when the prior week has nothing</h2>
 *
 * <p>{@link DashboardService#deltaPct} settled this for the KPI row and the
 * reasoning is unchanged: a week with no comparison has not "held steady",
 * and a confident 0% says it did. Restated rather than shared because that
 * method is private to a class this one does not extend, and the two divide
 * different things — percentage points here, counts there.
 */
@Service
class WeeklyProgressService {

    private final WeeklyStatsRepository weekly;
    private final DashboardRepository summaries;
    private final Clock clock;

    @Autowired
    WeeklyProgressService(WeeklyStatsRepository weekly, DashboardRepository summaries) {
        this(weekly, summaries, Clock.systemUTC());
    }

    /** Test seam — see {@code DashboardService}'s own {@code Clock} for why one is supplied rather than injected. */
    WeeklyProgressService(WeeklyStatsRepository weekly, DashboardRepository summaries, Clock clock) {
        this.weekly = weekly;
        this.summaries = summaries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    DashboardWeeklyDtos.DashboardWeeklyData weekly(CallerIdentity caller, Long projectId, LocalDate weekStart,
                                                    Long assigneeId) {
        LocalDate start = weekStart != null ? weekStart : currentWeekMonday();
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "weekStart must be a Monday; " + start + " is a "
                            + start.getDayOfWeek().toString().toLowerCase()
                            + ". A window that does not start on Monday compares against the wrong seven days.");
        }
        LocalDate end = start.plusDays(6);

        DashboardScope scope = DashboardScope.of(caller);
        if (!scope.coversProject(projectId)) {
            // A-077's refusal, restated for this tab as TodayProgressService
            // and OverviewService each restate it for their own — see
            // TodayProgressService's header on why three statements rather
            // than one shared helper.
            return new DashboardWeeklyDtos.DashboardWeeklyData(
                    null, WidgetService.NOT_YOUR_PROJECT, start, end, List.of());
        }

        LocalDate priorStart = start.minusDays(7);
        LocalDate priorEnd = end.minusDays(7);

        Long subject = scope.resourceSubject(assigneeId);
        return subject != null
                ? forResource(subject, start, end, priorStart, priorEnd)
                : forProjects(scope, projectId, start, end, priorStart, priorEnd);
    }

    // ── project-keyed — Admin, PM, Support ────────────────────────────────

    private DashboardWeeklyDtos.DashboardWeeklyData forProjects(DashboardScope scope, Long projectId,
                                                                 LocalDate start, LocalDate end,
                                                                 LocalDate priorStart, LocalDate priorEnd) {
        List<Long> projects = scope.projectIds();
        String scopeSuffix = projectId == null ? "" : "&projectId=" + projectId;

        Week now = projectWeek(projects, projectId, start, end);
        Week before = projectWeek(projects, projectId, priorStart, priorEnd);

        Instant asOf = summaries.computedAt(start, end).orElse(null);
        return new DashboardWeeklyDtos.DashboardWeeklyData(asOf, null, start, end,
                cards(now, before, start, end, scopeSuffix));
    }

    /**
     * One week's four raw quantities, read from whichever day inside it is
     * the right one for each — see the class header.
     */
    private Week projectWeek(List<Long> projects, Long projectId, LocalDate start, LocalDate end) {
        Optional<LocalDate> latest = weekly.latestProjectDay(start, end, projects, projectId);
        WeeklyStatsRepository.ProjectWeekStock stock = latest
                .map(day -> weekly.projectStock(day, projects, projectId))
                .orElse(null);

        // Monday's row, for the reason the header gives. Absent when the week
        // has no Monday row at all, in which case the due card reads zero
        // rather than borrowing another day's straddling window.
        long dueThisWeek = weekly.latestProjectDay(start, start, projects, projectId)
                .map(monday -> weekly.projectStock(monday, projects, projectId).openDueNext7())
                .orElse(0L);

        Optional<WeeklyStatsRepository.Progress> progress =
                weekly.projectProgress(start, end, projects, projectId);

        // Finished so far: flow, so it genuinely does sum across the week —
        // the one figure on this tab that does. Bounded at today, so a week
        // still running does not claim the whole of it has been counted.
        LocalDate finishedTo = end.isAfter(today()) ? today() : end;
        long finished = finishedTo.isBefore(start) ? 0
                : summaries.projectFlow(start, finishedTo, projects, projectId).closed();

        return new Week(
                stock == null ? 0 : stock.openDelayed(),
                stock == null ? 0 : stock.delayDaysSum(),
                dueThisWeek,
                finished,
                progress.map(p -> new ProgressPair(p.pctSum(), p.openTotal())).orElse(null),
                latest.isPresent());
    }

    // ── resource-keyed — a delivery role's own week, or one named resource ─

    private DashboardWeeklyDtos.DashboardWeeklyData forResource(long subject, LocalDate start, LocalDate end,
                                                                 LocalDate priorStart, LocalDate priorEnd) {
        String scopeSuffix = "&assigneeId=" + subject;

        Week now = resourceWeek(subject, start, end);
        Week before = resourceWeek(subject, priorStart, priorEnd);

        Instant asOf = weekly.latestResourceDay(start, end, subject)
                .flatMap(day -> weekly.resourceStock(day, subject))
                .map(WeeklyStatsRepository.ResourceWeekStock::computedAt)
                .orElse(null);

        return new DashboardWeeklyDtos.DashboardWeeklyData(asOf, null, start, end,
                cards(now, before, start, end, scopeSuffix));
    }

    private Week resourceWeek(long subject, LocalDate start, LocalDate end) {
        Optional<LocalDate> latest = weekly.latestResourceDay(start, end, subject);
        Optional<WeeklyStatsRepository.ResourceWeekStock> stock =
                latest.flatMap(day -> weekly.resourceStock(day, subject));

        long dueThisWeek = weekly.resourceDueThatWeek(start, subject).orElse(0L);

        // Bounded at today for the project variant's reason, and guarded the
        // same way: a week entirely in the future would otherwise ask for a
        // range whose end precedes its start.
        LocalDate finishedTo = end.isAfter(today()) ? today() : end;
        long finished = finishedTo.isBefore(start) ? 0
                : summaries.resourceFlow(start, finishedTo, subject).closed();

        // Own-work progress is offered for the CURRENT week only. pct_sum is
        // 0 both for "not measured on this day" and for "genuinely no
        // progress", and this table cannot tell them apart — see
        // WeeklyStatsRepository.ResourceWeekStock. Restricting to the week
        // that contains today is the only window where a non-zero value is
        // known to be a measurement rather than a default.
        ProgressPair progress = null;
        if (containsToday(start, end)) {
            progress = stock.map(s -> new ProgressPair(s.pctSum(), s.openTotal())).orElse(null);
        }

        return new Week(
                stock.map(WeeklyStatsRepository.ResourceWeekStock::assignedDelayed).orElse(0L),
                stock.map(WeeklyStatsRepository.ResourceWeekStock::delayDaysSum).orElse(0L),
                dueThisWeek, finished, progress, latest.isPresent());
    }

    // ── the four cards ────────────────────────────────────────────────────

    /**
     * @param progress   null when the week carries no measured figure at all,
     *                   which omits the card rather than sending a zero — a
     *                   fabricated "0% progress" is a measurement, and the
     *                   wrong one. {@code WidgetService}'s "an empty series
     *                   renders as a claim about the data, and a false one"
     *                   applied to a card.
     * @param summarised whether the week has any summarised day, which is
     *                   what separates "nothing is late" from "nothing has
     *                   been computed".
     */
    private record Week(long delayed, long delayDaysSum, long due, long finished,
                        ProgressPair progress, boolean summarised) {
    }

    private record ProgressPair(long pctSum, long openTotal) {

        BigDecimal average() {
            if (openTotal == 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(pctSum)
                    .divide(BigDecimal.valueOf(openTotal), 1, RoundingMode.HALF_UP);
        }
    }

    private List<DashboardWeeklyDtos.WeeklyCard> cards(Week now, Week before, LocalDate start, LocalDate end,
                                                        String scopeSuffix) {
        List<DashboardWeeklyDtos.WeeklyCard> cards = new ArrayList<>(4);

        // 1 · Avg progress — omitted entirely when unmeasured, per the record
        // note above. The delta is in percentage POINTS rather than a percent
        // change: "54% this week against 48% last" is a rise of six points,
        // and expressing it as +12.5% would be arithmetically true and read
        // by everyone as something else.
        if (now.progress() != null) {
            BigDecimal average = now.progress().average();
            Double delta = before.progress() == null ? null
                    : average.subtract(before.progress().average()).doubleValue();
            cards.add(new DashboardWeeklyDtos.WeeklyCard("avg-progress", "Avg progress — open tickets",
                    average.doubleValue(), "PERCENT", null, null, delta,
                    "/tickets?excludeClosed=true" + scopeSuffix));
        }

        // 2 · Due this week, with finished-so-far beside it. The drill-down
        // is the due window itself — dueFrom/dueTo, which GET /tickets
        // implements — rather than the seven-day-forward shape the column was
        // computed with, because the two describe the same set when the
        // column is read on a Monday and only that reading is used here.
        cards.add(new DashboardWeeklyDtos.WeeklyCard("due-this-week", "Due this week",
                now.due(), "COUNT",
                (double) now.finished(), "finished so far",
                deltaPct(now.due(), before.due(), before.summarised()),
                "/tickets?dueFrom=" + start + "&dueTo=" + end + scopeSuffix));

        // 3 · Delayed, open.
        cards.add(new DashboardWeeklyDtos.WeeklyCard("delayed-vs-last-week", "Delayed — open",
                now.delayed(), "COUNT",
                before.summarised() ? (double) before.delayed() : null,
                before.summarised() ? "last week" : null,
                deltaPct(now.delayed(), before.delayed(), before.summarised()),
                "/tickets?isDelayed=true&excludeClosed=true" + scopeSuffix));

        // 4 · Average delay in days, over the delayed population only —
        // dividing by every open ticket would dilute the figure with work
        // that is not late at all and report "0.4 days late" for a team with
        // three tickets a week overdue.
        //
        // A ticket due later today is inside `delayed` (open_delayed counts
        // planned_close_date before the day ENDS, matching is_delayed) while
        // contributing zero whole days to the sum, so it pulls the average
        // down by exactly the amount it is late, which is none. Named rather
        // than corrected: the alternative is a second column counting only
        // strictly-overdue tickets, and the two would then disagree about
        // what "delayed" means on two cards sitting side by side.
        cards.add(new DashboardWeeklyDtos.WeeklyCard("avg-delay-days", "Avg delay",
                averageDelay(now).doubleValue(), "DAYS", null, null,
                before.summarised()
                        ? averageDelay(now).subtract(averageDelay(before)).doubleValue()
                        : null,
                "/tickets?isDelayed=true&excludeClosed=true" + scopeSuffix));

        return List.copyOf(cards);
    }

    private static BigDecimal averageDelay(Week week) {
        if (week.delayed() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(week.delayDaysSum())
                .divide(BigDecimal.valueOf(week.delayed()), 1, RoundingMode.HALF_UP);
    }

    /**
     * @param priorSummarised false means the prior week was never computed,
     *                        which is not the same as its having been zero —
     *                        the first returns no delta, the second is a real
     *                        comparison against nothing outstanding.
     */
    private static Double deltaPct(long now, long before, boolean priorSummarised) {
        if (!priorSummarised || before == 0) {
            return null;
        }
        return BigDecimal.valueOf(now - before)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ── clock ─────────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private boolean containsToday(LocalDate start, LocalDate end) {
        LocalDate today = today();
        return !today.isBefore(start) && !today.isAfter(end);
    }

    private LocalDate currentWeekMonday() {
        return today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
