package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two per-project counts OB-02's board needs and the Projects feature does
 * not already compute — keyed by the ids of the page it is decorating.
 *
 * <h2>Why this reads so little</h2>
 *
 * <p>Everything else on the board — the completion date, the delay, the
 * current stage, the client, the product, the two people — comes from
 * {@code ObRunningProjectReader}, the Projects feature's own read, so that a
 * card on this board and a row in that grid can never disagree about whether a
 * project is late. What is left over is task progress and open escalations,
 * neither of which that grid reports, and both of which are plain counts.
 *
 * <h2>No scope predicate here, deliberately</h2>
 *
 * <p>This is keyed by project ids the caller has <em>already</em> been shown
 * through a scoped read. Re-applying the scope would be a second spelling of
 * §3's rule with nothing to gain: an id that is not in that list is never
 * passed in, and if one ever were, the bug would be upstream where the list
 * was built. Every method here is package-private and takes ids, so there is no
 * route by which an unscoped caller reaches it.
 *
 * <p>Not a dashboard {@code COUNT(*)} in CLAUDE.md's sense either: two
 * aggregates over one page's worth of projects, not a count over the whole
 * table on every paint.
 */
@Repository
class ObProjectBoardRepository {

    /**
     * Tasks per project, and how many are settled.
     *
     * <p>{@code SKIPPED} counts as settled beside {@code DONE} — a waived task
     * is finished business, which is how the ribbon and the Projects grid's own
     * stage roll-up both read it. Counting it outstanding would leave a project
     * whose optional tasks were all waived permanently short of complete.
     */
    private static final String TASK_COUNTS = """
            SELECT j.project_id                                      AS projectId,
                   COUNT(s.id)                                       AS tasksTotal,
                   COALESCE(SUM(s.status IN ('DONE', 'SKIPPED')), 0) AS tasksDone
              FROM ob_journeys j
              JOIN ob_journey_steps s ON s.journey_id = j.id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
             GROUP BY j.project_id
            """;

    /**
     * Open portal escalations per project.
     *
     * <p>{@code resolved_at IS NULL} is the open test — the same one the
     * escalations table's generated {@code open_key} column is built from,
     * spelled out because this is a count rather than the unique index's
     * one-open-per-step rule.
     *
     * <p>A project absent from the result has none. The caller defaults it to
     * zero rather than this padding the map, which is what
     * {@code criticalPathTatByProject} does one package over and for the same
     * reason: a row that says nothing is cheaper than a row that says zero.
     */
    private static final String OPEN_ESCALATIONS = """
            SELECT j.project_id AS projectId,
                   COUNT(*)     AS openEscalations
              FROM ob_client_escalations e
              JOIN ob_journeys j ON j.id = e.journey_id
             WHERE j.project_id IN (:projectIds)
               AND j.archived_at IS NULL
               AND e.resolved_at IS NULL
             GROUP BY j.project_id
            """;

    private final JdbcClient jdbc;

    ObProjectBoardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One query for the whole board, not one per row. Empty map for an empty board. */
    Map<Long, TaskCounts> taskCountsByProject(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, TaskCounts> byProject = new LinkedHashMap<>();
        jdbc.sql(TASK_COUNTS).param("projectIds", projectIds).query(TASK_MAPPER).list()
                .forEach(row -> byProject.put(row.projectId(), row));
        return byProject;
    }

    /** Projects with no open escalation are absent, not zero — see {@link #OPEN_ESCALATIONS}. */
    Map<Long, Integer> openEscalationsByProject(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> byProject = new LinkedHashMap<>();
        jdbc.sql(OPEN_ESCALATIONS).param("projectIds", projectIds)
                .query((rs, n) -> Map.entry(rs.getLong("projectId"), rs.getInt("openEscalations")))
                .list()
                .forEach(entry -> byProject.put(entry.getKey(), entry.getValue()));
        return byProject;
    }

    /** A project's task progress, folded across every module service it was boarded through. */
    record TaskCounts(long projectId, int tasksTotal, int tasksDone) {

        static final TaskCounts NONE = new TaskCounts(0, 0, 0);
    }

    private static final RowMapper<TaskCounts> TASK_MAPPER = (rs, n) -> new TaskCounts(
            rs.getLong("projectId"), rs.getInt("tasksTotal"), rs.getInt("tasksDone"));
}
