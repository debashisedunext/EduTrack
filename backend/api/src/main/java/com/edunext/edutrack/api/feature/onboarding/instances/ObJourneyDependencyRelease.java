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
     * Clears the hold on every journey waiting behind {@code completedJourneyId}.
     *
     * @return the released journey ids — the caller activates each and then
     *         calls {@link #notifyUnblocked}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> release(long completedJourneyId) {
        List<Long> held = jdbc.sql("SELECT id FROM ob_journeys WHERE held_by_journey_id = :id")
                .param("id", completedJourneyId).query(Long.class).list();
        if (held.isEmpty()) {
            return List.of();
        }
        jdbc.sql("""
                UPDATE ob_journeys
                   SET held_by_journey_id = NULL, released_at = :now
                 WHERE held_by_journey_id = :id
                """)
                .param("now", Timestamp.from(Instant.now()))
                .param("id", completedJourneyId)
                .update();
        return held;
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
