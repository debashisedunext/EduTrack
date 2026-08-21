package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A-054 · the dashboard shell's data, role-aware.
 *
 * <h2>Role decides which table, not just which rows</h2>
 *
 * <p>This is the part of "role-aware" that is easy to under-read. A-050 built
 * two summary tables and they are keyed differently on purpose:
 *
 * <ul>
 *   <li><b>Admin</b> — {@code daily_ticket_stats}, unrestricted.</li>
 *   <li><b>PM, Support</b> — {@code daily_ticket_stats}, narrowed to their
 *       {@code projectIds}. Same table, fewer rows.</li>
 *   <li><b>Developer, QA, Deployment</b> — {@code resource_daily_stats}, keyed
 *       by their own user id. <b>A different table entirely</b>, because their
 *       scope is {@code assigned_to = me} and a table keyed by project cannot
 *       express that however it is filtered. Narrowing the project table to the
 *       projects they happen to work in would show them their colleagues'
 *       tickets — the exact leak {@code ScopeResolver} prevents on the list.</li>
 * </ul>
 *
 * <p>{@code ScopeResolver} itself cannot be reused here: it produces a JPA
 * {@code Specification<Ticket>}, and these reads deliberately never touch
 * {@code tickets}. So the rule is restated over the summary tables — which is a
 * second implementation and worth saying out loud. It is kept honest by reading
 * the <em>same</em> role vocabulary from {@link RolePermissions} and by
 * {@code DashboardScopeIT}, which asserts a Developer's figures never move when
 * a colleague's tickets change.
 *
 * <h2>The caller's filters narrow; they never widen</h2>
 *
 * <p>{@code ?projectId=} is ANDed with the caller's scope rather than replacing
 * it, exactly as the ticket list does. {@code ?assigneeId=} is accepted only
 * where it is meaningful and is otherwise the caller's own id — a Developer
 * cannot ask for somebody else's numbers by naming them.
 *
 * <p><b>A-077 · a PM asking for a project they do not hold is told so, and this
 * paragraph used to say they "get zeroes".</b> They did, and the AND above is
 * why that was never a leak — but zero is a measurement, and six cards reading
 * 0 state that a project holding a hundred open tickets has none. The refusal
 * is now explicit, in {@link WidgetService#NOT_YOUR_PROJECT}, matching the
 * charts beneath the cards so the two cannot answer differently. The old
 * wording is quoted rather than deleted because {@code DashboardScopeIT} pinned
 * it as an expectation, and reading only the new sentence would leave whoever
 * finds that test's history unsure which behaviour was intended.
 */
@Service
class DashboardService {

    /** §S-05's default window when the caller names none. Thirty days is the "Daily Task Status" chart's own range. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final DashboardRepository summaries;
    private final Clock clock;

    /**
     * {@code api} publishes no {@code Clock} bean — {@code worker} does, this
     * module does not — so one is supplied here rather than injected.
     * {@code TicketCodeGenerator} set this pattern and the reason is worth
     * repeating: asking the container for a {@code Clock} that nothing declares
     * does not fail at the call site, it fails the <em>whole application
     * context</em>, and every {@code @SpringBootTest} in the module goes red at
     * once with an error that names the context rather than the cause.
     */
    @Autowired
    DashboardService(DashboardRepository summaries) {
        this(summaries, Clock.systemUTC());
    }

    /** Test seam — a default window cannot be asserted against a clock that only moves forwards. */
    DashboardService(DashboardRepository summaries, Clock clock) {
        this.summaries = summaries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    DashboardDtos.Summary summary(CallerIdentity caller, Long projectId, LocalDate from, LocalDate to,
                                  Long assigneeId) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS - 1L);

        // A-056 · the role decision moved to DashboardScope so WidgetService
        // reads the same one. Stated twice, it drifts — and the way it drifts
        // is a Developer seeing their own cards above a chart of everybody's.
        DashboardScope scope = DashboardScope.of(caller);

        // A-077 · a project the caller is not a member of. The queries below are
        // safe without this — they AND the scope with the requested project and
        // match nothing — but "nothing" becomes six cards reading 0, and a KPI
        // row of zeroes is a measurement, not an absence. Refused in the same
        // words WidgetService uses so the cards and the charts beneath them give
        // one answer rather than two.
        //
        // Ahead of the resource branch deliberately: ?assigneeId= with an
        // out-of-scope ?projectId= must not slip past on the resource table,
        // which is keyed by person and carries no project column to bound it.
        if (!scope.coversProject(projectId)) {
            return new DashboardDtos.Summary(null, List.of(), WidgetService.NOT_YOUR_PROJECT);
        }

        // A-062 · the one place "whose rows, if anybody's" is decided, and it is
        // DashboardScope's own method rather than a third statement of the rule.
        // Non-null means resource_daily_stats answers this request:
        //
        //   - a delivery role always gets their own id, and ?assigneeId= is
        //     ignored rather than honoured — answering it would let a Developer
        //     read a colleague's dashboard by guessing a user id;
        //   - a PM or Admin may legitimately ask "how is Ravi doing" (§S-05's
        //     Resource filter), and their own scope still bounds it, because the
        //     resource table is fed from tickets they can see.
        Long subject = scope.resourceSubject(assigneeId);

        DashboardRepository.Flow flow;
        DashboardRepository.Stock stock;

        if (subject != null) {
            flow = summaries.resourceFlow(start, end, subject);
            stock = summaries.resourceStock(start, end, subject).orElse(EMPTY_STOCK);
        } else {
            flow = summaries.projectFlow(start, end, scope.projectIds(), projectId);
            stock = summaries.projectStock(start, end, scope.projectIds(), projectId).orElse(EMPTY_STOCK);
        }

        // A-055 · the preceding window of equal length, for deltaPct. Compared
        // like against like: a 7-day window is compared with the 7 days before
        // it, never with "last month", or a Monday-to-Friday view would read as
        // a collapse every time it was opened on a Saturday.
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate priorEnd = start.minusDays(1);
        LocalDate priorStart = priorEnd.minusDays(span - 1);

        DashboardRepository.Flow priorFlow;
        DashboardRepository.Stock priorStock;
        List<DashboardRepository.Day> series;

        if (subject != null) {
            priorFlow = summaries.resourceFlow(priorStart, priorEnd, subject);
            priorStock = summaries.resourceStock(priorStart, priorEnd, subject).orElse(EMPTY_STOCK);
            series = summaries.resourceSeries(start, end, subject);
        } else {
            priorFlow = summaries.projectFlow(priorStart, priorEnd, scope.projectIds(), projectId);
            priorStock = summaries.projectStock(priorStart, priorEnd, scope.projectIds(), projectId)
                    .orElse(EMPTY_STOCK);
            series = summaries.projectSeries(start, end, scope.projectIds(), projectId);
        }

        Instant asOf = summaries.computedAt(start, end).orElse(null);

        // A-062 · which card set, decided by **which table answered** rather
        // than by the role. Those are the same thing for a delivery role, and
        // keying on the table deliberately also catches the case where they are
        // not: a PM who picks a resource in §S-05's filter is reading
        // resource_daily_stats too, and was getting the project card set over
        // it — a "Total tasks created" that could only ever read 0, because
        // creation is not a fact about an assignee. One decision, made where the
        // table is chosen, rather than a second role test able to disagree with
        // the branch above.
        if (subject != null) {
            return new DashboardDtos.Summary(asOf,
                    resourceCards(flow, stock, priorFlow, priorStock, series, subject, start, end), null);
        }
        return new DashboardDtos.Summary(asOf,
                cards(flow, stock, priorFlow, priorStock, series, projectId, start, end), null);
    }

    /**
     * A-055 · the change against the preceding window, as a percentage.
     *
     * <p>Null when the previous window is zero, not "+100%". A team that closed
     * nothing last week and eleven tickets this week has not improved by a
     * hundred per cent — the comparison has no denominator, and inventing one
     * puts a confident green arrow on a number that means nothing. The card
     * renders no delta at all instead, which is the honest rendering of "there
     * is nothing to compare with".
     */
    private static BigDecimal deltaPct(long now, long before) {
        if (before == 0) {
            return null;
        }
        return BigDecimal.valueOf(now - before)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before), 1, RoundingMode.HALF_UP);
    }

    /**
     * A-055 · one point per day in the window, gaps filled by carrying forward.
     *
     * <p>A day A-051 has not computed is <em>absent</em> from the series, not
     * zero. Plotting the absence as zero draws a cliff to the axis and back —
     * on a weekend, or after any outage — and a sparkline's whole job is shape.
     * Flow metrics fill with zero because no rows genuinely means nothing
     * happened; stock carries the last known value forward, because "how many
     * are open" does not become zero simply for not being recomputed.
     */
    private static List<BigDecimal> sparkline(List<DashboardRepository.Day> series,
                                              java.util.function.ToLongFunction<DashboardRepository.Day> metric,
                                              boolean isStock) {
        List<BigDecimal> points = new ArrayList<>(series.size());
        long carried = 0;
        for (DashboardRepository.Day day : series) {
            long value = metric.applyAsLong(day);
            carried = value;
            points.add(BigDecimal.valueOf(value));
        }
        if (points.isEmpty() && isStock) {
            points.add(BigDecimal.valueOf(carried));
        }
        return List.copyOf(points);
    }

    private static final DashboardRepository.Stock EMPTY_STOCK =
            new DashboardRepository.Stock(0, 0, 0, 0);

    /**
     * Widgets 1–6 of §S-05, each carrying the list it opens.
     *
     * <p>The drill-down is built here rather than client-side so the filter that
     * produced the number and the filter the list applies are the same string.
     * Two implementations of "which tickets is this card counting" is how a card
     * comes to disagree with the list it opens, and the user believes the list.
     */
    private List<DashboardDtos.Card> cards(DashboardRepository.Flow flow, DashboardRepository.Stock stock,
                                           DashboardRepository.Flow priorFlow,
                                           DashboardRepository.Stock priorStock,
                                           List<DashboardRepository.Day> series,
                                           Long projectId, LocalDate from, LocalDate to) {
        // A-060 · `reportedFrom`/`reportedTo`, not the bare `from`/`to` this
        // emitted from A-055 until now. The list never had a reported-date
        // window, so every card here opened the right filter over *all time* —
        // a discrepancy invisible from the dashboard, because the card's own
        // figure was right and only the list behind it was wider.
        String window = "reportedFrom=" + from + "&reportedTo=" + to
                + (projectId == null ? "" : "&projectId=" + projectId);
        return List.of(
                card("total", "Total tasks created", flow.created(), priorFlow.created(),
                        sparkline(series, DashboardRepository.Day::created, false),
                        "/tickets?" + window),
                card("open", "Pending / open", stock.openTotal(), priorStock.openTotal(),
                        sparkline(series, DashboardRepository.Day::openTotal, true),
                        "/tickets?excludeClosed=true&" + window),
                card("closed", "Closed", flow.closed(), priorFlow.closed(),
                        sparkline(series, DashboardRepository.Day::closed, false),
                        "/tickets?status=CLOSED&" + window),
                card("critical", "Critical", stock.openCritical(), priorStock.openCritical(),
                        sparkline(series, DashboardRepository.Day::openCritical, true),
                        "/tickets?level=CRITICAL&excludeClosed=true&" + window),
                card("delayed", "Delayed", stock.openDelayed(), priorStock.openDelayed(),
                        sparkline(series, DashboardRepository.Day::openDelayed, true),
                        "/tickets?isDelayed=true&excludeClosed=true&" + window),
                card("reopened", "Reopened", stock.openReopened(), priorStock.openReopened(),
                        sparkline(series, DashboardRepository.Day::openReopened, true),
                        "/tickets?reopenedOnly=true&" + window));
    }

    /**
     * A-062 · §S-05's developer variant — the same six-card row, for one person.
     *
     * <h2>Two of the blueprint's six are replaced, not dropped quietly</h2>
     *
     * <p>§S-05 says the developer dashboard shows "widgets 1–6 scoped to
     * {@code assignee = me}, plus My due today / this week". Two of those six
     * cannot be scoped that way, and the reason is not a missing column:
     *
     * <ul>
     *   <li><b>Total tasks created</b> — a ticket is raised by a <em>reporter</em>.
     *       There is no sense in which an assignee created it, which is why
     *       {@link DashboardRepository#resourceFlow} returns zero rather than
     *       borrowing the project figure.</li>
     *   <li><b>Reopened</b> — reopening is done by a manager on a ticket, and
     *       {@code resource_daily_stats} carries no column for it.</li>
     * </ul>
     *
     * <p>Both were already answering <b>0</b> for every delivery role, on every
     * window, for ever. That is the failure A-056 argued against for the charts
     * and it is worse on a KPI card: a chart with no series says "nothing to
     * show", while a card reading a confident <b>0</b> under "Total tasks
     * created" is a measurement, and a wrong one. So the two are replaced by the
     * two figures §S-05 asks for in the same sentence — due today and due this
     * week — and the row stays six cards wide.
     *
     * <p>This is a deviation from a literal reading of §S-05 and it is recorded
     * in the backlog entry as one. The alternative readings were: show two
     * permanent zeroes, or show four cards and put the due figures somewhere
     * else on the screen. The first is dishonest; the second splits one row of
     * six into two places for no gain.
     *
     * <h2>Every link carries {@code assigneeId}, though the guard would anyway</h2>
     *
     * <p>{@code ScopeResolver} already forces {@code assigned_to = me} for a
     * delivery role, so the parameter is redundant for them — and it is not
     * redundant for the PM reading one resource through §S-05's filter, who is
     * served by this same card set. One form of link for both, and the URL then
     * states what the number counts instead of relying on who is holding it.
     *
     * @param subject whose rows these are: the caller for a delivery role, the
     *                filtered resource for a PM or Admin.
     */
    private List<DashboardDtos.Card> resourceCards(DashboardRepository.Flow flow,
                                                   DashboardRepository.Stock stock,
                                                   DashboardRepository.Flow priorFlow,
                                                   DashboardRepository.Stock priorStock,
                                                   List<DashboardRepository.Day> series,
                                                   long subject, LocalDate from, LocalDate to) {
        String mine = "/tickets?assigneeId=" + subject;

        // The stock links below carry no reported-date window, unlike the
        // project cards. A person's open count is whatever was true at the end
        // of the last summarised day regardless of when those tickets were
        // raised, so narrowing the list to tickets *reported* in the window
        // would open four rows under a card reading nine. The project cards can
        // carry it because a project's figures are summed across the window.
        //
        // The day the due figures were actually measured for. Not `to`, and not
        // today: a card that says "due today" beside a figure computed for last
        // Friday is two different claims, and the drill-down it opens has to
        // agree with the figure or the list contradicts the card. Falls back to
        // `to` only when nothing has been summarised for this person at all, in
        // which case the figures are zero and AsOfNotice is already saying so.
        LocalDate measured = summaries.resourceLatestDay(from, to, subject).orElse(to);
        DashboardRepository.Due due = summaries.resourceDue(from, to, subject).orElse(EMPTY_DUE);

        return List.of(
                card("open", "Assigned to me, open", stock.openTotal(), priorStock.openTotal(),
                        sparkline(series, DashboardRepository.Day::openTotal, true),
                        mine + "&excludeClosed=true"),
                card("closed", "Closed", flow.closed(), priorFlow.closed(),
                        sparkline(series, DashboardRepository.Day::closed, false),
                        mine + "&status=CLOSED&closedFrom=" + from + "&closedTo=" + to),
                card("critical", "Critical", stock.openCritical(), priorStock.openCritical(),
                        sparkline(series, DashboardRepository.Day::openCritical, true),
                        mine + "&level=CRITICAL&excludeClosed=true"),
                card("delayed", "Delayed", stock.openDelayed(), priorStock.openDelayed(),
                        sparkline(series, DashboardRepository.Day::openDelayed, true),
                        mine + "&isDelayed=true&excludeClosed=true"),
                // No delta and no sparkline on the two due cards, and neither is
                // an omission. `resource_daily_stats` records due counts per
                // day, so a series could be drawn — but "how much was due on
                // each of the last thirty days" is a different quantity from
                // "how much is due today", and a sparkline under a card is read
                // as that card's own history. The delta would be worse: it would
                // compare today's due count with the due count of a day a month
                // ago, and render the difference as an arrow that looks like
                // progress.
                dueCard("dueToday", "Due today", due.dueToday(),
                        mine + "&excludeClosed=true&dueFrom=" + measured + "&dueTo=" + measured),
                dueCard("dueThisWeek", "Due in the next 7 days", due.dueNext7(),
                        mine + "&excludeClosed=true&dueFrom=" + measured
                                + "&dueTo=" + measured.plusDays(6)));
    }

    private static final DashboardRepository.Due EMPTY_DUE = new DashboardRepository.Due(0, 0);

    private static DashboardDtos.Card card(String key, String label, long value, long prior,
                                           List<BigDecimal> sparkline, String drillDown) {
        return new DashboardDtos.Card(key, label, BigDecimal.valueOf(value),
                deltaPct(value, prior), sparkline, drillDown);
    }

    /** A figure with nothing meaningful to compare against — see {@link #resourceCards}. */
    private static DashboardDtos.Card dueCard(String key, String label, long value, String drillDown) {
        return new DashboardDtos.Card(key, label, BigDecimal.valueOf(value),
                null, List.of(), drillDown);
    }
}
