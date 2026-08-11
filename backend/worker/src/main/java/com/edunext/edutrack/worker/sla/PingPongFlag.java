package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * D-025 · flagging one ticket that is being bounced between stages.
 *
 * <p>Blueprint §17 lists the risk plainly: <em>teams game the ribbon by
 * bouncing tickets to stop their stage clock</em>, and the mitigation is that
 * <em>the iteration counter makes ping-pong visible on the PM dashboard within
 * a day</em>. This is that visibility, plus the alert §11 asks for.
 *
 * <p><strong>A separate bean from {@link PingPongScanner}</strong>, for the
 * reason {@link SlaEscalation} documents: {@code @Transactional} is applied by
 * a proxy, so a method the scanner called on itself would silently run with no
 * transaction at all.
 *
 * <p><strong>No working-hours maths here, and that is not an oversight.</strong>
 * Every other scanner in this package measures a duration and therefore routes
 * through B-024. This one counts backward moves. There is no elapsed time in
 * the question, so there is nothing for the calendar to correct.
 */
@Component
class PingPongFlag {

    private static final Logger log = LoggerFactory.getLogger(PingPongFlag.class);

    /**
     * §11: <em>Iteration count reaches 3</em>.
     *
     * <p>Three is the ticket's iteration number, not the number of times it has
     * gone backwards. A ticket starts life at iteration 1, so reaching 3 means
     * it has been sent back <em>twice</em> — see {@link #bouncesOf}. Reporting
     * "3 rework loops" here would overstate it by one on every alert, which is
     * the kind of error that survives forever because it always looks plausible.
     */
    static final int ITERATION_THRESHOLD = 3;

    private final PingPongRepository tickets;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    PingPongFlag(PingPongRepository tickets,
                 NotificationWriter notifications,
                 OutboxEnqueuer outbox) {
        this.tickets = tickets;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    /** @return true if this call raised the flag and told somebody */
    @Transactional
    public boolean flag(PingPongRepository.BouncingTicket ticket, Instant now) {
        if (!tickets.claim(ticket.id(), ticket.cycleNo(), ticket.iterationNo(), now)) {
            // Another pass got this iteration first, or the ticket has not
            // bounced again since the last one.
            return false;
        }

        int bounces = bouncesOf(ticket.iterationNo());
        String title = ticket.ticketCode() + " has been sent backwards "
                + bounces + (bounces == 1 ? " time" : " times");
        String body = "Iteration " + ticket.iterationNo() + " of cycle " + ticket.cycleNo()
                + ". Repeated rework usually means the handoff criteria are unclear,"
                + " or the ticket is being returned rather than finished.";
        String link = "/tickets/" + ticket.ticketCode();

        Set<Long> recipients = recipientsOf(ticket);
        if (recipients.isEmpty()) {
            // Claimed regardless — otherwise every pass forever re-evaluates a
            // ticket nobody can be told about. Logged because a ticket bouncing
            // this much on a project with no manager is itself worth seeing.
            log.warn("sla: {} is on iteration {} with no project or reporting manager to tell",
                    ticket.ticketCode(), ticket.iterationNo());
            return false;
        }

        Map<Long, String> emails = tickets.emailsOf(recipients);

        for (long recipient : recipients) {
            // Bell entry, no realtime push: §11 gives this event a bell and an
            // email but deliberately no in-app popup. Interrupting a manager
            // mid-task about a pattern that took days to form is how popups
            // stop being read. That it is achieved by *not* calling the
            // broadcaster is exactly why it is worth writing down.
            notifications.write(new NewNotification(
                    recipient, ticket.id(), NotificationEvent.ITERATION_LIMIT_REACHED,
                    title, body, link));

            String address = emails.get(recipient);
            if (address == null) {
                continue;
            }
            // ITERATION_LIMIT_REACHED is an ESCALATION, so D-036 makes this mail
            // unsuppressable. That follows from the categorisation D-041 already
            // made rather than being decided here: the whole point of the alert
            // is to reach a manager who has not noticed the pattern themselves.
            outbox.enqueue(NewMail.forTicket(
                    ticket.id(), NotificationEvent.ITERATION_LIMIT_REACHED.name(),
                    recipient, address, body));
        }
        return true;
    }

    /** Iteration 1 is a ticket that has never gone backwards. */
    private static int bouncesOf(int iterationNo) {
        return iterationNo - 1;
    }

    /**
     * §11: PM and RM — <strong>not the assignee</strong>.
     *
     * <p>Worth stating because leaving them out looks like an omission. Whoever
     * currently holds the ticket usually did not cause the loop and cannot fix
     * it: ping-pong is a property of the handoff between two stages, and the
     * people who can change that are the ones who own the process. Telling the
     * current holder would also reliably tell the wrong person, since the
     * ticket has by definition just moved.
     */
    private static Set<Long> recipientsOf(PingPongRepository.BouncingTicket ticket) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (ticket.projectManagerId() != null) {
            recipients.add(ticket.projectManagerId());
        }
        if (ticket.reportingManagerId() != null) {
            recipients.add(ticket.reportingManagerId());
        }
        return recipients;
    }
}
