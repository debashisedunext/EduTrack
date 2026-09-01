package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dashboard Rework Dev 1, PR 6 · {@code GET /dashboard/today}'s seven cards,
 * the Open Issues role split, and the MIS grid — filled in against PR 4's
 * {@code daily_ticket_stats}/{@code resource_daily_stats} counters, which PR 2
 * stubbed this class to answer empty until they existed.
 *
 * <h2>"Today" is a snapshot, not a window</h2>
 *
 * <p>Every other dashboard tab takes a {@code from}/{@code to} range; this one
 * takes none, because every figure here is either <b>stock</b> — what is true
 * right now — or a <b>flow bounded to the UTC civil day</b> (started/finished
 * today). Both read the single most recently summarised day at or before
 * today, exactly the way {@link DashboardRepository}'s stock queries read the
 * latest day in a range rather than summing it — the identical reasoning,
 * applied to a "range" that is always one day wide.
 *
 * <h2>Two tables, decided by {@link DashboardScope}, restated here for a third
 * time — and that repetition is deliberate, not an oversight</h2>
 *
 * <p>{@link DashboardService} and {@link WidgetService} each make this call
 * over the same summary tables for the same documented reason: {@code
 * ScopeResolver} produces a {@code Specification<Ticket>}, and none of these
 * classes may touch {@code tickets}. {@code DashboardScopeIT}-style ITs are
 * what keep a third statement of the rule from drifting from the other two.
 *
 * <h2>Out-of-scope {@code projectId} is refused in words, not a 404</h2>
 *
 * <p>A-077 already settled this for the KPI row and the widgets: the queries
 * are safe either way — scope is ANDed with the request, so a project outside
 * it matches no rows — but "no rows" renders as seven cards reading zero,
 * which is a false measurement rather than an absence. {@link
 * WidgetService#NOT_YOUR_PROJECT} is reused verbatim so this tab, the KPI row
 * and the widgets underneath it cannot answer the same question three
 * different ways.
 */
@Service
class TodayProgressService {

    private final TodayStatsRepository stats;
    private final WorkingHoursService workingHours;
    private final Clock clock;

    @Autowired
    TodayProgressService(TodayStatsRepository stats, WorkingHoursService workingHours) {
        this(stats, workingHours, Clock.systemUTC());
    }

    /** Test seam — "today" cannot be asserted against a clock that only moves forwards. */
    TodayProgressService(TodayStatsRepository stats, WorkingHoursService workingHours, Clock clock) {
        this.stats = stats;
        this.workingHours = workingHours;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    TodayProgressDtos.TodayProgressData today(CallerIdentity caller, Long projectId) {
        DashboardScope scope = DashboardScope.of(caller);
        String variant = scope.ownWorkOnly() ? "OWN_WORK" : "FULL";

        if (!scope.coversProject(projectId)) {
            return new TodayProgressDtos.TodayProgressData(
                    null, variant, WidgetService.NOT_YOUR_PROJECT, List.of(), null, List.of());
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return scope.ownWorkOnly() ? ownWork(caller.userId(), today) : full(scope, projectId, today);
    }

    // ── FULL variant — Admin, PM, Support ────────────────────────────────────

    private TodayProgressDtos.TodayProgressData full(DashboardScope scope, Long projectId, LocalDate today) {
        LocalDate nextWorkingDay = workingHours.nextWorkingDay(today);
        Optional<LocalDate> latest = stats.latestProjectDay(today, scope.projectIds(), projectId);

        if (latest.isEmpty()) {
            return new TodayProgressDtos.TodayProgressData(null, "FULL", null,
                    cards(EMPTY_COUNTERS, today, nextWorkingDay, projectId, null),
                    openIssues(Map.of(), 0, projectId), List.of());
        }

        LocalDate day = latest.get();
        TodayStatsRepository.ProjectDay row = stats.projectDay(day, scope.projectIds(), projectId);
        Map<String, Long> byRole = stats.openByRole(day, scope.projectIds(), projectId);
        List<TodayProgressDtos.AssigneeMisRow> resources = stats.misRows(day, scope.projectIds(), projectId)
                .stream()
                .map(r -> misRow(r, today, nextWorkingDay))
                .toList();
        Instant asOf = stats.computedAt(day, scope.projectIds(), projectId).orElse(null);

        return new TodayProgressDtos.TodayProgressData(
                asOf, "FULL", null,
                cards(counters(row), today, nextWorkingDay, projectId, null),
                openIssues(byRole, row.openTotal(), projectId),
                resources);
    }

    // ── OWN_WORK variant — Developer, QA, Deployment ─────────────────────────

    private TodayProgressDtos.TodayProgressData ownWork(long userId, LocalDate today) {
        LocalDate nextWorkingDay = workingHours.nextWorkingDay(today);
        Optional<LocalDate> latest = stats.latestResourceDay(userId, today);

        if (latest.isEmpty()) {
            return new TodayProgressDtos.TodayProgressData(null, "OWN_WORK", null,
                    cards(EMPTY_COUNTERS, today, nextWorkingDay, null, userId), null, List.of());
        }

        LocalDate day = latest.get();
        TodayStatsRepository.ResourceDay row = stats.resourceDay(day, userId).orElseThrow(() ->
                new IllegalStateException("latestResourceDay named " + day + " for user " + userId
                        + " but resourceDay found no row for it — the two queries disagree about what exists"));

        return new TodayProgressDtos.TodayProgressData(
                row.computedAt(), "OWN_WORK", null,
                cards(counters(row), today, nextWorkingDay, null, userId), null, List.of());
    }

    // ── the fourteen counters, variant-agnostic ──────────────────────────────

    /**
     * The shape {@link #cards} builds from, whichever table supplied it.
     * {@code wipOnTime}/{@code wipNotUpdated}/{@code finishedToday} are
     * deliberately not stored columns — Dev 1's PR 4 note says so directly:
     * computed on read, the same way {@link DashboardService} derives {@code
     * wip_on_time} nowhere near this class but for the identical reason.
     */
    private record Counters(long nsTotal, long nsOverdue, long nsDueToday,
                            long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                            long blockedOnHold, long blockedAwaitingInfo, long pendingReview) {

        long wipOnTime() {
            return wipTotal - wipNearDelay - wipDelayed;
        }

        long wipNotUpdated() {
            return wipTotal - wipUpdatedToday;
        }
    }

    private static final Counters EMPTY_COUNTERS = new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private static Counters counters(TodayStatsRepository.ProjectDay r) {
        return new Counters(r.nsTotal(), r.nsOverdue(), r.nsDueToday(), r.wipTotal(), r.wipUpdatedToday(),
                r.wipNearDelay(), r.wipDelayed(), r.blockedOnHold(), r.blockedAwaitingInfo(), r.pendingReview());
    }

    private static Counters counters(TodayStatsRepository.ResourceDay r) {
        return new Counters(r.nsTotal(), r.nsOverdue(), r.nsDueToday(), r.wipTotal(), r.wipUpdatedToday(),
                r.wipNearDelay(), r.wipDelayed(), r.blockedOnHold(), r.blockedAwaitingInfo(), r.pendingReview());
    }

    // ── the seven cards ───────────────────────────────────────────────────────

    /**
     * @param projectId  the FULL variant's explicit {@code ?projectId=} filter, or null.
     * @param assigneeId the OWN_WORK variant's caller id, carried on every link
     *                   the same way {@link DashboardService#resourceCards}
     *                   carries it — redundant against {@code ScopeResolver},
     *                   which forces {@code assigned_to = me} regardless, but
     *                   stating what the number counts rather than leaving it
     *                   implicit in who is asking.
     */
    private List<TodayProgressDtos.TodaySummaryCard> cards(Counters c, LocalDate today, LocalDate nextWorkingDay,
                                                           Long projectId, Long assigneeId) {
        String scope = scopeSuffix(projectId, assigneeId);
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);

        List<TodayProgressDtos.TodaySummaryCard> cards = new ArrayList<>();

        // 1 · Today's Work — everything not yet closed that is either not
        // started or being worked, in one card. `statuses=` lists all four
        // non-terminal codes at once rather than two separate filters ORed —
        // TicketListSpecs' `statuses` is a plain IN(), and NEW/REOPENED/
        // IN_PROGRESS/REWORK is exactly ns_total's and wip_total's combined
        // population with nothing else mixed in.
        cards.add(new TodayProgressDtos.TodaySummaryCard("todays-work", "Today's Work",
                new DashboardDtos.Figure(c.nsTotal() + c.wipTotal(),
                        url("/tickets?statuses=NEW,REOPENED,IN_PROGRESS,REWORK", scope)),
                List.of(
                        figure("notStarted", "Not started", c.nsTotal(),
                                url("/tickets?statusCategory=TODO", scope)),
                        // No list can express "WIP and not near/at risk" without
                        // also excluding a WIP ticket that carries no due date at
                        // all — dueFrom would drop it, and it belongs here as
                        // on-time. Null rather than an approximate link, per the
                        // contract's own "or null where no list can express it".
                        figure("onTime", "On time", c.wipOnTime(), null),
                        figure("wip", "WIP", c.wipTotal(), url("/tickets?statuses=IN_PROGRESS,REWORK", scope)),
                        // The two overdue populations use different date
                        // boundaries (ns_overdue: before today; wip_delayed:
                        // through today) and different status sets, ORed — not a
                        // single filter combination the list can express.
                        figure("overdue", "Overdue", c.nsOverdue() + c.wipDelayed(), null))));

        // 2 · Overdue — the two ways a ticket can be late; the two figures sum
        // to the card's own total, which is therefore null for the same reason
        // card 1's "overdue" sub-figure is.
        cards.add(new TodayProgressDtos.TodaySummaryCard("overdue", "Overdue",
                new DashboardDtos.Figure(c.nsOverdue() + c.wipDelayed(), null),
                List.of(
                        figure("notStarted", "Not started", c.nsOverdue(),
                                url("/tickets?statusCategory=TODO&dueTo=", yesterday, scope)),
                        figure("wip", "WIP", c.wipDelayed(),
                                url("/tickets?statuses=IN_PROGRESS,REWORK&dueTo=", today, scope)))));

        // 3 · Not Started
        cards.add(new TodayProgressDtos.TodaySummaryCard("not-started", "Not Started",
                new DashboardDtos.Figure(c.nsTotal(), url("/tickets?statusCategory=TODO", scope)),
                List.of(
                        figure("overdueStart", "Overdue start", c.nsOverdue(),
                                url("/tickets?statusCategory=TODO&dueTo=", yesterday, scope)),
                        figure("dueToday", "Due today", c.nsDueToday(),
                                url("/tickets?statusCategory=TODO&dueFrom=", today, "&dueTo=", today, scope)),
                        figure("total", "Total", c.nsTotal(), url("/tickets?statusCategory=TODO", scope)))));

        // 4 · WIP
        cards.add(new TodayProgressDtos.TodaySummaryCard("wip", "WIP",
                new DashboardDtos.Figure(c.wipTotal(), url("/tickets?statuses=IN_PROGRESS,REWORK", scope)),
                List.of(
                        figure("total", "Total", c.wipTotal(), url("/tickets?statuses=IN_PROGRESS,REWORK", scope)),
                        figure("updatedToday", "Updated today", c.wipUpdatedToday(),
                                url("/tickets?statuses=IN_PROGRESS,REWORK&updatedFrom=", today,
                                        "&updatedTo=", today, scope)),
                        // tickets.updated_at is NOT NULL (V20260805_1041), so
                        // "before today" and "not updated today" are the same
                        // population — no NULL-exclusion gap to worry about, the
                        // way the onTime figures above have.
                        figure("notUpdated", "Not updated", c.wipNotUpdated(),
                                url("/tickets?statuses=IN_PROGRESS,REWORK&updatedTo=", yesterday, scope)))));

        // 5 · WIP Breakdown
        cards.add(new TodayProgressDtos.TodaySummaryCard("wip-breakdown", "WIP Breakdown",
                new DashboardDtos.Figure(c.wipTotal(), url("/tickets?statuses=IN_PROGRESS,REWORK", scope)),
                List.of(
                        figure("nearDelay", "Near delay", c.wipNearDelay(),
                                url("/tickets?statuses=IN_PROGRESS,REWORK&dueFrom=", tomorrow,
                                        "&dueTo=", nextWorkingDay, scope)),
                        figure("delayed", "Delayed", c.wipDelayed(),
                                url("/tickets?statuses=IN_PROGRESS,REWORK&dueTo=", today, scope)),
                        figure("onTime", "On time", c.wipOnTime(), null))));

        // 6 · Blocked — `status=` names exactly one code, so the two halves
        // cannot share a single link the way `statuses=` collapses WIP's two
        // codes into one; the card's own total ORs them and stays null.
        cards.add(new TodayProgressDtos.TodaySummaryCard("blocked", "Blocked",
                new DashboardDtos.Figure(c.blockedOnHold() + c.blockedAwaitingInfo(), null),
                List.of(
                        figure("onHold", "On hold", c.blockedOnHold(), url("/tickets?status=ON_HOLD", scope)),
                        figure("awaitingInfo", "Awaiting info", c.blockedAwaitingInfo(),
                                url("/tickets?status=AWAITING_INFO", scope)))));

        // 7 · Pending Review — one combined count, no sub-figures: splitting it
        // would double-count a ticket that is both RESOLVED and sitting in a
        // review stage, per the contract's own note on this card.
        cards.add(new TodayProgressDtos.TodaySummaryCard("pending-review", "Pending Review",
                new DashboardDtos.Figure(c.pendingReview(), url("/tickets?pendingReview=true", scope)),
                List.of()));

        return cards;
    }

    private static TodayProgressDtos.CardFigure figure(String key, String label, long value, String drillDown) {
        return new TodayProgressDtos.CardFigure(key, label, value, drillDown);
    }

    // ── Open Issues, by role ──────────────────────────────────────────────────

    /**
     * DEV / QA / PM / SUP / DEP / Unassigned, in that fixed order regardless of
     * value — the prototype draws the chip row in this order and a chart that
     * reorders itself by count is harder to scan every time it refreshes.
     */
    private static final List<String> CANONICAL_ROLES = List.of("DEVELOPER", "QA", "PM", "SUPPORT", "DEPLOYMENT");

    private static final Map<String, String> ROLE_LABELS = Map.of(
            "DEVELOPER", "DEV",
            "QA", "QA",
            "PM", "PM",
            "SUPPORT", "SUP",
            "DEPLOYMENT", "DEP",
            "UNASSIGNED", "Unassigned");

    private TodayProgressDtos.OpenIssuesByRole openIssues(Map<String, Long> byRole, long openTotal, Long projectId) {
        String scope = scopeSuffix(projectId, null);
        List<TodayProgressDtos.RoleFigure> roles = new ArrayList<>();

        for (String role : CANONICAL_ROLES) {
            // No role-based filter exists on GET /tickets — only a specific
            // person via ?assigneeId=. "Everyone the DEV role currently holds
            // work through" has no list to open, so every named-role chip is
            // null rather than an approximate link.
            roles.add(new TodayProgressDtos.RoleFigure(role, ROLE_LABELS.get(role),
                    byRole.getOrDefault(role, 0L), null));
        }
        // UNASSIGNED is the one chip the list CAN answer exactly.
        roles.add(new TodayProgressDtos.RoleFigure("UNASSIGNED", "Unassigned",
                byRole.getOrDefault("UNASSIGNED", 0L),
                url("/tickets?unassigned=true&excludeClosed=true", scope)));

        // A role the canonical six do not name (an Admin directly holding a
        // ticket is the realistic case) is kept, not dropped — the contract
        // states the chips sum to the total, and silently excluding one would
        // make that stop being true rather than merely incomplete.
        byRole.keySet().stream()
                .filter(role -> !CANONICAL_ROLES.contains(role) && !"UNASSIGNED".equals(role))
                .sorted()
                .forEach(role -> roles.add(new TodayProgressDtos.RoleFigure(role, role, byRole.get(role), null)));

        return new TodayProgressDtos.OpenIssuesByRole(
                new DashboardDtos.Figure(openTotal, url("/tickets?excludeClosed=true", scope)), roles);
    }

    // ── the MIS grid ──────────────────────────────────────────────────────────

    private static TodayProgressDtos.AssigneeMisRow misRow(TodayStatsRepository.MisRow r, LocalDate today,
                                                            LocalDate nextWorkingDay) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate tomorrow = today.plusDays(1);
        String scope = "&assigneeId=" + r.userId();

        long wipOnTime = r.wipTotal() - r.wipNearDelay() - r.wipDelayed();
        long finishedToday = r.finishedEarly() + r.finishedOnTime() + r.finishedLate();

        return new TodayProgressDtos.AssigneeMisRow(
                r.userId(), r.displayName(),
                new DashboardDtos.Figure(r.nsOverdue(),
                        url("/tickets?statusCategory=TODO&dueTo=", yesterday, scope)),
                new DashboardDtos.Figure(r.nsDueToday(),
                        url("/tickets?statusCategory=TODO&dueFrom=", today, "&dueTo=", today, scope)),
                new DashboardDtos.Figure(r.nsTotal(), url("/tickets?statusCategory=TODO", scope)),
                new DashboardDtos.Figure(r.wipTotal(), url("/tickets?statuses=IN_PROGRESS,REWORK", scope)),
                new DashboardDtos.Figure(r.wipUpdatedToday(),
                        url("/tickets?statuses=IN_PROGRESS,REWORK&updatedFrom=", today,
                                "&updatedTo=", today, scope)),
                new DashboardDtos.Figure(r.wipNearDelay(),
                        url("/tickets?statuses=IN_PROGRESS,REWORK&dueFrom=", tomorrow,
                                "&dueTo=", nextWorkingDay, scope)),
                new DashboardDtos.Figure(r.wipDelayed(),
                        url("/tickets?statuses=IN_PROGRESS,REWORK&dueTo=", today, scope)),
                new DashboardDtos.Figure(wipOnTime, null),
                new DashboardDtos.Figure(finishedToday,
                        url("/tickets?finishedFrom=", today, "&finishedTo=", today, scope)),
                // "Finished late" has no matching list predicate: it needs
                // actualCloseDate compared against the cycle's own
                // plannedCloseDate, which no GET /tickets parameter expresses.
                new DashboardDtos.Figure(r.finishedLate(), null));
    }

    // ── shared link-building ──────────────────────────────────────────────────

    /**
     * Concatenates its arguments in order. Deliberately not a two-argument
     * {@code (query, scope)} split: {@code DrillDownContractTest} scans this
     * file's literal source text for quoted fragments that open with a
     * leading {@code ?} or {@code &} before a parameter name, so every fixed
     * key has to sit in its own string literal broken at exactly that point —
     * folding a whole query string (fixed keys and interpolated dates
     * together) into one Java expression before it becomes a literal would
     * make the keys invisible to that scan, and the test would pass by
     * finding nothing to check rather than by checking something.
     *
     * <p>(Deliberately not spelling the two literal shapes out here with
     * their own quote marks — this comment is source text too, and the same
     * scan would misread an example for a real, undeclared parameter.)
     */
    private static String url(Object... parts) {
        StringBuilder built = new StringBuilder();
        for (Object part : parts) {
            built.append(part);
        }
        return built.toString();
    }

    private static String scopeSuffix(Long projectId, Long assigneeId) {
        if (assigneeId != null) {
            return "&assigneeId=" + assigneeId;
        }
        return projectId == null ? "" : "&projectId=" + projectId;
    }
}
