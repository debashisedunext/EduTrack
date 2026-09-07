package com.edunext.edutrack.worker.onboarding.tat;

import com.edunext.edutrack.domain.journal.ObStepJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C-113 · flagging one overdue step, atomically.
 *
 * <p>A separate bean from {@link ObTatScanner} on {@code SlaEscalation}'s
 * exact precedent: {@code @Transactional} is applied by a proxy, so a method
 * called from another method of the same class runs with no transaction at
 * all. The scanner loops and calls this; keeping the transaction here is
 * what makes it real.
 *
 * <p>Three things happen together inside it — the flag, the {@code
 * ob_step_history} row, and the queued notifications — because {@link
 * ObOutboxEnqueuer#enqueue} is {@code REQUIRED} by design (B-110): a rolled
 * back flag must not leave a phantom "your step breached" mail queued behind
 * it, and a flag that commits must not lose its mail to a broker that was
 * briefly unreachable.
 */
@Component
class ObTatBreach {

    private static final Logger log = LoggerFactory.getLogger(ObTatBreach.class);

    /** {@code ob_step_history.event_type} — free text (VARCHAR(40)), on {@code ObStepJournal}'s own invitation to extend it with a second event type. */
    private static final String TAT_BREACHED = "TAT_BREACHED";

    private static final DateTimeFormatter DUE_ON = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final ObTatRepository steps;
    private final WorkingHoursService workingHours;
    private final ObStepJournal journal;
    private final ObOutboxEnqueuer outbox;
    private final ZoneId zone;

    ObTatBreach(ObTatRepository steps,
                WorkingHoursService workingHours,
                ObStepJournal journal,
                ObOutboxEnqueuer outbox,
                @Value("${edutrack.onboarding.tat.zone:${edutrack.digest.zone:Asia/Kolkata}}") String zone) {
        this.steps = steps;
        this.workingHours = workingHours;
        this.journal = journal;
        this.outbox = outbox;
        this.zone = ZoneId.of(zone);
    }

    /** @return true if this call claimed the breach and recorded/queued it */
    @Transactional
    public boolean flag(ObTatRepository.OverdueStep step, Instant now) {
        if (!steps.flagBreached(step.stepId(), now)) {
            // Already flagged by an earlier pass, or another replica got
            // there first. Either way it is not ours to announce again.
            return false;
        }

        BigDecimal overdueBy = workingHours.workingHoursBetween(step.dueAt(), now);
        recordHistory(step, now, overdueBy);
        enqueueNotifications(step, overdueBy);
        return true;
    }

    /**
     * The narrative record — {@code SlaEscalation.recordLevelChange}'s own
     * shape, one module over. The actor is deliberately nobody: {@link
     * ObStepHistory#getActorType()}'s own comment anticipates exactly this
     * caller ("an escalation or a scanner, not a person").
     */
    private void recordHistory(ObTatRepository.OverdueStep step, Instant now, BigDecimal overdueBy) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(step.journeyId());
        entry.setStepId(step.stepId());
        entry.setObClientId(step.obClientId());
        entry.setEventType(TAT_BREACHED);
        entry.setFieldName("tat_breached_at");
        entry.setNewValue(now.toString());
        entry.setActorType("SYSTEM");
        entry.setActorId(null);
        entry.setRemarks("Due date passed — flagged automatically by the TAT scanner. Overdue by "
                + overdueBy.toPlainString() + " working hours.");
        journal.append(entry);
    }

    /**
     * The owner and the backup owner (C-104's snapshot; C-108's own
     * assignment surface is not built, but the column already is) — a set,
     * on {@code SlaEscalation.recipientsOf}'s own reasoning: the two are
     * routinely the same person early in a step's life, and nobody should
     * be told the same breach twice. Each gets a mail and a bell entry.
     */
    private void enqueueNotifications(ObTatRepository.OverdueStep step, BigDecimal overdueBy) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (step.ownerUserId() != null) {
            recipients.add(step.ownerUserId());
        }
        if (step.backupOwnerUserId() != null) {
            recipients.add(step.backupOwnerUserId());
        }

        if (recipients.isEmpty()) {
            // Flagged, but nobody to tell. A step this late with no owner
            // resolved is a triage failure somebody should see — logged
            // rather than passed over, on SlaEscalation's own precedent.
            log.warn("ob-tat: step {} breached with no owner or backup owner to alert", step.stepId());
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", step.clientName());
        payload.put("step_title", step.stepName());
        payload.put("overdue_by", overdueBy.toPlainString() + " working hours");
        payload.put("product_name", step.productName());
        payload.put("due_on", DUE_ON.format(LocalDateTime.ofInstant(step.dueAt(), zone)));

        for (long recipient : recipients) {
            ObRecipient.Staff staff = new ObRecipient.Staff(recipient);
            for (ObChannel channel : new ObChannel[] {ObChannel.EMAIL, ObChannel.IN_APP}) {
                outbox.enqueue(ObNotification.aboutStep(
                        ObNotificationEvent.TAT_BREACHED.key(), channel, staff,
                        step.obClientId(), step.journeyId(), step.stepId(), payload));
            }
        }
    }
}
