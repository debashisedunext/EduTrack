package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-127 · the bounded, live read behind {@code /cards/{cardKey}/items} — the
 * S-06 slide-over's own query, never {@code ob_dashboard_summary}.
 *
 * <h2>Why this is not the summary table, restated from the contract</h2>
 *
 * <p>The card's count is pre-aggregated; its rows are a bounded query run once,
 * when somebody clicks. {@code ObDashboardSummaryRepository} exists precisely
 * because CLAUDE.md forbids a <em>live</em> {@code COUNT(*)} behind a
 * dashboard's number — this is not that: it is a {@code LIMIT}-bounded row
 * fetch, the same shape every other paged list in this module already is.
 *
 * <h2>A union of two tables, in one statement</h2>
 *
 * <p>{@code ObDashboardItem} is deliberately a discriminated union across
 * {@code ob_journey_steps} (SERVICE) and {@code ob_client_prereq_tasks}
 * (PREREQUISITE) — the contract's own reasoning: "the rows are items, not
 * clients and not journeys", and no existing list answers both. Each branch is
 * its own {@code SELECT} against its own table with a matching column shape,
 * combined with {@code UNION ALL} so the cursor and the {@code LIMIT} apply
 * once, over both kinds of row together — never two separate pages the screen
 * would have to interleave itself.
 *
 * <h2>What each card means as a set of items, not a count</h2>
 *
 * <p>{@link CardPlan} is this class's answer to the same question
 * {@code ObDashboardSummaryRepository.EXPRESSIONS} answers for the count: one
 * entry per {@link ObDashboardCardKey}, matched by key rather than position so
 * a card the enum grows without a plan here fails a test rather than reading
 * empty forever. The predicates mirror {@code ObDashboardStatsRepository}'s
 * {@code OPEN}, {@code OVERDUE} and {@code AMBER} constants — restated rather
 * than shared, because that class lives in the {@code worker} module and this
 * one cannot depend on it; kept in step by {@code ObDashboardCardItemsIT},
 * which seeds rows the same way {@code ObDashboardStatsRepositoryIT} does and
 * asserts both agree about which side of each line a row falls on.
 *
 * <ul>
 *   <li><b>{@code ongoing-projects}</b> — every not-yet-settled item, service
 *       or prerequisite, that belongs to a journey which has not completed.
 *       A locked journey's own blocking prerequisites are exactly what a
 *       reader clicking "ongoing projects" wants to see, which is why this is
 *       the one card besides the two deadline cards that unions both kinds.</li>
 *   <li><b>{@code this-weeks-deadlines}</b>, <b>{@code todays-delivery}</b> —
 *       plan §9's "all client tasks — services and prerequisites — due" in the
 *       named window, read against the organisation's own day boundaries
 *       (§ below), not settled.</li>
 *   <li><b>{@code overdue-clients}</b> — services matching
 *       {@code ObDashboardStatsRepository.OVERDUE} exactly, because that is
 *       the predicate that puts a client on this card in the first place.
 *       Prerequisites are excluded for the same reason: B-120 counts this
 *       card from {@code ob_journey_steps} alone.</li>
 *   <li><b>{@code live}</b> — completed services belonging to a client whose
 *       {@code overall_status} is {@code LIVE}: what shipped for a client
 *       there is nothing left to chase.</li>
 *   <li><b>{@code at-risk}</b> — services matching {@code OVERDUE} or
 *       {@code AMBER} on a journey in the {@code RUNNING} bucket — the exact
 *       rows that turn that journey's colour amber or red. A locked or held
 *       journey has no colour at all (A-108) and is excluded, matching the
 *       count.</li>
 *   <li><b>{@code client-escalations}</b> — services carrying an open
 *       {@code ob_client_escalations} row, ordered newest-raised first, per
 *       that migration's own note on the index it built for this card.</li>
 * </ul>
 *
 * <h2>The cursor orders on two columns, never one</h2>
 *
 * <p>{@code due_at} (or, for {@code client-escalations}, {@code raised_at})
 * ties constantly — many steps share a due date computed from the same
 * working-calendar pass. {@code sortKeySigned} breaks the tie <em>and</em>
 * disambiguates the two id spaces a union puts side by side: a
 * {@code SERVICE} row's key is its own positive {@code ob_journey_steps.id}
 * and a {@code PREREQUISITE} row's is the negation of its
 * {@code ob_client_prereq_tasks.id}. Two different tables both starting their
 * {@code AUTO_INCREMENT} at 1 would otherwise collide the moment both kinds
 * shared a due instant.
 */
@Repository
class ObDashboardCardItemsRepository {

    /** {@code ObDashboardStatsRepository.OPEN}, restated — see the class note. */
    private static final String OPEN_SERVICE =
            "s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT')";

    /**
     * {@code ObDashboardStatsRepository.OVERDUE}, restated. {@code WAITING_ON_CLIENT} is out: its clock is stopped.
     *
     * <p>Package-private rather than {@code private} since B-128:
     * {@code ObDelayedProjectsRepository} needs the identical predicate over
     * its own {@code s} alias, and a third copy of the same six-line
     * expression within <em>one package</em> is not the cross-module
     * necessity that justifies restating it from {@code worker} in the first
     * place — see the class note above on why this file restates it once
     * already rather than depending on the {@code worker} module.
     */
    static final String OVERDUE_SERVICE = """
            (s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
             AND s.due_at IS NOT NULL AND s.due_at < :now)""";

    /** {@code ObDashboardStatsRepository.AMBER}, restated. */
    private static final String AMBER_SERVICE = """
            (s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED')
             AND s.due_at IS NOT NULL AND s.started_at IS NOT NULL
             AND s.due_at >= :now
             AND TIMESTAMPADD(MICROSECOND,
                     FLOOR(TIMESTAMPDIFF(MICROSECOND, s.started_at, s.due_at) * :amberShare),
                     s.started_at) <= :now)""";

    /**
     * A journey with no colour and nothing left open — A-108's four-bucket CASE, RUNNING only.
     *
     * <p>Package-private for the same reason {@link #OVERDUE_SERVICE} is:
     * B-128's {@code ObDelayedProjectsRepository} needs the identical
     * "past the gate, not held, not completed" test over its own journey
     * alias.
     */
    static final String JOURNEY_IS_RUNNING = """
            jr.completed_at IS NULL AND jr.gate_status <> 'LOCKED'
             AND NOT (jr.held_by_journey_id IS NOT NULL AND jr.released_at IS NULL)""";

    /** {@code ObClientPrereqTask.isOverdue}, restated as SQL: not settled and past due. */
    private static final String OVERDUE_PREREQ = "(t.status IN ('PENDING', 'SUBMITTED') AND t.due_at < :now)";

    private static final String SERVICE_SELECT = """
            SELECT 'SERVICE'          AS item_type,
                   s.id               AS item_id,
                   cl.id              AS ob_client_id,
                   cl.name            AS ob_client_name,
                   jr.id              AS journey_id,
                   pr.id              AS product_id,
                   pr.code            AS product_code,
                   pr.name            AS product_name,
                   s.name             AS title,
                   s.owner_user_id    AS owner_user_id,
                   ou.full_name       AS owner_name,
                   s.status           AS status,
                   s.due_at           AS due_at,
                   (%s)               AS is_overdue,
                   esc.raised_at      AS escalation_raised_at,
                   s.id               AS sort_key_signed
              FROM ob_journey_steps s
              JOIN ob_journeys jr ON jr.id = s.journey_id AND jr.archived_at IS NULL
              JOIN ob_clients cl ON cl.id = jr.ob_client_id
              JOIN ob_products pr ON pr.id = jr.product_id
              LEFT JOIN users ou ON ou.id = s.owner_user_id
              LEFT JOIN ob_client_escalations esc ON esc.step_id = s.id AND esc.resolved_at IS NULL
             WHERE s.due_at IS NOT NULL
               AND (:productId IS NULL OR jr.product_id = :productId)
               AND (:ownerUserId IS NULL OR s.owner_user_id = :ownerUserId OR s.backup_owner_user_id = :ownerUserId)
               AND (%s)
               AND (%s)
            """;

    private static final String PREREQ_SELECT = """
            SELECT 'PREREQUISITE'     AS item_type,
                   t.id               AS item_id,
                   cl.id              AS ob_client_id,
                   cl.name            AS ob_client_name,
                   NULL               AS journey_id,
                   NULL               AS product_id,
                   NULL               AS product_code,
                   NULL               AS product_name,
                   t.title            AS title,
                   NULL               AS owner_user_id,
                   NULL               AS owner_name,
                   t.status           AS status,
                   t.due_at           AS due_at,
                   (%s)               AS is_overdue,
                   NULL               AS escalation_raised_at,
                   -t.id              AS sort_key_signed
              FROM ob_client_prereq_tasks t
              JOIN ob_clients cl ON cl.id = t.ob_client_id
             WHERE :ownerUserId IS NULL
               AND (:productId IS NULL OR EXISTS (
                     SELECT 1 FROM ob_journeys pj
                      WHERE pj.ob_client_id = cl.id AND pj.product_id = :productId AND pj.archived_at IS NULL))
               AND (%s)
               AND (%s)
            """;

    /**
     * One entry per {@link ObDashboardCardKey} — see the class javadoc for what
     * each means as a set of rows.
     *
     * @param serviceExtra card-specific SQL over the SERVICE branch's aliases
     * @param prereqExtra  card-specific SQL over the PREREQUISITE branch's
     *                     aliases, or {@code null} when this card excludes
     *                     prerequisites entirely — the branch is then omitted
     *                     from the union rather than filtered to nothing, so
     *                     there is no scan to filter.
     * @param sortColumn   {@code due_at} or {@code escalation_raised_at} —
     *                     the outer query's keyset column, exposed to Java as
     *                     {@code ItemRow.cursorAt} regardless of which one it
     *                     was.
     * @param descending   true for {@code live} (most recently promised first)
     *                     and {@code client-escalations} (newest-raised first,
     *                     per the migration's own index comment); ascending —
     *                     soonest first — everywhere else.
     */
    private record CardPlan(String serviceExtra, String prereqExtra, String sortColumn, boolean descending) {
    }

    private static final Map<ObDashboardCardKey, CardPlan> PLANS = Map.of(
            ObDashboardCardKey.ONGOING_PROJECTS, new CardPlan(
                    OPEN_SERVICE + " AND jr.completed_at IS NULL",
                    "t.status IN ('PENDING', 'SUBMITTED') AND EXISTS ("
                            + "SELECT 1 FROM ob_journeys oj WHERE oj.ob_client_id = cl.id "
                            + "AND oj.archived_at IS NULL AND oj.completed_at IS NULL)",
                    "due_at", false),
            ObDashboardCardKey.THIS_WEEKS_DEADLINES, new CardPlan(
                    "s.status NOT IN ('DONE', 'SKIPPED') AND s.due_at >= :weekStart AND s.due_at < :weekEnd",
                    "t.status NOT IN ('VERIFIED', 'SKIPPED') AND t.due_at >= :weekStart AND t.due_at < :weekEnd",
                    "due_at", false),
            ObDashboardCardKey.TODAYS_DELIVERY, new CardPlan(
                    "s.status NOT IN ('DONE', 'SKIPPED') AND s.due_at >= :dayStart AND s.due_at < :dayEnd",
                    "t.status NOT IN ('VERIFIED', 'SKIPPED') AND t.due_at >= :dayStart AND t.due_at < :dayEnd",
                    "due_at", false),
            ObDashboardCardKey.OVERDUE_CLIENTS, new CardPlan(OVERDUE_SERVICE, null, "due_at", false),
            ObDashboardCardKey.LIVE, new CardPlan(
                    "s.status = 'DONE' AND cl.overall_status = 'LIVE'", null, "due_at", true),
            ObDashboardCardKey.AT_RISK, new CardPlan(
                    "(" + OVERDUE_SERVICE + " OR " + AMBER_SERVICE + ") AND " + JOURNEY_IS_RUNNING,
                    null, "due_at", false),
            ObDashboardCardKey.CLIENT_ESCALATIONS, new CardPlan(
                    "esc.raised_at IS NOT NULL", null, "escalation_raised_at", true));

    private final JdbcClient jdbc;

    ObDashboardCardItemsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Up to {@code fetchSize} rows for one card, newest-cursor-first per
     * {@link CardPlan#descending}.
     *
     * @param fetchSize {@code PageLimit.fetchSize} — one more than the page,
     *                  so the caller can tell whether there is another
     */
    List<ItemRow> page(ObDashboardCardKey cardKey, ObDashboardScope scope, Long productId, Long ownerUserId,
                       Cursor cursor, int fetchSize, Instant now, BigDecimal amberShare,
                       Instant dayStart, Instant dayEnd, Instant weekStart, Instant weekEnd) {

        CardPlan plan = PLANS.get(cardKey);
        if (plan == null) {
            // Every ObDashboardCardKey has a plan — ObDashboardCardItemsRepositoryTest
            // pins it — so reaching this is a card added to the enum without one.
            throw new IllegalStateException("no CardPlan for " + cardKey);
        }

        boolean includePrereqs = plan.prereqExtra() != null && ownerUserId == null;

        String service = SERVICE_SELECT.formatted(
                OVERDUE_SERVICE, scope.journeyPredicate("jr", "cl"), plan.serviceExtra());
        String union = includePrereqs
                ? "(%s) UNION ALL (%s)".formatted(
                        service, PREREQ_SELECT.formatted(
                                OVERDUE_PREREQ, scope.clientPredicate("cl"), plan.prereqExtra()))
                : service;

        // u.%1$s AS cursor_at rather than reading u.due_at or u.escalation_raised_at
        // directly: which column IS the cursor's sort value differs by card
        // (client-escalations orders on raised_at, not due_at), and this is the
        // one place that has to know it — ItemRow and its mapper read "cursor_at"
        // unconditionally, so a card's choice of column cannot leak into either.
        String sql = """
                SELECT u.*, u.%1$s AS cursor_at FROM (%2$s) u
                WHERE (:cursorAt IS NULL
                       OR u.%1$s %3$s :cursorAt
                       OR (u.%1$s = :cursorAt AND u.sort_key_signed %3$s :cursorKey))
             ORDER BY u.%1$s %4$s, u.sort_key_signed %4$s
                LIMIT :fetchSize
                """.formatted(plan.sortColumn(), union,
                plan.descending() ? "<" : ">", plan.descending() ? "DESC" : "ASC");

        var spec = jdbc.sql(sql)
                .param("now", now)
                .param("amberShare", amberShare)
                .param("dayStart", dayStart)
                .param("dayEnd", dayEnd)
                .param("weekStart", weekStart)
                .param("weekEnd", weekEnd)
                .param("productId", productId)
                .param("ownerUserId", ownerUserId)
                .param("cursorAt", cursor == null ? null : Instant.parse(cursor.sortKey()))
                .param("cursorKey", cursor == null ? null : cursor.id())
                .param("fetchSize", fetchSize);
        if (!scope.unrestricted()) {
            spec = spec.param(ObDashboardScope.USER_PARAM, scope.userId());
        }
        return spec.query(ROW_MAPPER).list();
    }

    /**
     * One row of the union — {@code ObDashboardItem} before the DTO mapping.
     *
     * @param cursorAt the value this card actually ordered on — {@code dueAt}
     *                 for every card but {@code client-escalations}, which
     *                 orders on when the escalation was raised. Never read by
     *                 the DTO mapping; only {@link ObDashboardCardItemsService}
     *                 reads it, to name the next page's cursor.
     */
    record ItemRow(String itemType, long itemId, long obClientId, String obClientName, Long journeyId,
                   Long productId, String productCode, String productName, String title, Long ownerUserId,
                   String ownerName, String status, Instant dueAt, boolean isOverdue,
                   Instant cursorAt, long sortKeySigned) {
    }

    private static final RowMapper<ItemRow> ROW_MAPPER = (rs, n) -> new ItemRow(
            rs.getString("item_type"),
            rs.getLong("item_id"),
            rs.getLong("ob_client_id"),
            rs.getString("ob_client_name"),
            nullableLong(rs, "journey_id"),
            nullableLong(rs, "product_id"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getString("title"),
            nullableLong(rs, "owner_user_id"),
            rs.getString("owner_name"),
            rs.getString("status"),
            instant(rs, "due_at"),
            rs.getBoolean("is_overdue"),
            instant(rs, "cursor_at"),
            rs.getLong("sort_key_signed"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Every card the enum declares has a plan behind it — mirrors {@code ObDashboardSummaryRepository.expressions()}. */
    static Set<ObDashboardCardKey> plannedCards() {
        return PLANS.keySet();
    }
}
