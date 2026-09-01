package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Dashboard Rework Dev 2, PR 9 · {@code GET /dashboard/overview}'s four
 * range cards, the Top Assignees open-state bars and the status donut — PR
 * 2 stubbed this class to answer empty until PR 4's counters existed to
 * read.
 *
 * <h2>Two kinds of card, the same split {@link DashboardService} already
 * made in this file's sibling</h2>
 *
 * <p><b>Total</b> and <b>Completed</b> are flow — tickets reported, and
 * tickets closed, inside {@code from}..{@code to} — read from {@link
 * DashboardRepository#projectFlow}/{@link DashboardRepository#resourceFlow},
 * which already answer exactly these two questions for the KPI row above
 * this tab. <b>Pending</b> and <b>In Progress</b> are stock — TODO and
 * IN_PROGRESS category, as things stand at the latest summarised day in the
 * window — read from {@link OverviewRepository}, which this PR adds because
 * neither sibling repository carries the category columns. Mixing flow and
 * completed with stock in one four-card row is not an inconsistency to
 * resolve; it is {@link DashboardService#cards} restated for this tab's own
 * four keys, and CLAUDE.md's "flow sums, stock does not" rule is what
 * decides which column each card reads, not which tab it is on.
 *
 * <h2>Distribution reuses the cards' own drill-downs</h2>
 *
 * <p>Built from the three already-computed card figures rather than a
 * second query, and linked with the three already-built card URLs rather
 * than a second string — {@link DashboardService}'s own reasoning: two
 * statements of "which tickets is this counting" is how a chart comes to
 * disagree with the card above it.
 *
 * <h2>Top Assignees' segments are disjoint; the WIP/Not-Started links are
 * not narrowed to match</h2>
 *
 * <p>{@code overdue} is {@code ns_overdue + wip_delayed}, subtracted back
 * out of {@code notStarted} and {@code inProgress} so the three sum to the
 * bar's own total — the identical "overdue takes precedence" rule PR 14's
 * {@code module-open} states for the same shape of three-segment bar. Its
 * links follow the same precedent too: {@code GET /tickets} has no
 * "in-progress-but-not-overdue" filter, so {@code notStarted}/{@code
 * inProgress} link to the full category and only {@code overdue} narrows —
 * {@code WidgetService#moduleOpen}'s own note calls this a lag, not a
 * disagreement, for the same reason it is one here.
 */
@Service
class OverviewService {

    /** Matches {@link DashboardService}'s own default — §S-05's window when the caller names none. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    /** Contract: "Sorted by open total, capped at ten." */
    private static final int TOP_ASSIGNEES_LIMIT = 10;

    private final OverviewRepository overview;
    private final DashboardRepository summaries;
    private final Clock clock;

    @Autowired
    OverviewService(OverviewRepository overview, DashboardRepository summaries) {
        this(overview, summaries, Clock.systemUTC());
    }

    /** Test seam — see {@link DashboardService}'s own constructor note on why this module supplies its own Clock. */
    OverviewService(OverviewRepository overview, DashboardRepository summaries, Clock clock) {
        this.overview = overview;
        this.summaries = summaries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    DashboardOverviewDtos.DashboardOverviewData overview(CallerIdentity caller, Long projectId, LocalDate from,
                                                          LocalDate to, Long assigneeId) {
        DashboardScope scope = DashboardScope.of(caller);

        // A-077's refusal, restated for this tab exactly as TodayProgressService
        // restates it for its own — see that class's header on why this is
        // three statements of one rule rather than a shared helper.
        if (!scope.coversProject(projectId)) {
            return new DashboardOverviewDtos.DashboardOverviewData(
                    null, WidgetService.NOT_YOUR_PROJECT, List.of(), List.of(), List.of());
        }

        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);

        // A-062's table decision, restated a third time for the identical
        // reason DashboardScope's own header gives: an own-work caller reads
        // resource_daily_stats for themselves, never the project table, or
        // they see their colleagues' figures on this tab while the KPI row
        // above it correctly shows only their own.
        Long subject = scope.resourceSubject(assigneeId);
        return subject != null ? forResource(subject, start, end) : forProjects(scope, projectId, start, end);
    }

    // ── project-keyed — Admin, PM, Support, or nobody filtered to one resource ─

    private DashboardOverviewDtos.DashboardOverviewData forProjects(DashboardScope scope, Long projectId,
                                                                     LocalDate start, LocalDate end) {
        String scopeSuffix = projectId == null ? "" : "&projectId=" + projectId;
        DashboardRepository.Flow flow = summaries.projectFlow(start, end, scope.projectIds(), projectId);
        Optional<LocalDate> latest = overview.latestProjectDay(start, end, scope.projectIds(), projectId);

        if (latest.isEmpty()) {
            List<DashboardOverviewDtos.OverviewCard> cards = cards(flow.created(), 0, 0, flow.closed(),
                    start, end, scopeSuffix);
            return new DashboardOverviewDtos.DashboardOverviewData(
                    null, null, cards, List.of(), distribution(cards));
        }

        LocalDate day = latest.get();
        OverviewRepository.CategoryStock stock = overview.projectCategoryStock(day, scope.projectIds(), projectId);
        Instant asOf = summaries.computedAt(start, end).orElse(null);

        List<DashboardOverviewDtos.OverviewCard> cards = cards(flow.created(), stock.pending(), stock.inProgress(),
                flow.closed(), start, end, scopeSuffix);

        List<DashboardOverviewDtos.AssigneeOpenState> assignees = overview
                .topAssignees(day, scope.projectIds(), projectId, TOP_ASSIGNEES_LIMIT)
                .stream()
                .map(OverviewService::assignee)
                .toList();

        return new DashboardOverviewDtos.DashboardOverviewData(asOf, null, cards, assignees, distribution(cards));
    }

    // ── resource-keyed — a delivery role's own figures, or one named resource ─

    private DashboardOverviewDtos.DashboardOverviewData forResource(long subject, LocalDate start, LocalDate end) {
        String scopeSuffix = "&assigneeId=" + subject;
        // created and reopened are not attributable to an assignee —
        // DashboardRepository#resourceFlow's own note. Total therefore reads
        // zero for this caller, honestly, the same way A-062's resourceCards
        // does for "Total tasks created" rather than borrowing the project
        // figure and showing a Developer their whole project's intake.
        DashboardRepository.Flow flow = summaries.resourceFlow(start, end, subject);
        Optional<LocalDate> latest = overview.latestResourceDay(start, end, subject);

        if (latest.isEmpty()) {
            List<DashboardOverviewDtos.OverviewCard> cards = cards(flow.created(), 0, 0, flow.closed(),
                    start, end, scopeSuffix);
            return new DashboardOverviewDtos.DashboardOverviewData(
                    null, null, cards, List.of(), distribution(cards));
        }

        LocalDate day = latest.get();
        OverviewRepository.CategoryStock stock = overview.resourceCategoryStock(day, subject).orElseThrow(() ->
                new IllegalStateException("latestResourceDay named " + day + " for user " + subject
                        + " but resourceCategoryStock found no row for it — the two queries disagree about what exists"));
        Instant asOf = overview.resourceComputedAt(day, subject).orElse(null);

        List<DashboardOverviewDtos.OverviewCard> cards = cards(flow.created(), stock.pending(), stock.inProgress(),
                flow.closed(), start, end, scopeSuffix);

        // Top Assignees is a "who else is carrying work" chart; it has no
        // meaning pointed at the caller's own single dashboard, the same
        // reason Today's own-work variant serves no MIS table at all.
        return new DashboardOverviewDtos.DashboardOverviewData(asOf, null, cards, List.of(), distribution(cards));
    }

    // ── the four cards ────────────────────────────────────────────────────

    private static List<DashboardOverviewDtos.OverviewCard> cards(long total, long pending, long inProgress,
                                                                   long completed, LocalDate start, LocalDate end,
                                                                   String scopeSuffix) {
        return List.of(
                new DashboardOverviewDtos.OverviewCard("total", "Total", total,
                        "/tickets?reportedFrom=" + start + "&reportedTo=" + end + scopeSuffix),
                new DashboardOverviewDtos.OverviewCard("pending", "Pending", pending,
                        "/tickets?statusCategory=TODO" + scopeSuffix),
                new DashboardOverviewDtos.OverviewCard("in-progress", "In Progress", inProgress,
                        "/tickets?statusCategory=IN_PROGRESS" + scopeSuffix),
                new DashboardOverviewDtos.OverviewCard("completed", "Completed", completed,
                        "/tickets?status=CLOSED&closedFrom=" + start + "&closedTo=" + end + scopeSuffix));
    }

    // ── the donut, built from the cards rather than a second query ──────────

    private static List<DashboardOverviewDtos.DistributionSlice> distribution(
            List<DashboardOverviewDtos.OverviewCard> cards) {
        DashboardOverviewDtos.OverviewCard pending = cards.get(1);
        DashboardOverviewDtos.OverviewCard inProgress = cards.get(2);
        DashboardOverviewDtos.OverviewCard completed = cards.get(3);
        long total = pending.value() + inProgress.value() + completed.value();

        return List.of(
                slice("TODO", "Not started / pending", pending, total),
                slice("IN_PROGRESS", "In progress", inProgress, total),
                slice("DONE", "Completed", completed, total));
    }

    private static DashboardOverviewDtos.DistributionSlice slice(String category, String label,
                                                                  DashboardOverviewDtos.OverviewCard card,
                                                                  long total) {
        return new DashboardOverviewDtos.DistributionSlice(category, label, card.value(),
                pct(card.value(), total), card.drillDown());
    }

    /** Served rather than left to the client — the contract's own reason, restated: the legend and the arc cannot round differently. */
    private static double pct(long value, long total) {
        if (total == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(value * 100.0)
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ── one Top Assignees bar ─────────────────────────────────────────────

    private static DashboardOverviewDtos.AssigneeOpenState assignee(OverviewRepository.Assignee row) {
        // Disjoint and overdue-first, per this class's own header.
        long overdue = row.nsOverdue() + row.wipDelayed();
        long notStarted = row.nsTotal() - row.nsOverdue();
        long inProgress = row.wipTotal() + row.blockedOnHold() + row.blockedAwaitingInfo() - row.wipDelayed();
        String scope = "&assigneeId=" + row.userId();

        return new DashboardOverviewDtos.AssigneeOpenState(row.userId(), row.displayName(),
                figure(inProgress, "/tickets?statusCategory=IN_PROGRESS" + scope),
                figure(overdue, "/tickets?isDelayed=true&excludeClosed=true" + scope),
                figure(notStarted, "/tickets?statusCategory=TODO" + scope));
    }

    private static DashboardDtos.Figure figure(long value, String drillDown) {
        return new DashboardDtos.Figure(value, drillDown);
    }
}
