package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * D-023 · announcing one stage that has outstayed its SLA.
 *
 * <p>A separate bean from {@link StageSlaScanner} for the reason D-020 learned
 * the hard way: {@code @Transactional} is proxy-applied, so a method invoked
 * from another method of the same class runs with no transaction at all, and
 * says nothing about it.
 */
@Component
class StageSlaEscalation {

    private static final Logger log = LoggerFactory.getLogger(StageSlaEscalation.class);

    private final StageSlaRepository stages;
    private final WorkingHoursService workingHours;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    StageSlaEscalation(StageSlaRepository stages,
                       WorkingHoursService workingHours,
                       NotificationWriter notifications,
                       OutboxEnqueuer outbox) {
        this.stages = stages;
        this.workingHours = workingHours;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    /**
     * @return true if this segment had genuinely breached and was announced
     */
    @Transactional
    public boolean escalateIfBreached(StageSlaRepository.OpenStage stage, Instant now) {
        // D-027. The stage budget is denominated in WORKING hours (the column
        // comment says so), so the elapsed side has to be measured the same
        // way. Comparing wall-clock elapsed against a working-hours budget is
        // the Friday-evening bug blueprint §5 warns about, one level down: a
        // ticket handed to QA at 17:00 on Friday with a 4-hour stage SLA would
        // breach before anybody was back at their desk.
        BigDecimal elapsed = workingHours.workingHoursBetween(
                stage.enteredAt().toInstant(), now, stage.projectId(), stage.stageOwnerId());

        if (elapsed.compareTo(stage.slaHours()) < 0) {
            // The SQL prefilter is deliberately generous — it can only be too
            // permissive, never too strict — so this is the real test and most
            // candidates stop here.
            return false;
        }

        BigDecimal over = elapsed.subtract(stage.slaHours());

        if (!stages.claim(stage.transitionId(), over)) {
            // Another instance announced it between the read and here.
            return false;
        }

        String title = stage.ticketCode() + " is stuck in " + stage.stageName();
        String body = elapsed.toPlainString() + " working hours in this stage against a "
                + stage.slaHours().toPlainString() + "-hour SLA — over by "
                + over.toPlainString() + ".";
        String link = "/tickets/" + stage.ticketCode();

        Set<Long> recipients = recipientsOf(stage);
        Map<Long, String> emails = stages.emailsOf(recipients);

        if (recipients.isEmpty()) {
            // A queued stage with no owner and no project manager. Worth
            // seeing: it is the case where a ticket can sit forever with
            // nobody who would ever be told.
            log.warn("stage-sla: {} is stuck in {} with nobody to alert",
                    stage.ticketCode(), stage.stageCode());
        }

        for (long recipient : recipients) {
            notifications.write(new NewNotification(
                    recipient, stage.ticketId(), NotificationEvent.STAGE_SLA_BREACHED,
                    title, body, link));

            String address = emails.get(recipient);
            if (address != null) {
                // STAGE_SLA_BREACHED is an escalation, so D-036 makes the mail
                // unsuppressable and D-031 prefixes the ticket code.
                outbox.enqueue(NewMail.forTicket(
                        stage.ticketId(), NotificationEvent.STAGE_SLA_BREACHED.name(),
                        recipient, address, body));
            }
        }
        return true;
    }

    /**
     * The person holding the stage, their manager, and the project manager.
     *
     * <p>Deliberately the <em>stage owner</em> rather than the ticket's
     * assignee: §16 item 3b's whole point is that a ticket can be well inside
     * its Planned Close Date while rotting in one queue, and the person who can
     * unstick it is whoever it was handed to, not whoever owns the ticket
     * overall.
     */
    private static Set<Long> recipientsOf(StageSlaRepository.OpenStage stage) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (stage.stageOwnerId() != null) {
            recipients.add(stage.stageOwnerId());
        }
        if (stage.reportingManagerId() != null) {
            recipients.add(stage.reportingManagerId());
        }
        if (stage.projectManagerId() != null) {
            recipients.add(stage.projectManagerId());
        }
        return recipients;
    }
}
