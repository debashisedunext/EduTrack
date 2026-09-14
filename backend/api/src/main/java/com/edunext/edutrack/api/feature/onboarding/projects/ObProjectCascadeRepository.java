package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The four statements that remove a project nothing points at, in the only
 * order the foreign keys allow.
 *
 * <h2>Why this is written out rather than left to {@code ON DELETE CASCADE}</h2>
 *
 * <p>Because the schema deliberately does not cascade here.
 * {@code fk_ob_journey_steps_journey} is {@code RESTRICT}, and
 * {@code V20260903_1600}'s own comment says why: <em>"cascading a journey
 * delete into steps collides with the self-FK below the moment any step depends
 * on another, and would fail naming the wrong table."</em> Journeys are
 * archived, not deleted, and that remains true for every path except this one.
 *
 * <h2>The self-FK is why step 1 exists</h2>
 *
 * <p>{@code ob_journey_steps.depends_on_step_id} points at another row of the
 * same table, so deleting a journey's steps in one statement can fail on the
 * rows that happen to be removed first. Clearing the column across the whole
 * project before deleting anything makes the set independent, and the rows are
 * about to stop existing so nothing is lost by it.
 *
 * <p>{@code ob_journey_step_items} is not here: it is the one child of a step
 * that cascades, so it goes with the steps.
 */
@Repository
@UnscopedAccess("""
        Deletes by project id after ObProjectDeletionGuard has established that \
        nothing points at it and after a scoped read has answered 404 for a \
        project the caller cannot see. There is no row scope to apply at this \
        point — the decision was made two layers up.""")
class ObProjectCascadeRepository {

    /** 1 · break the self-reference, so the delete below sees independent rows. */
    private static final String CLEAR_DEPENDENCIES = """
            UPDATE ob_journey_steps s
              JOIN ob_journeys j ON j.id = s.journey_id
               SET s.depends_on_step_id = NULL
             WHERE j.project_id = :id
            """;

    /** 2 · the steps, taking their task-list items with them by cascade. */
    private static final String DELETE_STEPS = """
            DELETE s FROM ob_journey_steps s
              JOIN ob_journeys j ON j.id = s.journey_id
             WHERE j.project_id = :id
            """;

    /** 3 · the journeys, now childless. */
    private static final String DELETE_JOURNEYS =
            "DELETE FROM ob_journeys WHERE project_id = :id";

    /** 4 · the project itself. */
    private static final String DELETE_PROJECT =
            "DELETE FROM ob_projects WHERE id = :id";

    private final JdbcClient jdbc;

    ObProjectCascadeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Remove the project and everything underneath it.
     *
     * <p>Called only from inside {@code ObProjectWriteService.delete}'s
     * transaction, and only once its guard has answered empty. The purchase row
     * in {@code ob_client_applications} is deliberately left alone: it is a
     * commercial fact about the client rather than part of the project, and a
     * client who bought a product still bought it after somebody deleted the
     * project that was mis-created against it.
     */
    void deleteProject(long projectId) {
        jdbc.sql(CLEAR_DEPENDENCIES).param("id", projectId).update();
        jdbc.sql(DELETE_STEPS).param("id", projectId).update();
        jdbc.sql(DELETE_JOURNEYS).param("id", projectId).update();
        jdbc.sql(DELETE_PROJECT).param("id", projectId).update();
    }
}
