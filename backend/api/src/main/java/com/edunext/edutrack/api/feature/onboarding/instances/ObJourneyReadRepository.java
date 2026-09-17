package com.edunext.edutrack.api.feature.onboarding.instances;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.edunext.edutrack.domain.onboarding.ObStepReviewState;
import com.edunext.edutrack.domain.onboarding.ObStepRowState;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;

/**
 * C-110 · the one journey behind {@code GET /onboarding/journeys/{journeyId}}.
 *
 * <h2>Why this is a query and not {@code ObJourneyRepository.findById}</h2>
 *
 * <p>Half of {@code ObJourneySummary} is not on {@code ob_journeys}:
 * {@code clientName} is denormalised for the eye, the product triple is a
 * join, {@code templateVersion} is the pinned version on
 * {@code ob_journey_templates}, and {@code percentComplete}, {@code rag} and
 * {@code totalTatDays} are aggregates over the step rows. Loading the entity
 * and then issuing four more reads to decorate it would be four round trips
 * for one row — {@code ObClientReadRepository.journeysOf}'s shape, narrowed
 * from "one client's journeys" to "this journey".
 *
 * <h2>Scoped in the same statement, by {@link ObClientScope}'s own predicate</h2>
 *
 * <p>A journey is visible exactly when its client is, so the {@code JOIN} onto
 * {@code ob_clients} that {@code clientName} already needs is also where the
 * scope belongs — {@code ObClientReadRepository.findDetail}'s shape. Composing
 * the predicate rather than respelling §3 here is CLAUDE.md's rule: a second
 * reading of the scope would drift the first time Sales' changed, and it would
 * drift silently because both would look correct in isolation.
 *
 * <p><b>Out of scope answers empty, exactly as "no such journey" does</b>, so
 * the controller's 404 cannot distinguish them — blueprint §2's
 * no-existence-leak rule, which is why this returns an {@code Optional} and
 * not something a caller could turn into a 403.
 */
@Repository
class ObJourneyReadRepository {

    private final JdbcClient jdbc;

    ObJourneyReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Archived journeys answer empty.
     *
     * <p>An archived journey is a withdrawn purchase, excluded from every
     * count and every list — {@code ObClientReadRepository.journeysOf} drops
     * them for the same reason — so addressing one directly is a 404 rather
     * than a view onto a row the rest of the module pretends is gone.
     */
    Optional<Row> find(ObClientScope scope, long journeyId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        var spec = jdbc.sql("""
                SELECT j.id                 AS id,
                       j.ob_client_id       AS obClientId,
                       c.name               AS clientName,
                       j.gate_status        AS gateStatus,
                       j.held_by_journey_id AS heldByJourneyId,
                       j.started_at         AS startedAt,
                       j.completed_at       AS completedAt,
                       j.archived_at        AS archivedAt,
                       p.id                 AS productId,
                       p.code               AS productCode,
                       p.name               AS productName,
                       j.service_name       AS serviceName,
                       t.id                 AS templateId,
                       t.version            AS templateVersion,
                       (SELECT COUNT(*) FROM ob_journey_steps ts WHERE ts.journey_id = j.id) AS stepCount,
                       (SELECT COUNT(*) FROM ob_journey_steps ds WHERE ds.journey_id = j.id
                         AND ds.status IN ('DONE', 'SKIPPED')) AS stepsSettled,
                       (SELECT COALESCE(SUM(bs.tat_days), 0) FROM ob_journey_steps bs
                         WHERE bs.journey_id = j.id) AS totalTatDays,
                       (SELECT %s FROM ob_journey_steps rs WHERE rs.journey_id = j.id) AS rag
                  FROM ob_journeys j
                  JOIN ob_clients c ON c.id = j.ob_client_id
                  JOIN ob_products p ON p.id = j.product_id
                  JOIN ob_journey_templates t ON t.id = j.template_id
                 WHERE j.id = :id
                   AND j.archived_at IS NULL
                   AND %s
                """.formatted(ObStepRag.worstOverSteps("rs"), scope.predicate("c")))
                .param("id", journeyId);

        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(MAPPER).optional();
    }

    /** The display name behind {@code owner}, resolved only when there is one. */
    Optional<String> displayNameOf(long userId) {
        return jdbc.sql("SELECT full_name FROM users WHERE id = :id")
                .param("id", userId).query(String.class).optional();
    }

    /**
     * The implementor on the project this journey belongs to — who an ownerless
     * task falls to.
     *
     * <h2>One query for the journey, not one per task</h2>
     *
     * <p>Every task of a journey inherits from the same project, so this is
     * asked once by {@link ObJourneyReadService} and applied down the list,
     * exactly as {@code stagesOfJourney} and {@code itemsOfJourney} are.
     *
     * <h2>Unscoped, and provably so</h2>
     *
     * <p>Called only after {@link #find} has already answered for this journey
     * under the caller's scope — a journey the caller cannot see never reaches
     * this method, and the one thing it discloses is a user id the same read is
     * about to print as the task's owner anyway.
     *
     * <p>Empty where the project names no implementor. That is a real state and
     * not an error: {@code ObProjectProvisioning.ensureFor} creates a project
     * with a null implementor when a purchase arrives through OB-05 rather than
     * through the New Project form, and those tasks stay genuinely unassigned.
     */
    Optional<Long> implementorOfJourney(long journeyId) {
        return jdbc.sql("""
                SELECT p.implementor_user_id
                  FROM ob_journeys j
                  JOIN ob_projects p ON p.id = j.project_id
                 WHERE j.id = :id
                   AND p.implementor_user_id IS NOT NULL
                """)
                .param("id", journeyId)
                .query(Long.class)
                .optional();
    }

    /**
     * Who reviews this journey's finished tasks — the project's
     * {@code implementor_manager_user_id}.
     *
     * <p>Read alongside the implementor and for the same reason: the screen has
     * to know, before it draws a control, whether this reader is the person
     * {@code ObJourneyStepLifecycleService#requireReviewer} will accept. A
     * Review column offered to somebody the server then refuses is worse than
     * no column.
     *
     * <p>Empty where the project names no manager — a real state, not an error.
     * Those projects simply have no reviewer but an {@code OB_ADMIN}.
     */
    Optional<Long> implementorManagerOfJourney(long journeyId) {
        return jdbc.sql("""
                SELECT p.implementor_manager_user_id
                  FROM ob_journeys j
                  JOIN ob_projects p ON p.id = j.project_id
                 WHERE j.id = :id
                   AND p.implementor_manager_user_id IS NOT NULL
                """)
                .param("id", journeyId)
                .query(Long.class)
                .optional();
    }

    /**
     * Which implementation stage each task of one journey belongs to, by step
     * id.
     *
     * <h2>One query for the whole journey, not one per step</h2>
     *
     * <p>The alternative is resolving the stage inside the per-step mapping,
     * which is a join per task on a screen that draws every task of a journey
     * at once — the N+1 this read already avoids everywhere else.
     *
     * <h2>The fold is copied deliberately, and must stay copied</h2>
     *
     * <p>{@code COALESCE(g.implementation_stage_id, -g.id, 0)} is the same
     * expression as {@code ObProjectReadRepository.STAGE_ROLLUP} and
     * {@code ObClientReadRepository#stepDotsOf}. Three readers now fold stage
     * groups onto implementation stages this way, and the project page matches
     * a task to a ribbon stop by comparing the results — so a fourth reader
     * that folded differently would produce a stage that looks populated and
     * opens empty. That is a bug nobody would read as a key mismatch, which is
     * why each of the three says so in its own javadoc rather than trusting
     * the next author to go looking.
     *
     * <p><b>Left joins, on purpose.</b> A template step deleted after a journey
     * started leaves {@code template_step_id} pointing at nothing; the task
     * keeps running and lands in the {@code 0} bucket rather than dropping out
     * of the ribbon.
     */
    Map<Long, StageRef> stagesOfJourney(long journeyId) {
        return jdbc.sql("""
                SELECT s.id AS stepId,
                       COALESCE(g.implementation_stage_id, -g.id, 0) AS stageKey,
                       COALESCE(g.name, 'Ungrouped')                 AS stageName
                  FROM ob_journey_steps s
             LEFT JOIN ob_journey_template_steps  ts ON ts.id = s.template_step_id
             LEFT JOIN ob_journey_template_stages g  ON g.id = ts.template_stage_id
                 WHERE s.journey_id = :journeyId
                """)
                .param("journeyId", journeyId)
                .query((rs, n) -> Map.entry(
                        rs.getLong("stepId"),
                        new StageRef(rs.getLong("stageKey"), rs.getString("stageName"))))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** One task's stage — the key the ribbon matches on, and the name it prints. */
    record StageRef(long stageKey, String stageName) {
    }

    /**
     * Every sub-task of every task of one journey, keyed by task id.
     *
     * <h2>Why the journey read carries these at all</h2>
     *
     * <p>It did not used to: the checklist was fetched one task at a time by
     * {@code GET /onboarding/journey-steps/{stepId}}, because only the selected
     * task's panel ever showed it. The project page's stage body shows every
     * task of a stage with its sub-tasks open underneath, so that shape would
     * be one request per task on first paint.
     *
     * <h2>Mandatory is resolved here, not defaulted by the caller</h2>
     *
     * <p>{@code ob_journey_step_items} does not store it — it is a fact about
     * the <em>template</em> item, which is why
     * {@code ObJourneyStepLifecycleService.checklistFor} joins for it too.
     * {@code COALESCE(ti.is_mandatory, 1)} keeps this agreeing with that
     * method's {@code getOrDefault(..., true)}: an item whose template row has
     * gone is treated as mandatory, so a vanished template can never quietly
     * drop a task out of the completion gate.
     *
     * <h2>`isDone` means answered, not answered yes</h2>
     *
     * <p>{@code answer IS NOT NULL}, matching the server's completion gate
     * exactly — an item answered <b>False</b> satisfies the gate as an item
     * answered True does. {@code StepTaskList}'s own note spells out why
     * "fixing" this to mean "answered True" would make the screen refuse
     * completions the server allows.
     */
    Map<Long, List<ItemRow>> itemsOfJourney(long journeyId) {
        return jdbc.sql("""
                SELECT i.step_id                     AS stepId,
                       i.id                          AS id,
                       i.sequence                    AS sequence,
                       i.label                       AS label,
                       i.answer IS NOT NULL          AS isDone,
                       i.answer                      AS answer,
                       i.remark                      AS remark,
                       COALESCE(ti.is_mandatory, 1)  AS isMandatory,
                       i.answered_at                 AS answeredAt,
                       i.answered_by                 AS answeredBy,
                       i.review_state                AS reviewState,
                       i.reviewed_at                 AS reviewedAt,
                       i.reviewed_by                 AS reviewedBy,
                       i.row_state                   AS rowState,
                       i.submitted_at                AS submittedAt,
                       i.submitted_by                AS submittedBy,
                       i.outcome_seen_at             AS outcomeSeenAt,
                       (i.row_state IN ('VERIFIED', 'REJECTED')
                          AND i.outcome_seen_at IS NULL)  AS unseenOutcome,
                       -- Shut for good. `row_state`, not `review_state`: a
                       -- verdict on a row still out for review is the reviewer's
                       -- own work and stays theirs to cycle until they release
                       -- it. That distinction used to need a submitted_at /
                       -- reviewed_at comparison here; the row now carries it.
                       (i.row_state = 'VERIFIED') AS reviewLocked
                  FROM ob_journey_step_items i
                  JOIN ob_journey_steps s ON s.id = i.step_id
             LEFT JOIN ob_journey_template_step_items ti ON ti.id = i.template_item_id
                 WHERE s.journey_id = :journeyId
                 ORDER BY i.step_id, i.sequence, i.id
                """)
                .param("journeyId", journeyId)
                .query((rs, n) -> new ItemRow(
                        rs.getLong("stepId"), rs.getLong("id"), rs.getInt("sequence"),
                        rs.getString("label"), rs.getBoolean("isDone"),
                        nullableBoolean(rs, "answer"), rs.getString("remark"),
                        rs.getBoolean("isMandatory"),
                        // `getObject(.., Instant.class)` is not supported by the
                        // MySQL driver — the same reason this file already has
                        // `instant` for every other timestamp it reads.
                        instant(rs, "answeredAt"),
                        nullableLong(rs, "answeredBy"),
                        ObStepReviewState.valueOf(rs.getString("reviewState")),
                        instant(rs, "reviewedAt"),
                        nullableLong(rs, "reviewedBy"),
                        rs.getBoolean("reviewLocked"),
                        ObStepRowState.valueOf(rs.getString("rowState")),
                        instant(rs, "submittedAt"),
                        nullableLong(rs, "submittedBy"),
                        instant(rs, "outcomeSeenAt"),
                        rs.getBoolean("unseenOutcome")))
                .list().stream()
                .collect(Collectors.groupingBy(ItemRow::stepId,
                        java.util.LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Every required-document entry of every task of one journey, keyed by task.
     *
     * <h2>One query, not two per task</h2>
     *
     * <p>{@code ObJourneyStepLifecycleService.docsFor} answers this for a single
     * task with two reads — the template's document list, and a count of the
     * task's clean attachments. On a stage drawing every task at once that is
     * 2N, so both are folded into one statement here with the count as a
     * correlated subquery.
     *
     * <p><b>Satisfaction is counted, not matched</b>, and that is not a
     * shortcut: {@code ob_journey_step_docs} does not exist, so nothing links
     * one attachment to one checklist entry. The gate can only ask whether
     * enough clean files are attached to cover the required entries. Which
     * entries are marked satisfied is therefore decided in Java below, in
     * sequence, exactly as {@code docsFor} does — a stable order at least means
     * one entry does not read satisfied on one call and outstanding on the next.
     *
     * <p>A task whose {@code template_step_id} is null or gone contributes no
     * rows, which is right: the requirement lived on the template.
     */
    Map<Long, List<DocRow>> docsOfJourney(long journeyId) {
        return jdbc.sql("""
                SELECT s.id        AS stepId,
                       d.id        AS id,
                       d.label     AS label,
                       d.is_required AS isRequired,
                       (SELECT COUNT(*) FROM ob_attachments a
                         WHERE a.step_id = s.id
                           AND a.scan_status = 'CLEAN'
                           AND a.deleted_at IS NULL) AS cleanCount
                  FROM ob_journey_steps s
                  JOIN ob_journey_template_step_docs d ON d.step_id = s.template_step_id
                 WHERE s.journey_id = :journeyId
                 ORDER BY s.id, d.sequence, d.id
                """)
                .param("journeyId", journeyId)
                .query((rs, n) -> new DocRow(
                        rs.getLong("stepId"), rs.getLong("id"), rs.getString("label"),
                        rs.getBoolean("isRequired"), rs.getInt("cleanCount")))
                .list().stream()
                .collect(Collectors.groupingBy(DocRow::stepId,
                        java.util.LinkedHashMap::new, Collectors.toList()));
    }

    /** One required-document entry. {@code cleanCount} is the whole task's. */
    record DocRow(long stepId, long id, String label, boolean isRequired, int cleanCount) {
    }

    /**
     * One sub-task, as the stage body draws it.
     *
     * <p>{@code reviewState}/{@code reviewedAt}/{@code reviewedBy} are the OB
     * Manager's ledger ({@code V20260916_1700}). They are read here and not
     * only on the single-step route because the project page draws the whole
     * journey at once, and a verified row has to look shut there too — the
     * lock is not a property of which screen you are on.
     */
    record ItemRow(long stepId, long id, int sequence, String label,
                   boolean isDone, Boolean answer, String remark,
                   boolean isMandatory, Instant answeredAt, Long answeredBy,
                   ObStepReviewState reviewState, Instant reviewedAt, Long reviewedBy,
                   boolean reviewLocked,
                   ObStepRowState rowState, Instant submittedAt, Long submittedBy,
                   Instant outcomeSeenAt, boolean unseenOutcome) {
    }

    /** `getBoolean` reads a SQL NULL as false, which is the middle state here. */
    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }


    record Row(long id, long obClientId, String clientName, String gateStatus, Long heldByJourneyId,
               Instant startedAt, Instant completedAt, Instant archivedAt,
               long productId, String productCode, String productName, String serviceName,
               long templateId, int templateVersion,
               int stepCount, int stepsSettled, int totalTatDays, String rag) {
    }

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getLong("obClientId"),
            rs.getString("clientName"),
            rs.getString("gateStatus"),
            nullableLong(rs, "heldByJourneyId"),
            instant(rs, "startedAt"),
            instant(rs, "completedAt"),
            instant(rs, "archivedAt"),
            rs.getLong("productId"),
            rs.getString("productCode"),
            rs.getString("productName"),
            rs.getString("serviceName"),
            rs.getLong("templateId"),
            rs.getInt("templateVersion"),
            rs.getInt("stepCount"),
            rs.getInt("stepsSettled"),
            rs.getInt("totalTatDays"),
            rs.getString("rag"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
