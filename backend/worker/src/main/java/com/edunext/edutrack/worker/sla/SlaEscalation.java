package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.TicketHistory;
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

    /** D-028 · what an escalation calls itself in {@code ticket_history}. */
    private static final String LEVEL_CHANGED = "LEVEL_CHANGED";

    /** The level an SLA breach raises a ticket to. Mirrors {@code SlaRepository.ESCALATE}. */
    private static final String CRITICAL = "CRITICAL";

    private final SlaRepository tickets;
    private final WorkingHoursService workingHours;
    private final EscalationPolicies policies;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;
    private final TicketJournal journal;

    SlaEscalation(SlaRepository tickets,
                  WorkingHoursService workingHours,
                  EscalationPolicies policies,
                  NotificationWriter notifications,
                  OutboxEnqueuer outbox,
                  TicketJournal journal) {
        this.tickets = tickets;
        this.workingHours = workingHours;
        this.policies = policies;
        this.notifications = notifications;
        this.outbox = outbox;
        this.journal = journal;
    }

    /** @return true if this call escalated it and sent the alerts */
    @Transactional
    public boolean escalate(SlaRepository.BreachedTicket ticket, Instant now) {
        if (!tickets.escalate(ticket.id(), now)) {
            // It closed between the read and the write, or another pass got
            // there first. Either way it is not ours to announce.
            return false;
        }

        recordLevelChange(ticket);

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
     * D-028 · record the escalation in {@code ticket_history} as {@code SYSTEM}.
     *
     * <p><strong>Written for one report: "born critical" versus "became
     * critical".</strong> The ticket's {@code level} column only ever holds the
     * current answer, so once a breach raises it to {@code CRITICAL} the fact
     * that it was raised as {@code LOW} survives in exactly two places —
     * {@code original_level}, which says what it started as, and this row, which
     * says when and why it moved. A-070 reads the pair. Without the row the
     * ticket looks as though it was always critical and nobody can say when that
     * became true.
     *
     * <p><strong>{@code original_level} is not touched here, and that is the
     * point.</strong> The schema calls it "never mutated"; {@code ESCALATE}
     * updates {@code level} alone. Writing both would erase the very
     * distinction this row exists to preserve, and it would do so silently,
     * because the ticket would still be perfectly self-consistent afterwards.
     *
     * <p>The actor is deliberately nobody. The journal enforces that
     * {@code actor_id NULL} and {@code actor_type SYSTEM} agree, so this cannot
     * be recorded as a person's decision even by mistake — which matters,
     * because a human name against an automated escalation is the one shape of
     * history that reads plausibly and is false.
     *
     * <p>Not defended against failure: this runs inside {@code escalate}'s
     * transaction, and if the journal rejects the entry the flag, the bell
     * entries and the queued mail must all go back with it. An escalation that
     * announced itself but left no record is precisely what the append-only
     * tables exist to prevent.
     */
    private void recordLevelChange(SlaRepository.BreachedTicket ticket) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.id());
        entry.setCycleNo(ticket.currentCycleNo());
        entry.setEventType(LEVEL_CHANGED);
        entry.setFieldName("level");
        entry.setOldValue(ticket.level());
        entry.setNewValue(CRITICAL);
        entry.setActorType("SYSTEM");
        entry.setActorId(null);
        entry.setRemarks("Planned Close Date passed — escalated automatically by the SLA scanner");
        journal.append(entry);
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
