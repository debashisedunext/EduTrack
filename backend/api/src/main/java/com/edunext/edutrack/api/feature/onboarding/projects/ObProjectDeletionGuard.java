package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What stands between a project and {@code DELETE} — asked before the statement
 * runs, so the answer is a sentence rather than a foreign-key violation.
 *
 * <h2>The question is "does anything point at this?", not "has it started?"</h2>
 *
 * <p>Those two nearly agree and the first is the one that can be answered
 * exactly. A project owns journeys, journeys own steps, and <b>nine tables hold
 * a foreign key to {@code ob_journey_steps} with no cascade</b>:
 * {@code ob_step_history}, {@code ob_step_clock_events},
 * {@code ob_step_communications}, {@code ob_signoffs}, {@code ob_escalations},
 * {@code ob_client_escalations}, {@code ob_attachments},
 * {@code ob_notifications} and {@code ob_notification_outbox}. Every one of
 * them is a record of something that happened, and two —
 * {@code ob_step_history} and its prerequisite twin — are hash-chained and
 * append-only.
 *
 * <p>So rather than infer from a step's status, this asks each of them
 * directly. A project nothing points at is a project nothing happened to, and
 * that is the only kind this module will remove.
 *
 * <p>{@code ob_journey_step_items} is deliberately absent: it is the only
 * child of a step that cascades, it is the task-list template copied at
 * instantiation, and a checklist nobody has ticked records nothing.
 *
 * <h2>Why delete exists at all, given the above</h2>
 *
 * <p>Because the alternative is worse for the case it is actually for. A
 * project created against the wrong client, or on a product chosen by mistake,
 * is noise on a grid people read every morning — and {@code DROPPED} keeps it
 * there forever with a reason that says "this was a typo". Deleting the ones
 * that never ran keeps the grid honest; refusing the ones that did keeps the
 * history honest.
 */
@Repository
@UnscopedAccess("""
        Counts rows by project and by the journeys under it with no scope \
        predicate, and must: the caller has already passed a scoped read that \
        answered 404, and the question here is whether anything at all depends \
        on this project — a sign-off recorded by somebody in another scope \
        blocks the delete exactly as firmly as one of the caller's own.""")
class ObProjectDeletionGuard {

    /**
     * One statement, nine {@code EXISTS} subqueries, one row back.
     *
     * <p>Each is scoped through the project's own live journeys, so a journey
     * archived out of this project does not hold its delete — an archived
     * journey is already the module's way of saying "this no longer applies".
     */
    private static final String SQL = """
            SELECT EXISTS (SELECT 1 FROM ob_step_history h
                             JOIN ob_journey_steps s ON s.id = h.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasHistory,
                   EXISTS (SELECT 1 FROM ob_step_clock_events c
                             JOIN ob_journey_steps s ON s.id = c.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasClock,
                   EXISTS (SELECT 1 FROM ob_step_communications m
                             JOIN ob_journey_steps s ON s.id = m.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasComms,
                   EXISTS (SELECT 1 FROM ob_signoffs g
                             JOIN ob_journey_steps s ON s.id = g.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasSignoffs,
                   EXISTS (SELECT 1 FROM ob_escalations e
                             JOIN ob_journey_steps s ON s.id = e.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasEscalations,
                   EXISTS (SELECT 1 FROM ob_client_escalations ce
                             JOIN ob_journey_steps s ON s.id = ce.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasClientEscalations,
                   EXISTS (SELECT 1 FROM ob_attachments a
                             JOIN ob_journey_steps s ON s.id = a.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasAttachments,
                   EXISTS (SELECT 1 FROM ob_notifications n
                             JOIN ob_journey_steps s ON s.id = n.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasNotifications,
                   EXISTS (SELECT 1 FROM ob_notification_outbox o
                             JOIN ob_journey_steps s ON s.id = o.step_id
                             JOIN ob_journeys j ON j.id = s.journey_id
                            WHERE j.project_id = :id)                       AS hasOutbox
            """;

    private final JdbcClient jdbc;

    ObProjectDeletionGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The things in the way, phrased for a person. Empty means the project can
     * go.
     *
     * <p>Ordered by how much it explains: history first, because a project with
     * recorded transitions is the case somebody most needs talked out of
     * deleting, and the rest follow the work.
     */
    List<String> blockersFor(long projectId) {
        Map<String, Object> row = jdbc.sql(SQL).param("id", projectId).query().singleRow();

        List<String> blockers = new ArrayList<>();
        if (isTrue(row.get("hasHistory"))) {
            blockers.add("recorded step history");
        }
        if (isTrue(row.get("hasClock"))) {
            blockers.add("time logged against its steps");
        }
        if (isTrue(row.get("hasSignoffs"))) {
            blockers.add("client sign-offs");
        }
        if (isTrue(row.get("hasComms"))) {
            blockers.add("recorded communications");
        }
        if (isTrue(row.get("hasEscalations")) || isTrue(row.get("hasClientEscalations"))) {
            blockers.add("escalations");
        }
        if (isTrue(row.get("hasAttachments"))) {
            blockers.add("uploaded documents");
        }
        if (isTrue(row.get("hasNotifications")) || isTrue(row.get("hasOutbox"))) {
            blockers.add("notifications already sent");
        }
        return blockers;
    }

    /**
     * MySQL's {@code EXISTS} is {@code BIGINT} 0/1, and the driver may hand it
     * back as {@code Long}, {@code Integer} or {@code Boolean}. Read through
     * {@link Number} rather than cast, so a driver upgrade cannot turn "has
     * history" into a {@link ClassCastException} on a delete path.
     */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() != 0;
    }
}
