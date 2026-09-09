package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProductRef;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B-128 · the bounded, live read behind {@code /dashboard/delayed-projects} —
 * plan §9's first grid.
 *
 * <h2>Not a dashboard {@code COUNT(*)}</h2>
 *
 * <p>This is a row fetch, not a count, and CLAUDE.md's rule is about the
 * latter — {@code ObDashboardCardItemsRepository}'s own class note draws the
 * identical line. The set it selects is naturally bounded: journeys currently
 * past the gate, not held, not completed, carrying at least one overdue step
 * — a fraction of the journey table on any real deployment, never all of it.
 *
 * <h2>One journey, one overdue step, one current step — three correlated
 * scalar subqueries rather than a Java loop over {@code ObJourneyStep}</h2>
 *
 * <p>{@code ov} names the earliest-due <b>overdue</b> step (the one that put
 * this journey on the grid — {@link #OVERDUE_STEP}, borrowed unchanged from
 * {@link ObDashboardCardItemsRepository}); {@code cur} names the
 * lowest-sequence step currently {@code IN_PROGRESS} or
 * {@code WAITING_ON_CLIENT} — the contract's "current stage". They are not
 * always the same row: a client's overdue step may itself be
 * {@code BLOCKED}, in which case {@code cur} resolves to nothing and
 * {@code currentStep} is null on the wire, exactly as the contract's own
 * note reads — "null on a journey whose every step is blocked or not yet
 * activated". {@code responsible} is always named from {@code ov}, never
 * {@code cur}: the question this column answers is "who owns the delay",
 * and a step can be the reason this journey is late without currently being
 * the one running.
 *
 * <h2>No RAG on {@code currentStep}, and that is a scope decision</h2>
 *
 * <p>{@code ObStepDot.rag} is nullable on the wire and this route always
 * sends null. Computing it for real would mean loading the full
 * {@code ObJourneyStep} entity behind {@code cur} and running it through
 * {@code ObJourneyStepRagService} — a second per-row calendar call on top of
 * the one {@link ObDelayedProjectsService} already makes for
 * {@code delayedByDays} — for a chip plan §9 does not ask this grid to show
 * (the design calls out "expected completion and delayed-by days
 * prominent", not a per-row health dot; that already lives on OB-05's own
 * strip). Named here rather than silently short-changed.
 *
 * <h2>{@code delayedByDays} is not computed here</h2>
 *
 * <p>SQL cannot consult {@code WorkingCalendar}, so this class hands back
 * {@code expectedCompletionAt} as a plain instant and
 * {@link ObDelayedProjectsService} turns it into working days late — the
 * same split {@code ObDashboardStatsRepository.refreshBlockedHours} makes for
 * {@code blocked_hours}, for the identical reason.
 */
@Repository
class ObDelayedProjectsRepository {

    /** {@code ObDashboardCardItemsRepository.OVERDUE_SERVICE}, over this query's own {@code s} alias. */
    private static final String OVERDUE_STEP = ObDashboardCardItemsRepository.OVERDUE_SERVICE;

    /** {@code ObDashboardCardItemsRepository.JOURNEY_IS_RUNNING}, over this query's own {@code jr} alias. */
    private static final String JOURNEY_IS_RUNNING = ObDashboardCardItemsRepository.JOURNEY_IS_RUNNING;

    private static final String CANDIDATES_SQL = """
            SELECT jr.id            AS journey_id,
                   cl.id            AS ob_client_id,
                   cl.name          AS ob_client_name,
                   jr.started_at    AS started_at,
                   pr.id            AS product_id,
                   pr.code          AS product_code,
                   pr.name          AS product_name,
                   ov.due_at        AS expected_completion_at,
                   COALESCE(ov.owner_user_id, ov.backup_owner_user_id) AS responsible_user_id,
                   ovu.full_name    AS responsible_name,
                   cur.id           AS current_step_id,
                   cur.sequence     AS current_step_sequence,
                   cur.name         AS current_step_name,
                   cur.status       AS current_step_status,
                   cur.depends_on_step_id AS current_step_depends_on
              FROM ob_journeys jr
              JOIN ob_clients cl ON cl.id = jr.ob_client_id
              JOIN ob_products pr ON pr.id = jr.product_id
              -- The earliest-due overdue step — the row that made this
              -- journey delayed in the first place. A scalar subquery in the
              -- JOIN condition rather than a correlated EXISTS in the WHERE,
              -- because this query needs the row's own columns
              -- (due_at, owner) and not merely its existence.
              LEFT JOIN ob_journey_steps ov ON ov.id = (
                    SELECT s.id FROM ob_journey_steps s
                     WHERE s.journey_id = jr.id AND (%1$s)
                  ORDER BY s.due_at ASC, s.id ASC
                     LIMIT 1)
              LEFT JOIN users ovu ON ovu.id = COALESCE(ov.owner_user_id, ov.backup_owner_user_id)
              -- The lowest-sequence step actually in flight — the contract's
              -- "current stage". Independent of ov: see the class note.
              LEFT JOIN ob_journey_steps cur ON cur.id = (
                    SELECT s2.id FROM ob_journey_steps s2
                     WHERE s2.journey_id = jr.id
                       AND s2.status IN ('IN_PROGRESS', 'WAITING_ON_CLIENT')
                  ORDER BY s2.sequence ASC
                     LIMIT 1)
             WHERE jr.archived_at IS NULL
               AND (%2$s)
               -- No overdue step, no row: this is what makes the query
               -- "delayed projects" rather than "every open journey".
               AND ov.id IS NOT NULL
               AND (:productId IS NULL OR jr.product_id = :productId)
               AND (:ownerUserId IS NULL
                    OR ov.owner_user_id = :ownerUserId OR ov.backup_owner_user_id = :ownerUserId)
               AND (%3$s)
            """;

    private static final String PRODUCTS_BOUGHT_SQL = """
            SELECT ca.ob_client_id AS ob_client_id, pr.id AS id, pr.code AS code, pr.name AS name
              FROM ob_client_applications ca
              JOIN ob_products pr ON pr.id = ca.product_id
             WHERE ca.ob_client_id IN (:clientIds)
          ORDER BY pr.name ASC
            """;

    private final JdbcClient jdbc;

    ObDelayedProjectsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every currently-delayed journey the caller's scope can see — unfiltered
     * by {@code minDelayDays} and unpaginated, both of which
     * {@link ObDelayedProjectsService} applies once {@code delayedByDays} is
     * known, since neither can be expressed against
     * {@code expectedCompletionAt} in SQL.
     *
     * @param now used only to decide which steps count as overdue —
     *            {@link ObDelayedProjectsService} makes its own, working-day
     *            aware use of {@code now} for the figure itself.
     */
    List<CandidateRow> candidates(ObDashboardScope scope, Long productId, Long ownerUserId, Instant now) {
        if (scope.deniesEverything()) {
            return List.of();
        }
        String sql = CANDIDATES_SQL.formatted(OVERDUE_STEP, JOURNEY_IS_RUNNING, scope.journeyPredicate("jr", "cl"));
        var spec = jdbc.sql(sql)
                .param("now", now)
                .param("productId", productId)
                .param("ownerUserId", ownerUserId);
        if (!scope.unrestricted()) {
            spec = spec.param(ObDashboardScope.USER_PARAM, scope.userId());
        }
        return spec.query(CANDIDATE_ROW_MAPPER).list();
    }

    /**
     * Every product {@code clientIds} has ever bought, grouped by client —
     * the contract's {@code productsBought}, "every product this client
     * bought, not just this journey's". One batched query for the whole page
     * rather than one per row, since {@link ObDelayedProjectsService} only
     * ever calls this for the rows a page actually returns.
     */
    Map<Long, List<ObProductRef>> productsBoughtByClient(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ObProductRef>> byClient = new LinkedHashMap<>();
        jdbc.sql(PRODUCTS_BOUGHT_SQL)
                .param("clientIds", clientIds)
                .query((rs, n) -> new Object[] {
                        rs.getLong("ob_client_id"),
                        new ObProductRef(rs.getLong("id"), rs.getString("code"), rs.getString("name")) })
                .list()
                .forEach(row -> byClient
                        .computeIfAbsent((Long) row[0], id -> new ArrayList<>())
                        .add((ObProductRef) row[1]));
        return byClient;
    }

    /** One candidate journey, before {@link ObDelayedProjectsService} turns {@code expectedCompletionAt} into working days. */
    record CandidateRow(long journeyId, long obClientId, String obClientName, Instant startedAt,
                        long productId, String productCode, String productName,
                        Instant expectedCompletionAt, Long responsibleUserId, String responsibleName,
                        Long currentStepId, Integer currentStepSequence, String currentStepName,
                        String currentStepStatus, Long currentStepDependsOn) {
    }

    private static final RowMapper<CandidateRow> CANDIDATE_ROW_MAPPER = (rs, n) -> new CandidateRow(
            rs.getLong("journey_id"),
            rs.getLong("ob_client_id"),
            rs.getString("ob_client_name"),
            instant(rs, "started_at"),
            rs.getLong("product_id"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            instant(rs, "expected_completion_at"),
            nullableLong(rs, "responsible_user_id"),
            rs.getString("responsible_name"),
            nullableLong(rs, "current_step_id"),
            nullableInt(rs, "current_step_sequence"),
            rs.getString("current_step_name"),
            rs.getString("current_step_status"),
            nullableLong(rs, "current_step_depends_on"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
