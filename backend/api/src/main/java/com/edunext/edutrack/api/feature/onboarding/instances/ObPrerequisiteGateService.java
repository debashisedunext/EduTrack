package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.api.feature.onboarding.prereqs.ObPrereqGate;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
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
 * C-118 · the real {@link ObPrereqGate} — plan §5.3's four consequences, the
 * ones {@code ObPrereqGateReadOnly} named and declined to build: flip every
 * {@code LOCKED} journey {@code OPEN}, activate its dependency-free steps,
 * start their clocks, fire the kickoff mail. Registers itself by existing:
 * {@code ObPrereqGateConfiguration}'s {@code @ConditionalOnMissingBean} backs
 * off the moment this {@code @Component} is on the classpath, so nothing
 * about the seam or its caller ({@code ObPrereqTaskService#settle}) changes.
 *
 * <h2>Why this class, not {@code ObJourneyRepository}, touches the journeys</h2>
 *
 * <p>{@code ObJourneyRepository} is read only through {@code ScopedJourneys}
 * — {@code ScopeGuardRulesTest} fails the build on any other caller in
 * {@code api}, and rightly: a caller-scoped read is exactly what "every
 * journey of this client" is not here. This class instead reads and flips
 * {@code ob_journeys} through a plain guarded {@code UPDATE}, {@code
 * ObTatBreach}'s own idiom one module over ("a guarded UPDATE, not a side
 * claims table") — the caller into this method has already proven standing
 * over {@code obClientId} itself (verifying or skipping one of its own
 * prerequisite tasks), so there is no second caller identity to re-scope by,
 * the same reasoning {@code ObJourneyStepLifecycleService}'s own {@code
 * @UnscopedAccess} gives for its narrower single-journey read.
 *
 * <h2>Idempotency mirrors the fallback it replaces</h2>
 *
 * <p>{@code openedNow = satisfied && !wasCleared} is {@code
 * ObPrereqGateReadOnly}'s own guard, copied rather than shared — the header
 * moves {@code CLEARED} once, forward only, so a second settle on an
 * already-cleared client changes nothing here either. {@code
 * gate_status = 'LOCKED'} on the {@code UPDATE} itself is a second,
 * belt-and-braces guard: even if this method were ever entered twice for the
 * same transition, it can flip a journey at most once.
 */
@Service
public class ObPrerequisiteGateService implements ObPrereqGate {

    private final JdbcClient jdbc;
    private final ObJourneyStepLifecycleService lifecycle;
    private final ObJourneyStepRepository journeySteps;
    private final ObClientPrereqsRepository prereqHeaders;
    private final ObOutboxEnqueuer outbox;

    public ObPrerequisiteGateService(JdbcClient jdbc, ObJourneyStepLifecycleService lifecycle,
            ObJourneyStepRepository journeySteps, ObClientPrereqsRepository prereqHeaders,
            ObOutboxEnqueuer outbox) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
        this.journeySteps = journeySteps;
        this.prereqHeaders = prereqHeaders;
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome evaluate(long obClientId, List<ObClientPrereqTask> tasks) {
        boolean satisfied = ObPrereqGate.isSatisfiedBy(tasks);

        ObClientPrereqs header = prereqHeaders.findByObClientId(obClientId).orElse(null);
        boolean wasCleared = header != null && header.getStatus() == ObClientPrereqs.Status.CLEARED;
        if (!satisfied || wasCleared) {
            return new Outcome(gateStatusOf(obClientId), false, List.of());
        }

        if (header != null) {
            header.setStatus(ObClientPrereqs.Status.CLEARED);
            header.setClearedAt(Instant.now());
            prereqHeaders.save(header);
        }

        List<Long> lockedJourneyIds = jdbc.sql("""
                SELECT id FROM ob_journeys
                 WHERE ob_client_id = :clientId AND gate_status = 'LOCKED'
                """).param("clientId", obClientId).query(Long.class).list();

        // "This transition opened it" — the header's once-only CLEARED stamp
        // when there is a header, and otherwise the fact that a journey
        // flipped. A client with no checklist row and no locked journey has
        // nothing this call opened, and saying so keeps gateOpened true at
        // most once, which is what the record's own contract promises.
        boolean openedNow = header != null || !lockedJourneyIds.isEmpty();

        if (!lockedJourneyIds.isEmpty()) {
            jdbc.sql("""
                    UPDATE ob_journeys
                       SET gate_status = 'OPEN', gate_opened_at = :now
                     WHERE id IN (:ids) AND gate_status = 'LOCKED'
                    """)
                    .param("now", Timestamp.from(Instant.now()))
                    .param("ids", lockedJourneyIds)
                    .update();

            for (long journeyId : lockedJourneyIds) {
                // Step activation re-asserts gate OPEN — plan §5.3's own
                // defence in depth. The journey row this method just wrote
                // is what that re-assertion reads: same transaction, same
                // connection, the UPDATE above is visible to it uncommitted.
                lifecycle.activateEligibleSteps(journeyId);
            }

            notifyGateOpened(obClientId, lockedJourneyIds);
        }

        return new Outcome(gateStatusOf(obClientId), openedNow, List.copyOf(lockedJourneyIds));
    }

    /** {@code ObJourneyGateReader#gateStatusOf}'s own rule, read locally rather than reached for across packages. */
    private ObGateStatus gateStatusOf(long obClientId) {
        Long lockedCount = jdbc.sql("""
                SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = :clientId AND gate_status = 'LOCKED'
                """).param("clientId", obClientId).query(Long.class).single();
        Long total = jdbc.sql("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = :clientId")
                .param("clientId", obClientId).query(Long.class).single();
        if (total == 0L) {
            return ObGateStatus.LOCKED;
        }
        return lockedCount > 0 ? ObGateStatus.LOCKED : ObGateStatus.OPEN;
    }

    /**
     * The kickoff mail — {@code GATE_OPENED}, to the SPOC and to every owner
     * whose step just activated. {@code ObTatBreach#enqueueNotifications}'s
     * own shape one module over: a small payload built from facts this
     * method already has, one row per recipient per channel.
     *
     * <p>{@code journey_count} is the only optional variable populated —
     * {@code product_names}/{@code first_step_title} would need a join this
     * first pass does not otherwise make, named as a boundary rather than
     * built past it, on {@code ObStepTatBudget}'s own precedent for stating
     * one plainly rather than leaving it to be discovered.
     */
    private void notifyGateOpened(long obClientId, List<Long> openedJourneyIds) {
        String clientName = jdbc.sql("SELECT name FROM ob_clients WHERE id = :id")
                .param("id", obClientId).query(String.class).optional().orElse("this client");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", clientName);
        payload.put("journey_count", openedJourneyIds.size());

        Long spocContactId = jdbc.sql("""
                SELECT id FROM ob_client_contacts WHERE ob_client_id = :id AND is_primary_key = 1
                """).param("id", obClientId).query(Long.class).optional().orElse(null);
        if (spocContactId != null) {
            outbox.enqueue(ObNotification.aboutClient(
                    ObNotificationEvent.GATE_OPENED.key(), ObChannel.EMAIL,
                    new ObRecipient.Client(spocContactId), obClientId, payload));
        }

        Set<Long> ownerIds = new LinkedHashSet<>();
        for (long journeyId : openedJourneyIds) {
            for (ObJourneyStep step : journeySteps.findByJourneyIdOrderBySequenceAsc(journeyId)) {
                // Every step here was PENDING until activateEligibleSteps just
                // ran — the journey was LOCKED, and C-103's own account is
                // that nothing runs while LOCKED — so IN_PROGRESS here means
                // this call is what activated it, not a step already running.
                if (step.getStatus() == ObJourneyStepStatus.IN_PROGRESS && step.getOwnerUserId() != null) {
                    ownerIds.add(step.getOwnerUserId());
                }
            }
        }
        for (long ownerId : ownerIds) {
            ObRecipient.Staff staff = new ObRecipient.Staff(ownerId);
            for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
                outbox.enqueue(ObNotification.aboutClient(
                        ObNotificationEvent.GATE_OPENED.key(), channel, staff, obClientId, payload));
            }
        }
    }
}
