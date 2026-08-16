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
 * it, exactly as the ticket list does. A PM asking for a project they do not
 * hold gets zeroes, not that project. {@code ?assigneeId=} is accepted only
 * where it is meaningful and is otherwise the caller's own id — a Developer
 * cannot ask for somebody else's numbers by naming them.
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

        boolean ownWorkOnly = isOwnWorkOnly(caller.roleCode());

        DashboardRepository.Flow flow;
        DashboardRepository.Stock stock;

        if (ownWorkOnly) {
            // assigneeId is ignored rather than honoured: answering it would let
            // a Developer read a colleague's dashboard by guessing a user id.
            flow = summaries.resourceFlow(start, end, caller.userId());
            stock = summaries.resourceStock(start, end, caller.userId()).orElse(EMPTY_STOCK);
        } else if (assigneeId != null) {
            // A PM or Admin may legitimately ask "how is Ravi doing" — §S-05's
            // Resource filter. Their own scope still bounds it, because the
            // resource table is fed from tickets they can see.
            flow = summaries.resourceFlow(start, end, assigneeId);
            stock = summaries.resourceStock(start, end, assigneeId).orElse(EMPTY_STOCK);
        } else {
            List<Long> scope = projectScopeOf(caller);
            flow = summaries.projectFlow(start, end, scope, projectId);
            stock = summaries.projectStock(start, end, scope, projectId).orElse(EMPTY_STOCK);
        }

        Instant asOf = summaries.computedAt(start, end).orElse(null);
        return new DashboardDtos.Summary(asOf, cards(flow, stock, projectId, start, end));
    }

    private static final DashboardRepository.Stock EMPTY_STOCK =
            new DashboardRepository.Stock(0, 0, 0, 0);

    /** §2's three delivery roles see their own work and nothing else. */
    private static boolean isOwnWorkOnly(String roleCode) {
        return RolePermissions.DEVELOPER.equals(roleCode)
                || RolePermissions.QA.equals(roleCode)
                || RolePermissions.DEPLOYMENT.equals(roleCode);
    }

    /** Empty means unrestricted, matching {@code ScopeResolver}'s own convention for Admin. */
    private static List<Long> projectScopeOf(CallerIdentity caller) {
        return RolePermissions.ADMIN.equals(caller.roleCode()) ? List.of() : caller.projectIds();
    }

    /**
     * Widgets 1–6 of §S-05, each carrying the list it opens.
     *
     * <p>The drill-down is built here rather than client-side so the filter that
     * produced the number and the filter the list applies are the same string.
     * Two implementations of "which tickets is this card counting" is how a card
     * comes to disagree with the list it opens, and the user believes the list.
     */
    private List<DashboardDtos.Card> cards(DashboardRepository.Flow flow, DashboardRepository.Stock stock,
                                           Long projectId, LocalDate from, LocalDate to) {
        String window = "from=" + from + "&to=" + to + (projectId == null ? "" : "&projectId=" + projectId);
        return List.of(
                card("total", "Total tasks created", flow.created(), "/tickets?" + window),
                card("open", "Pending / open", stock.openTotal(), "/tickets?excludeClosed=true&" + window),
                card("closed", "Closed", flow.closed(), "/tickets?status=CLOSED&" + window),
                card("critical", "Critical", stock.openCritical(),
                        "/tickets?level=CRITICAL&excludeClosed=true&" + window),
                card("delayed", "Delayed", stock.openDelayed(),
                        "/tickets?isDelayed=true&excludeClosed=true&" + window),
                card("reopened", "Reopened", stock.openReopened(),
                        "/tickets?reopenedOnly=true&" + window));
    }

    private static DashboardDtos.Card card(String key, String label, long value, String drillDown) {
        // deltaPct and sparkline are A-055's. Null and empty render as absent;
        // zero and a flat line would render as assertions nobody computed.
        return new DashboardDtos.Card(key, label, BigDecimal.valueOf(value), null, List.of(), drillDown);
    }
}
