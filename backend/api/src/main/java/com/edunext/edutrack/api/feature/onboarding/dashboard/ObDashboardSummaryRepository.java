package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * B-121 · reads {@code ob_dashboard_summary} for the OB-02 board.
 *
 * <p>Never {@code ob_journeys} and never {@code ob_journey_steps} — CLAUDE.md's
 * "never a live {@code COUNT(*)} for dashboards" applies here exactly as it
 * does to {@code DashboardRepository} and {@code WidgetRepository} one module
 * over, and A-108 built this table so that it could.
 *
 * <h2>🔴 Summing the product rows is exact for four cards and over-counts three</h2>
 *
 * <p>This is the one thing to know before changing anything in this class.
 *
 * <p>The table's grain is {@code (stat_date, product_id)}. A journey belongs to
 * exactly one product and a step belongs to exactly one journey, so
 * {@code journeys_*}, {@code steps_*} and the three {@code rag_*} columns
 * <b>partition</b> across the product rows and adding them up is exact.
 *
 * <p>{@code clients_overdue}, {@code clients_live} and {@code clients_escalated}
 * do not. B-120 writes each as {@code COUNT(DISTINCT ob_client_id)}
 * <em>within</em> a product — which is what makes the per-product figure right,
 * and A-108 asked for it in those words ("a client late on several services" is
 * one Overdue Client). A client who bought ERP and Biometric and is late on
 * both therefore contributes 1 to each row, and {@code SUM} over the rows says
 * 2. Plan §4 makes multi-product the normal case ("multi-select products → N
 * locked journeys"), so this is not a corner.
 *
 * <p><b>The all-products figure for those three is not derivable from this
 * table at any cost.</b> A distinct count cannot be reconstructed from
 * per-group distinct counts; the information is gone at write time. The three
 * ways out, and why this class takes the third:
 *
 * <ol>
 *   <li>Count the journey tables live when no product is selected. Forbidden,
 *       and forbidden for a reason that bites hardest on exactly this query —
 *       {@code overdue-clients} needs a row-by-row {@code EXISTS} over
 *       {@code ob_journey_steps}, the one table here that grows without bound.</li>
 *   <li>Store an org-wide row. There is nowhere to put it: the primary key is
 *       {@code (stat_date, product_id)} and {@code product_id} carries a
 *       foreign key to {@code ob_products}, so a sentinel needs a Stream A
 *       migration — either dropping that key or adding a client-keyed
 *       {@code ob_client_daily_stats} beside the two A-108 already created,
 *       which is the shape the ticketing side landed on with
 *       {@code client_daily_stats}.</li>
 *   <li>Sum, and let the caller know which figures are upper bounds. Which is
 *       what happens here, because the contract specifies the sum in as many
 *       words ("a sum over rows, not a separate stored total, so the two
 *       cannot drift") and because the alternative available <em>today</em> is
 *       to show nothing on three of seven cards.</li>
 * </ol>
 *
 * <p>{@link #isExact(Long, ObDashboardCardKey)} is the honest half of that
 * third option and is what {@link ObDashboardService} reads: with a
 * {@code productId} the figure is exact for all seven cards, because one row is
 * selected and nothing is added up. The over-count exists only on the
 * all-products board, and only for the three client-counted cards.
 *
 * <p>Recorded in the backlog as the follow-up it is, rather than left to be
 * found as "Live says 12 and the client list has 7".
 */
@Repository
class ObDashboardSummaryRepository {

    /**
     * Every card's arithmetic, in one place and keyed by the card it answers.
     *
     * <p>Written as SQL fragments rather than as seven columns of a fixed
     * SELECT so that the card vocabulary and the expression answering it sit on
     * one line each. {@link ObDashboardCardKey} decides the board's order; this
     * decides what each card means, and the two are matched by key rather than
     * by position — so a card added to the contract and to the enum without an
     * expression here fails {@code ObDashboardSummaryRepositoryTest} rather
     * than silently reading zero.
     *
     * <p>{@code ongoing-projects} is locked + held + running, which is
     * {@code journeys_total - journeys_completed}. Written as the sum of the
     * three open buckets rather than as the subtraction: A-108's four buckets
     * partition the total by construction, and naming the three that are open
     * says what the card means where the subtraction says only what it is not.
     *
     * <p>{@code at-risk} is amber + red and deliberately excludes
     * {@code journeys_locked}. A journey whose gate has not cleared has no
     * colour at all (A-108), and folding it in would put the whole of a fresh
     * intake on this card — the mirror of the mistake A-108 warns about when it
     * says a refresh must not fold a locked journey into green.
     */
    private static final Map<ObDashboardCardKey, String> EXPRESSIONS =
            new EnumMap<>(Map.of(
                    ObDashboardCardKey.ONGOING_PROJECTS,
                    "journeys_locked + journeys_held + journeys_open_running",
                    ObDashboardCardKey.THIS_WEEKS_DEADLINES, "steps_due_this_week",
                    ObDashboardCardKey.TODAYS_DELIVERY, "steps_due_today",
                    ObDashboardCardKey.OVERDUE_CLIENTS, "clients_overdue",
                    ObDashboardCardKey.LIVE, "clients_live",
                    ObDashboardCardKey.AT_RISK, "rag_amber + rag_red",
                    ObDashboardCardKey.CLIENT_ESCALATIONS, "clients_escalated"));

    private final JdbcClient jdbc;

    ObDashboardSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One day of the board.
     *
     * @param statDate   the day these figures describe
     * @param computedAt the latest {@code computed_at} across the rows folded
     *                   into it — how stale the board is. {@code MAX} rather
     *                   than any single row's: B-120 writes stock and flow in
     *                   separate statements inside one transaction, so the
     *                   newest is the one that says when the day was last
     *                   touched.
     * @param counts     every card, always all seven, zero where the day has no
     *                   row to contribute one.
     */
    record Rollup(LocalDate statDate, Instant computedAt, Map<ObDashboardCardKey, Long> counts) {
    }

    /**
     * The two most recent days the table holds, newest first.
     *
     * <p><b>The two most recent stored days, not today and yesterday.</b> A
     * deployment whose worker was down on Tuesday has no Tuesday row, and
     * asking for {@code latest - 1 day} would find nothing and report every
     * delta as null — which reads on screen as "we have never had a previous
     * day" rather than as "Tuesday is missing". Reading the days that exist
     * makes Wednesday's delta a comparison against Monday, which is the honest
     * available answer and is what a sparse series means everywhere else here.
     *
     * <p>Filtered by product when one is asked for, so the delta compares like
     * with like: a product whose first journey landed yesterday has one day of
     * history even where the table has thirty.
     *
     * @return zero, one or two days. Empty means B-120 has never run — a state
     *         A-108 makes reachable on purpose and which the response reports
     *         as a null {@code computedAt}.
     */
    List<LocalDate> recentDays(Long productId) {
        return jdbc.sql("""
                SELECT DISTINCT stat_date
                  FROM ob_dashboard_summary
                 WHERE (:productId IS NULL OR product_id = :productId)
                 ORDER BY stat_date DESC
                 LIMIT 2
                """)
                .param("productId", productId)
                .query(LocalDate.class)
                .list();
    }

    /**
     * The seven counters for one day.
     *
     * <p>{@code COALESCE(SUM(...), 0)} on every card: a product row that has
     * never been touched by the flow pass contributes NULL to that column's sum
     * in MySQL, and a null count would render as a blank tile rather than as a
     * nought. A-108 defaults every column to 0 at write time, so this is belt
     * and braces — kept because the alternative failure is silent and it costs
     * nothing.
     *
     * <p>See the class note before reading the three client-counted cards out
     * of this: with {@code productId} null they are upper bounds.
     *
     * @return empty when the day has no row for this product. A bare aggregate
     *         over no rows still returns one row with every column NULL, and
     *         that is "no data", not a zeroed board — a zero is a claim where
     *         an absent row is silence, which is A-108's own reason for
     *         clamping the refresh window rather than back-filling zeroes.
     */
    Optional<Rollup> rollup(LocalDate statDate, Long productId) {
        String projections = EXPRESSIONS.entrySet().stream()
                .map(entry -> "COALESCE(SUM(" + entry.getValue() + "), 0) AS "
                        + columnAlias(entry.getKey()))
                .reduce((left, right) -> left + ",\n                       " + right)
                .orElseThrow();

        String sql = """
                SELECT MAX(computed_at) AS computed_at,
                       %s
                  FROM ob_dashboard_summary
                 WHERE stat_date = :statDate
                   AND (:productId IS NULL OR product_id = :productId)
                """.formatted(projections);

        return jdbc.sql(sql)
                .param("statDate", statDate)
                .param("productId", productId)
                .query((rs, rowNum) -> {
                    Timestamp computed = rs.getTimestamp("computed_at");
                    if (computed == null) {
                        return null;
                    }
                    Map<ObDashboardCardKey, Long> counts = new EnumMap<>(ObDashboardCardKey.class);
                    for (ObDashboardCardKey key : ObDashboardCardKey.values()) {
                        counts.put(key, rs.getLong(columnAlias(key)));
                    }
                    return new Rollup(statDate, computed.toInstant(), counts);
                })
                .optional()
                .filter(Objects::nonNull);
    }

    /**
     * Whether this card's figure is the real number rather than an upper bound.
     *
     * <p>False only for the three client-counted cards on the all-products
     * board. See the class note; this is the accessor that keeps the
     * classification out of {@link ObDashboardService}'s prose and in one
     * testable predicate.
     */
    static boolean isExact(Long productId, ObDashboardCardKey key) {
        return productId != null || !key.isClientCounted();
    }

    /**
     * Every card the SQL above projects, so a test can assert the map covers
     * the enum rather than trusting that it does.
     */
    static Map<ObDashboardCardKey, String> expressions() {
        return Map.copyOf(EXPRESSIONS);
    }

    /** {@code ongoing-projects} → {@code c_ongoing_projects}. Derived from the enum, never from input. */
    private static String columnAlias(ObDashboardCardKey key) {
        return "c_" + key.wireName().replace('-', '_');
    }
}
