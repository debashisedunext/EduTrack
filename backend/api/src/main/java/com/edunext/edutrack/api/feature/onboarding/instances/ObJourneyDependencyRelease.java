package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-123 · the service-level hold's other end. A journey instantiated behind
 * a dependency (plan §5.5) carries {@code held_by_journey_id}; when that
 * journey completes, every journey it was holding is released — the column
 * cleared, {@code released_at} stamped — and the caller activates its first
 * wave. This class owns the release and the notification;
 * {@link ObJourneyStepLifecycleService} owns the activation, because that is
 * where {@code activateEligibleSteps} lives and where a journey's completion
 * is detected.
 *
 * <h2>One column, a set of dependencies</h2>
 *
 * <p>A service waits behind <em>several</em> others since
 * {@code V20260911_1100}, and {@code held_by_journey_id} still holds one id.
 * It is a cursor over that set rather than the whole of it: instantiation
 * points it at the first outstanding holder, and when that holder completes
 * {@link #release} asks whether any of the journey's <em>other</em>
 * dependencies is still running. If one is, the column is re-pointed at it
 * and the journey is <b>not</b> released; only when none is left does the
 * column clear and {@code released_at} stamp.
 *
 * <p>The alternative — a second join table holding one row per outstanding
 * hold — was not written because this needs no schema of its own and cannot
 * drift from the declaration: the outstanding set is recomputed from
 * {@code ob_journey_template_dependencies} every time, so an admin adding a
 * dependency to a service is reflected at the next release rather than
 * leaving stale hold rows behind. What it costs is that a journey's full set
 * of outstanding holders is not visible on the row — only the one it is
 * waiting on next — which no screen asks for.
 *
 * <p><b>The trap this avoids</b> is the one-line version of the same code:
 * clear every journey whose {@code held_by_journey_id} matches. With a set of
 * dependencies that starts every journey behind the first of its three
 * dependencies to finish, which is exactly the hold the feature exists to
 * enforce and exactly the bug nobody sees until a client's journeys run in
 * the wrong order.
 *
 * <p>Raw {@code JdbcClient} rather than {@code ObJourneyRepository}, on
 * {@link ObPrerequisiteGateService}'s own reasoning: "every journey held by
 * this one" is not a caller-scoped read, and the caller has already proven
 * standing over the journey it just completed.
 */
@Component
public class ObJourneyDependencyRelease {

    private final JdbcClient jdbc;
    private final ObJourneyStepRepository journeySteps;
    private final ObOutboxEnqueuer outbox;

    public ObJourneyDependencyRelease(JdbcClient jdbc, ObJourneyStepRepository journeySteps,
            ObOutboxEnqueuer outbox) {
        this.jdbc = jdbc;
        this.journeySteps = journeySteps;
        this.outbox = outbox;
    }

    /**
     * Settles every journey that was waiting on {@code completedJourneyId}:
     * released if that was its last outstanding dependency, re-pointed at the
     * next one if it was not.
     *
     * @return only the journeys actually released — a re-pointed one is still
     *         held, so the caller must not activate its steps or announce it
     *         as unblocked
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> release(long completedJourneyId) {
        List<Map<String, Object>> waiting = jdbc.sql("""
                SELECT id, ob_client_id, template_id
                  FROM ob_journeys
                 WHERE held_by_journey_id = :id
                """)
                .param("id", completedJourneyId).query().listOfRows();
        if (waiting.isEmpty()) {
            return List.of();
        }

        Timestamp now = Timestamp.from(Instant.now());
        List<Long> released = new ArrayList<>();
        for (Map<String, Object> row : waiting) {
            long journeyId = ((Number) row.get("id")).longValue();
            long obClientId = ((Number) row.get("ob_client_id")).longValue();
            long templateId = ((Number) row.get("template_id")).longValue();

            Long nextHolder = outstandingHolder(obClientId, templateId, completedJourneyId);
            if (nextHolder != null) {
                // Still held, by a different dependency. `released_at` stays
                // null: the journey has not been released, and stamping it
                // here would make the timestamp mean "the first of its
                // dependencies finished" on some rows and "it started" on
                // others.
                jdbc.sql("UPDATE ob_journeys SET held_by_journey_id = :holder WHERE id = :id")
                        .param("holder", nextHolder).param("id", journeyId).update();
                continue;
            }
            jdbc.sql("""
                    UPDATE ob_journeys
                       SET held_by_journey_id = NULL, released_at = :now
                     WHERE id = :id
                    """)
                    .param("now", now).param("id", journeyId).update();
            released.add(journeyId);
        }
        return released;
    }

    /**
     * Another of {@code templateId}'s declared dependencies that this client
     * still has running, or {@code null} if none is left.
     *
     * <p>Resolved through the dependency's <b>service</b> — its
     * {@code (product_id, name)} — and not its template row id, exactly as
     * {@code ObJourneyInstantiationService#holdingJourneysFor} does at birth,
     * and for that method's reason: a dependency that published a new version
     * would otherwise stop matching the journey the client is actually
     * running, and every dependent journey would fall through as unheld.
     *
     * <p>{@code completedJourneyId} is excluded explicitly rather than relied
     * on to have a {@code completed_at} by now. It does — the caller sets it
     * before calling — but a check that only works because of an ordering two
     * classes apart is a check that breaks silently when the ordering moves.
     *
     * <p>The inner {@code ORDER BY ... LIMIT 1} picks the client's newest
     * journey per dependency service, which is the same row
     * {@code findFirstBy…OrderByIdDesc} returns at instantiation. Without it a
     * client re-boarded onto a service would have an old completed journey
     * counted as a live hold, or the reverse.
     */
    private Long outstandingHolder(long obClientId, long templateId, long completedJourneyId) {
        return jdbc.sql("""
                SELECT h.id
                  FROM ob_journey_template_dependencies d
                  JOIN ob_journey_templates dep ON dep.id = d.depends_on_template_id
                  JOIN ob_journeys h ON h.id = (
                           SELECT h2.id
                             FROM ob_journeys h2
                            WHERE h2.ob_client_id = :clientId
                              AND h2.product_id = dep.product_id
                              AND h2.service_name = dep.name
                              AND h2.archived_at IS NULL
                            ORDER BY h2.id DESC
                            LIMIT 1)
                 WHERE d.template_id = :templateId
                   AND h.id <> :completedId
                   AND h.completed_at IS NULL
                 ORDER BY h.id
                 LIMIT 1
                """)
                .param("clientId", obClientId)
                .param("templateId", templateId)
                .param("completedId", completedJourneyId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /**
     * {@code JOURNEY_UNBLOCKED} (§5 item 5: "completion unlocks it
     * automatically with a notification") to every owner whose step just
     * activated — {@code ObPrerequisiteGateService#notifyGateOpened}'s shape.
     */
    public void notifyUnblocked(long journeyId, long dependencyJourneyId) {
        Map<String, Object> names = jdbc.sql("""
                SELECT c.name AS clientName, p.name AS productName, j.ob_client_id AS clientId,
                       (SELECT dp.name FROM ob_journeys dj JOIN ob_products dp ON dp.id = dj.product_id
                         WHERE dj.id = :dependencyId) AS dependsOn
                  FROM ob_journeys j
                  JOIN ob_clients c ON c.id = j.ob_client_id
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.id = :id
                """)
                .param("id", journeyId).param("dependencyId", dependencyJourneyId)
                .query().singleRow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", names.get("clientName"));
        payload.put("product_name", names.get("productName"));
        payload.put("depends_on_product", names.get("dependsOn"));
        long obClientId = ((Number) names.get("clientId")).longValue();

        Set<Long> owners = new LinkedHashSet<>();
        for (ObJourneyStep step : journeySteps.findByJourneyIdOrderBySequenceAsc(journeyId)) {
            if (step.getStatus() == ObJourneyStepStatus.IN_PROGRESS && step.getOwnerUserId() != null) {
                owners.add(step.getOwnerUserId());
                payload.putIfAbsent("first_step_title", step.getName());
            }
        }
        for (long owner : owners) {
            ObRecipient.Staff staff = new ObRecipient.Staff(owner);
            for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
                outbox.enqueue(new ObNotification(ObNotificationEvent.JOURNEY_UNBLOCKED.key(), channel, staff,
                        obClientId, journeyId, null, payload,
                        ObNotification.dedupeKeyFor(ObNotificationEvent.JOURNEY_UNBLOCKED.key(), channel,
                                "journey", journeyId, staff)));
            }
        }
    }
}
