package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads {@code ob_client_daily_stats} — the prerequisite half of OB-02's two
 * deadline cards.
 *
 * <h2>Why a second table is added to a card rather than a second column</h2>
 *
 * <p>Plan §9 counts "all client tasks — services <em>and</em> prerequisites —
 * due Mon–Sun", and {@link ObDashboardCardItemsRepository} has always obeyed
 * it: its drill-over unions {@code ob_journey_steps} with
 * {@code ob_client_prereq_tasks}. The <em>card</em> above the drill-over read
 * {@code ob_dashboard_summary.steps_due_this_week} alone, which B-120 fills
 * from the journey tables only — so a board with seventeen prerequisite tasks
 * due this week and no service due showed <b>0</b> beside a panel listing all
 * seventeen. This class is the missing half.
 *
 * <p>It could not be a column on {@code ob_dashboard_summary}: that table is
 * keyed {@code (stat_date, product_id)} and a prerequisite checklist belongs to
 * a <em>client</em>, gating every journey they bought rather than any one of
 * them — and a client can hold an open checklist and no journey at all, which
 * no product-keyed row can represent. The migration
 * {@code V20260914_2245__ob_client_daily_stats.sql} works through both
 * attributions that were available and why each is wrong.
 *
 * <h2>These figures are exact, filtered and unfiltered alike</h2>
 *
 * <p>Unlike the three client-counted cards {@link ObDashboardSummaryRepository}
 * spends its class note on, nothing here is an upper bound. A prerequisite task
 * belongs to exactly one client, so the rows partition the population and the
 * all-clients figure is a plain {@code SUM}. Selecting a product filters the
 * clients by an {@code EXISTS} over their journeys — <b>the same predicate the
 * drill-over applies to its PREREQUISITE branch</b>, deliberately, so that the
 * card and the list it opens agree under a product filter as well as without
 * one. A client who bought two products has their checklist counted once on the
 * unfiltered board and shown under both products when one is selected, which is
 * what a gate blocking both of them means.
 *
 * <h2>The scope predicate is applied here too</h2>
 *
 * <p>{@link ObDashboardScope#clientPredicate} narrows OB_SALES to clients they
 * created and OB_STEP_OWNER to clients whose journeys carry their steps. The
 * drill-over already applies it to its prerequisite rows; applying the same
 * text here keeps a narrowed caller's card and list in step for the same reason
 * the unrestricted one is. It is a filter over {@code ob_clients}, not a count
 * — every figure still comes from the pre-aggregate, which is what CLAUDE.md's
 * no-live-{@code COUNT(*)} rule is about.
 */
@Repository
class ObClientPrereqStatsRepository {

    /**
     * The two cards this table contributes to, and the column behind each.
     *
     * <p>Keyed by card for {@link ObDashboardSummaryRepository}'s reason: the
     * card vocabulary and the arithmetic answering it sit on one line each, and
     * a card added to the enum without an entry here is absent rather than
     * silently zero — {@code ObClientPrereqStatsRepositoryTest} asserts the two
     * halves name the same cards.
     *
     * <p>{@code prereq_tasks_overdue} is deliberately not mapped: Overdue
     * Clients counts <em>clients</em> with an overdue service and its own
     * drill-over excludes prerequisites entirely, so the card and the list
     * already agree and adding a prerequisite arm to one of them alone would
     * break that.
     */
    private static final Map<ObDashboardCardKey, String> COLUMNS =
            new EnumMap<>(Map.of(
                    ObDashboardCardKey.THIS_WEEKS_DEADLINES, "prereq_tasks_due_this_week",
                    ObDashboardCardKey.TODAYS_DELIVERY, "prereq_tasks_due_today"));

    private final JdbcClient jdbc;

    ObClientPrereqStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One day's prerequisite contribution to each card it touches.
     *
     * <p>Always both keys, zero where the day has no client row to contribute
     * one — a zero here is added to a services figure that is itself a real
     * number, so unlike {@link ObDashboardSummaryRepository#rollup} there is no
     * "the board has never been computed" claim for this to confuse. That
     * question is settled by the summary table before this is called.
     *
     * @param statDate  the day, as chosen from {@code ob_dashboard_summary}'s
     *                  stored days so that both halves of a card describe the
     *                  same moment
     * @param productId one product's clients, or null for every client the
     *                  caller can see
     * @param scope     the caller's row scope; {@code 1 = 1} for the three
     *                  unrestricted module roles
     */
    Map<ObDashboardCardKey, Long> contribution(LocalDate statDate, Long productId, ObDashboardScope scope) {
        String projections = COLUMNS.entrySet().stream()
                .map(entry -> "COALESCE(SUM(s." + entry.getValue() + "), 0) AS " + alias(entry.getKey()))
                .reduce((left, right) -> left + ",\n                       " + right)
                .orElseThrow();

        String sql = """
                SELECT %s
                  FROM ob_client_daily_stats s
                  JOIN ob_clients c ON c.id = s.ob_client_id
                 WHERE s.stat_date = :statDate
                   AND (:productId IS NULL OR EXISTS (
                         SELECT 1 FROM ob_journeys j
                          WHERE j.ob_client_id = s.ob_client_id
                            AND j.product_id = :productId
                            AND j.archived_at IS NULL))
                   AND (%s)
                """.formatted(projections, scope.clientPredicate("c"));

        var spec = jdbc.sql(sql)
                .param("statDate", statDate)
                .param("productId", productId);
        if (!scope.unrestricted()) {
            spec = spec.param(ObDashboardScope.USER_PARAM, scope.userId());
        }

        return spec.query((rs, rowNum) -> {
            Map<ObDashboardCardKey, Long> counts = new EnumMap<>(ObDashboardCardKey.class);
            for (ObDashboardCardKey key : COLUMNS.keySet()) {
                counts.put(key, rs.getLong(alias(key)));
            }
            return counts;
        }).optional().orElseGet(ObClientPrereqStatsRepository::none);
    }

    /** Every card this table contributes to, so a test can assert the mapping rather than trust it. */
    static Map<ObDashboardCardKey, String> columns() {
        return Map.copyOf(COLUMNS);
    }

    /** Zero for each contributing card — what an aggregate over no rows means here. */
    static Map<ObDashboardCardKey, Long> none() {
        Map<ObDashboardCardKey, Long> counts = new EnumMap<>(ObDashboardCardKey.class);
        COLUMNS.keySet().forEach(key -> counts.put(key, 0L));
        return counts;
    }

    /** {@code this-weeks-deadlines} → {@code p_this_weeks_deadlines}. Derived from the enum, never from input. */
    private static String alias(ObDashboardCardKey key) {
        return "p_" + key.wireName().replace('-', '_');
    }
}
