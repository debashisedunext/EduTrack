package com.edunext.edutrack.worker.onboarding.escalation;

import com.edunext.edutrack.domain.journal.ObStepJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C-115 · raising one rung of the ladder for one breached step, atomically.
 *
 * <p>A separate bean from {@link ObEscalationScanner}, {@code ObTatBreach}'s
 * exact reason one class over: {@code @Transactional} is a proxy concern, so
 * a method the scanner calls on itself would run with no transaction at all.
 * The insert, the history row and the queued mail have to commit together —
 * the outbox is {@code REQUIRED} by design (B-110) precisely so a rolled
 * back rung leaves no phantom "you have been escalated" mail behind it.
 */
@Component
class ObEscalationRaise {

    private static final Logger log = LoggerFactory.getLogger(ObEscalationRaise.class);

    /** {@code ob_escalations.reason} — the only trigger this scanner knows; §5.11's Amber and blocked-too-long rungs are not built here. */
    private static final String TAT_BREACH = "TAT_BREACH";

    /** {@code ob_step_history.event_type} — a second door on {@code ObStepJournal}'s own invitation, {@code ObTatBreach.TAT_BREACHED}'s precedent. */
    private static final String ESCALATION_RAISED = "ESCALATION_RAISED";

    private final ObEscalationLadderRepository escalations;
    private final WorkingHoursService workingHours;
    private final ObStepJournal journal;
    private final ObOutboxEnqueuer outbox;

    ObEscalationRaise(ObEscalationLadderRepository escalations,
                       WorkingHoursService workingHours,
                       ObStepJournal journal,
                       ObOutboxEnqueuer outbox) {
        this.escalations = escalations;
        this.workingHours = workingHours;
        this.journal = journal;
        this.outbox = outbox;
    }

    /** @return true if this call raised the rung and recorded/queued it */
    @Transactional
    public boolean raise(ObEscalationLadderRepository.Candidate step, ObEscalationLevel level, Instant now) {
        Long escalatedTo = resolveRecipient(step, level);

        if (!escalations.insert(step, level, TAT_BREACH, escalatedTo, now)) {
            // Another pass already raised this rung. Not ours to announce.
            return false;
        }

        BigDecimal overdueBy = workingHours.workingHoursBetween(step.tatBreachedAt(), now);
        recordHistory(step, level, now, overdueBy);
        enqueueNotification(step, level, escalatedTo, overdueBy);
        return true;
    }

    /**
     * L1 → the owner, L2 → the owner's reporting manager, L3 → OB Admin —
     * the seeded ladder (plan §5.11, PHASE-2-BUILD-PLAN §2). Fixed here
     * rather than read from configuration: no settings table exists yet for
     * B-113 to have populated (C-114's own boundary, named rather than built
     * past), so this is the same interim constant {@code
     * ObJourneyStepRagService}'s amber threshold is.
     *
     * <p>Resolving to {@code null} is allowed and is deliberately not
     * substituted — the migration's own words are "itself worth seeing on
     * the dashboard".
     */
    private Long resolveRecipient(ObEscalationLadderRepository.Candidate step, ObEscalationLevel level) {
        return switch (level) {
            case L1 -> step.ownerUserId();
            case L2 -> step.managerUserId();
            case L3 -> escalations.resolveObAdmin();
        };
    }

    /** {@code SYSTEM}, on {@code ObTatBreach.recordHistory}'s identical reasoning. */
    private void recordHistory(ObEscalationLadderRepository.Candidate step, ObEscalationLevel level,
                               Instant now, BigDecimal overdueBy) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(step.journeyId());
        entry.setStepId(step.stepId());
        entry.setObClientId(step.obClientId());
        entry.setEventType(ESCALATION_RAISED);
        entry.setFieldName("level");
        entry.setNewValue(level.name());
        entry.setActorType("SYSTEM");
        entry.setActorId(null);
        entry.setRemarks("Escalated to " + level + " — overdue by "
                + overdueBy.toPlainString() + " working hours since breach.");
        journal.append(entry);
    }

    /**
     * One recipient — {@code ObEscalation.escalatedTo} names exactly one,
     * unlike {@code ObTatBreach}'s owner-plus-backup set. Nothing to send if
     * the matrix resolved nobody; logged, on the same reasoning.
     */
    private void enqueueNotification(ObEscalationLadderRepository.Candidate step, ObEscalationLevel level,
                                     Long escalatedTo, BigDecimal overdueBy) {
        if (escalatedTo == null) {
            log.warn("ob-escalation: step {} raised to {} with nobody resolved to tell", step.stepId(), level);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", step.clientName());
        payload.put("step_title", step.stepName());
        payload.put("escalation_level", level.name());
        payload.put("product_name", step.productName());
        payload.put("overdue_by", overdueBy.toPlainString() + " working hours");

        ObRecipient.Staff staff = new ObRecipient.Staff(escalatedTo);
        for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
            outbox.enqueue(ObNotification.aboutStep(
                    ObNotificationEvent.ESCALATION_RAISED.key(), channel, staff,
                    step.obClientId(), step.journeyId(), step.stepId(), payload));
        }
    }
}
