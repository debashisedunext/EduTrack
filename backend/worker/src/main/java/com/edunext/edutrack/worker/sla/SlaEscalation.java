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
 * D-020 · escalating one breached ticket, atomically.
 *
 * <p><strong>A separate bean from {@link SlaScanner} on purpose.</strong>
 * {@code @Transactional} is applied by a proxy, so a method called from another
 * method of the same class runs with no transaction at all — silently. The
 * scanner loops over tickets and calls this; keeping it here is what makes the
 * annotation mean anything.
 *
 * <p>The transaction matters because three writes have to agree: the ticket is
 * flagged, the bell entries are written, and the mail is queued. The outbox is
 * {@code REQUIRED} by design (D-010) precisely so a rolled-back escalation
 * cannot leave a phantom mail promising a breach that was never recorded.
 */
@Component
class SlaEscalation {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalation.class);

    private final SlaRepository tickets;
    private final WorkingHoursService workingHours;
    private final EscalationPolicies policies;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    SlaEscalation(SlaRepository tickets,
                  WorkingHoursService workingHours,
                  EscalationPolicies policies,
                  NotificationWriter notifications,
                  OutboxEnqueuer outbox) {
        this.tickets = tickets;
        this.workingHours = workingHours;
        this.policies = policies;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    /** @return true if this call escalated it and sent the alerts */
    @Transactional
    public boolean escalate(SlaRepository.BreachedTicket ticket, Instant now) {
        if (!tickets.escalate(ticket.id(), now)) {
            // It closed between the read and the write, or another pass got
            // there first. Either way it is not ours to announce.
            return false;
        }

        // D-027. How far past the date is a duration, so it goes through the
        // working calendar: "overdue by 62 hours" counts a weekend nobody was
        // working, "overdue by 6 working hours" is a number a manager can act
        // on. Detection stays a plain timestamp comparison — see SlaScanner.
        BigDecimal overdueBy = workingHours.workingHoursBetween(
                ticket.plannedCloseDate().toInstant(), now,
                ticket.projectId(), ticket.assignedTo());

        String title = ticket.ticketCode() + " breached its SLA";
        String body = "Overdue by " + overdueBy.toPlainString()
                + " working hours — raised to Critical.";
        String link = "/tickets/" + ticket.ticketCode();

        // D-024. The matrix decides whether this breach reaches the reporting
        // manager at all — that is what L1 means. The assignee and the project
        // manager are told regardless: they own the work and the project, and
        // switching off an escalation should not switch off the alert.
        EscalationPolicies.Escalation policy = policies.forTicket(
                ticket.projectId(), ticket.taskTypeId(), ticket.level());
        Set<Long> recipients = recipientsOf(ticket, policy.l1());
        Map<Long, String> emails = tickets.emailsOf(recipients);

        if (recipients.isEmpty()) {
            // Flagged, but there is nobody to tell. Logged rather than passed
            // over: a ticket this late with no assignee and no project manager
            // is a triage failure somebody should see.
            log.warn("sla: {} breached with no assignee, manager or project manager to alert",
                    ticket.ticketCode());
        }

        for (long recipient : recipients) {
            // The bell entry is written whatever happens to the mail — it is
            // the record, and D-046 replays it if they are offline.
            notifications.write(new NewNotification(
                    recipient, ticket.id(), NotificationEvent.SLA_BREACHED, title, body, link));

            String address = emails.get(recipient);
            if (address == null) {
                // Deactivated between assignment and breach. The in-app entry
                // above still exists for whoever inherits their queue.
                continue;
            }
            // SLA_BREACHED is mandatory mail (D-036), so no preference can
            // suppress this. The subject prefix is D-031's, added by the
            // enqueuer so this caller cannot forget it.
            outbox.enqueue(NewMail.forTicket(
                    ticket.id(), NotificationEvent.SLA_BREACHED.name(), recipient, address, body));
        }
        return true;
    }

    /**
     * §16 item 3: Reporting Manager, Project Manager and assignee.
     *
     * <p>A set because the three are routinely the same person — a PM who
     * assigned a ticket to themselves, a developer whose manager runs the
     * project — and nobody should be told the same breach three times. Ordered
     * so the assignee comes first, since they are the one who can act on it.
     *
     * <p>Nulls are skipped rather than substituted. An unassigned ticket past
     * its date is D-026's problem, not a reason to invent a recipient.
     *
     * @param l1 D-024: false when the project's matrix says a breach of this
     *           kind does not go up the reporting line
     */
    private static Set<Long> recipientsOf(SlaRepository.BreachedTicket ticket, boolean l1) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (ticket.assignedTo() != null) {
            recipients.add(ticket.assignedTo());
        }
        if (l1 && ticket.reportingManagerId() != null) {
            recipients.add(ticket.reportingManagerId());
        }
        if (ticket.projectManagerId() != null) {
            recipients.add(ticket.projectManagerId());
        }
        return recipients;
    }
}
