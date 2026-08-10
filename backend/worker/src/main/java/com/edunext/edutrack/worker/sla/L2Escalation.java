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

/**
 * D-024 · the second escalation level.
 *
 * <p>Blueprint §6: <em>L1 at breach, L2 after 48 h beyond PCD to the RM's
 * manager.</em> L1 is D-020 telling the reporting manager the moment a ticket
 * goes late. This is what happens when that produced nothing for two more
 * working days.
 */
@Component
class L2Escalation {

    private static final Logger log = LoggerFactory.getLogger(L2Escalation.class);

    /**
     * §6's figure, in working hours like everything else here.
     *
     * <p>Forty-eight <em>working</em> hours is a bit over five working days on
     * a nine-hour calendar, not two — which is the right reading: an escalation
     * that fires over a weekend nobody was working reaches a manager about a
     * ticket their team had no opportunity to touch, and that is how the second
     * level stops being believed.
     */
    static final BigDecimal OVERDUE_WORKING_HOURS = new BigDecimal("48");

    private final L2EscalationRepository tickets;
    private final EscalationPolicies policies;
    private final WorkingHoursService workingHours;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    L2Escalation(L2EscalationRepository tickets,
                 EscalationPolicies policies,
                 WorkingHoursService workingHours,
                 NotificationWriter notifications,
                 OutboxEnqueuer outbox) {
        this.tickets = tickets;
        this.policies = policies;
        this.workingHours = workingHours;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    /** @return true if this ticket qualified and the escalation was sent */
    @Transactional
    public boolean escalateIfDue(L2EscalationRepository.OverdueTicket ticket, Instant now) {
        EscalationPolicies.Escalation policy = policies.forTicket(
                ticket.projectId(), ticket.taskTypeId(), ticket.level());

        if (!policy.l2()) {
            // Off unless a project asked for it — see EscalationPolicies for
            // why that is the default rather than a decision made here.
            return false;
        }

        // D-027, and the reason the SQL only prefilters.
        BigDecimal overdueBy = workingHours.workingHoursBetween(
                ticket.plannedCloseDate().toInstant(), now,
                ticket.projectId(), ticket.assignedTo());

        if (overdueBy.compareTo(OVERDUE_WORKING_HOURS) < 0) {
            return false;
        }

        if (ticket.escalateToId() == null) {
            // The chain ran out: the assignee's manager has no manager, or the
            // ticket lost its assignee since it breached. Claimed anyway, with
            // a null target — otherwise every pass forever re-evaluates a
            // ticket nobody can be told about, and the log fills with it.
            tickets.claim(ticket.id(), null, overdueBy);
            log.warn("sla: {} is {} working hours overdue with no second-level manager to escalate to",
                    ticket.ticketCode(), overdueBy.toPlainString());
            return false;
        }

        if (!tickets.claim(ticket.id(), ticket.escalateToId(), overdueBy)) {
            return false;
        }

        String title = ticket.ticketCode() + " is still open " + overdueBy.toPlainString()
                + " working hours past its close date";
        String body = "Escalated to you because it has been overdue for more than "
                + OVERDUE_WORKING_HOURS.toPlainString()
                + " working hours since the first escalation.";
        String link = "/tickets/" + ticket.ticketCode();

        notifications.write(new NewNotification(
                ticket.escalateToId(), ticket.id(), NotificationEvent.SLA_BREACHED,
                title, body, link));

        tickets.emailOf(ticket.escalateToId()).ifPresent(address ->
                outbox.enqueue(NewMail.forTicket(
                        ticket.id(), NotificationEvent.SLA_BREACHED.name(),
                        ticket.escalateToId(), address, body)));

        return true;
    }
}
