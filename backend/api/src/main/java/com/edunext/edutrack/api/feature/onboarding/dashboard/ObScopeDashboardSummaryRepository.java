package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads {@code ob_scope_dashboard_summary} — the OB-02 board for a caller whose
 * scope the org-wide table cannot express.
 *
 * <p>{@link ObDashboardSummaryRepository} with one more key column, and it
 * exists because that table's grain is {@code (stat_date, product_id)} while
 * A-112 narrows OB_SALES to "journeys whose client they created" and
 * OB_STEP_OWNER to "journeys containing their steps". B-121 answered both with
 * seven {@code unavailableReason} sentences and recorded the fix as a Stream A
 * migration; this is the read half of that fix.
 *
 * <h2>The card arithmetic is not restated here</h2>
 *
 * <p>{@link ObDashboardSummaryRepository#expressions()} is the source of what
 * each card means, and this class projects those same fragments over its own
 * table. The migration matches the parent's column names precisely so that it
 * can. That is the whole reason this is a thin second reader rather than a
 * second definition: a Step Owner comparing their At Risk against their
 * manager's is comparing one arithmetic over a smaller set, and two copies of
 * "amber + red" is how those two numbers come to mean different things without
 * anyone editing either screen.
 *
 * <h2>Three states, and they are three different sentences</h2>
 *
 * <p>The service distinguishes them, so this class has to make them
 * distinguishable:
 *
 * <ul>
 *   <li><b>The table has no days at all</b> — {@link #recentDays} is empty. The
 *       refresh has never run. Deliberately <em>not</em> filtered by caller, so
 *       that "the job has never run" stays separable from the next case.</li>
 *   <li><b>Days exist, this caller has no row</b> — {@link #rollup} is empty.
 *       Nothing is assigned to them. The refresh writes no row for an empty
 *       scope precisely so that this is visible; see the migration header on
 *       why a zeroed row would be a false claim rather than a kindness.</li>
 *   <li><b>A row</b> — real figures.</li>
 * </ul>
 *
 * <h2>The three client-counted cards overstate here exactly as upstairs</h2>
 *
 * <p>{@code clients_overdue}, {@code clients_live} and {@code clients_escalated}
 * are written as {@code COUNT(DISTINCT ob_client_id)} within a product, so
 * summing the product rows counts a multi-product client once per product.
 * {@link ObDashboardSummaryRepository#isExact} is the single predicate for
 * both tables and is deliberately not specialised here — one rule means
 * {@code countIsUpperBound} keeps one meaning on the wire.
 */
@Repository
class ObScopeDashboardSummaryRepository {

    private final JdbcClient jdbc;

    ObScopeDashboardSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The two most recent days the table holds for anyone, newest first.
     *
     * <p><b>Not filtered by caller</b>, and that is the point: it answers "has
     * the refresh run", which has to stay separable from "has this caller
     * anything to show". Filtering by user here would collapse the two and
     * report a Step Owner with no services as an unrefreshed board.
     *
     * <p>Filtered by product when one is asked for, so a delta compares like
     * with like — {@link ObDashboardSummaryRepository#recentDays}'s own reason.
     */
    List<LocalDate> recentDays(Long productId) {
        return jdbc.sql("""
                SELECT DISTINCT stat_date
                  FROM ob_scope_dashboard_summary
                 WHERE (:productId IS NULL OR product_id = :productId)
                 ORDER BY stat_date DESC
                 LIMIT 2
                """)
                .param("productId", productId)
                .query(LocalDate.class)
                .list();
    }

    /**
     * The seven counters for one caller on one day.
     *
     * @return empty when this caller has no row on this day — nothing is
     *         assigned to them, which is a different claim from a board of
     *         zeroes. A bare aggregate over no rows still returns one row with
     *         every column NULL, so the null {@code computed_at} is what
     *         separates the two and is filtered on below.
     */
    Optional<ObDashboardSummaryRepository.Rollup> rollup(LocalDate statDate, long scopeUserId,
                                                         Long productId) {
        String projections = ObDashboardSummaryRepository.expressions().entrySet().stream()
                .map(entry -> "COALESCE(SUM(" + entry.getValue() + "), 0) AS "
                        + columnAlias(entry.getKey()))
                .reduce((left, right) -> left + ",\n                       " + right)
                .orElseThrow();

        String sql = """
                SELECT MAX(computed_at) AS computed_at,
                       %s
                  FROM ob_scope_dashboard_summary
                 WHERE stat_date = :statDate
                   AND scope_user_id = :scopeUserId
                   AND (:productId IS NULL OR product_id = :productId)
                """.formatted(projections);

        return jdbc.sql(sql)
                .param("statDate", statDate)
                .param("scopeUserId", scopeUserId)
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
                    return new ObDashboardSummaryRepository.Rollup(
                            statDate, computed.toInstant(), counts);
                })
                .optional()
                .filter(Objects::nonNull);
    }

    /**
     * {@code ongoing-projects} → {@code c_ongoing_projects}.
     *
     * <p>Duplicated from {@link ObDashboardSummaryRepository} rather than
     * shared: it is four tokens of string handling derived from the enum and
     * never from input, and widening the parent's private helper to package
     * scope to save them would make an alias-naming detail part of that class's
     * surface.
     */
    private static String columnAlias(ObDashboardCardKey key) {
        return "c_" + key.wireName().replace('-', '_');
    }
}
