package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * D-026 · telling triage about a ticket nobody has picked up.
 *
 * <p>Blueprint §11 gives the event its audience — PM and Support Desk — and
 * §17 gives it its purpose: a ticket that sits unassigned is invisible to every
 * other engine here. D-020 needs a Planned Close Date to breach, D-022 skips
 * unassigned tickets by design, and D-025 needs a ribbon that is moving. This
 * is the one alert that fires when <em>nothing</em> is happening.
 *
 * <p>A separate bean from the scanner, for the proxy reason
 * {@link SlaEscalation} sets out.
 */
@Component
class UnassignedTicketAlert {

    private static final Logger log = LoggerFactory.getLogger(UnassignedTicketAlert.class);

    /**
     * Two <em>working</em> hours (D-027), not two wall-clock hours.
     *
     * <p>The difference is the whole reason the calendar exists: a ticket
     * raised at 17:30 on Friday is not a triage failure at 19:30 on Friday, and
     * an alert that says it is trains the desk to ignore the ones that arrive
     * on a Tuesday morning and genuinely have been missed.
     */
    static final BigDecimal UNASSIGNED_WORKING_HOURS = new BigDecimal("2");

    private final UnassignedTicketRepository tickets;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    UnassignedTicketAlert(UnassignedTicketRepository tickets,
                          WorkingHoursService workingHours,
                          WorkingCalendarRepository calendars,
                          NotificationWriter notifications,
                          OutboxEnqueuer outbox) {
        this.tickets = tickets;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    /** @return true if this call alerted triage */
    @Transactional
    public boolean alertIfUntouched(UnassignedTicketRepository.UnassignedTicket ticket, Instant now) {
        Instant reported = ticket.reportedAt().toInstant();

        BigDecimal waited = workingHours.workingHoursBetween(
                reported, now, ticket.projectId(), null);

        if (waited.compareTo(UNASSIGNED_WORKING_HOURS) < 0) {
            return false;
        }

        if (!tickets.claim(ticket.id(), now, repeatableIfBefore(ticket, now))) {
            return false;
        }

        String title = ticket.ticketCode() + " has been unassigned for "
                + waited.setScale(1, RoundingMode.DOWN).toPlainString() + " working hours";
        String body = "Raised " + reported.toString().substring(0, 16).replace('T', ' ')
                + " UTC at " + ticket.level() + " and nobody owns it yet.";
        String link = "/tickets/" + ticket.ticketCode();

        Set<Long> recipients = recipientsOf(ticket);
        if (recipients.isEmpty()) {
            // Claimed anyway: re-evaluating a ticket nobody can be told about,
            // on every pass forever, fills the log and changes nothing. A
            // project with neither a manager nor a support member is itself the
            // thing to fix, and this is where it becomes visible.
            log.warn("triage: {} is unassigned with no project manager or support desk to tell",
                    ticket.ticketCode());
            return false;
        }

        Map<Long, String> emails = tickets.emailsOf(recipients);

        for (long recipient : recipients) {
            notifications.write(new NewNotification(
                    recipient, ticket.id(), NotificationEvent.NEW_UNASSIGNED_TICKET,
                    title, body, link));

            String address = emails.get(recipient);
            if (address == null) {
                continue;
            }
            // NEW_UNASSIGNED_TICKET is an ASSIGNMENT event, so D-036 makes this
            // mail unsuppressable. Right for this one: a desk that has switched
            // off "nobody has picked this up" is a desk that stops noticing.
            outbox.enqueue(NewMail.forTicket(
                    ticket.id(), NotificationEvent.NEW_UNASSIGNED_TICKET.name(),
                    recipient, address, body));
        }
        return true;
    }

    /**
     * The instant a previous alert must predate for another to be due.
     *
     * <p>One working day, so a ticket still in the queue tomorrow morning is
     * raised again — the D-022 argument, that an alert sent once and never
     * repeated is one that gets ignored once and never repeated.
     *
     * <p><strong>{@link Instant#EPOCH} when this pass saw no previous alert</strong>,
     * which is not the same as "no alert exists". If the insert then loses a
     * race, a row appeared between the read and the write and another worker
     * has just alerted; returning {@code now} here would make the fallback
     * {@code UPDATE}'s guard vacuously true and tell the desk twice. That is
     * precisely the defect D-025 exposed in D-022, and it is not being written
     * a second time.
     */
    private Instant repeatableIfBefore(UnassignedTicketRepository.UnassignedTicket ticket,
                                       Instant now) {
        if (ticket.lastAlertedAt() == null) {
            return Instant.EPOCH;
        }
        Instant lastAlerted = ticket.lastAlertedAt().toInstant();
        BigDecimal sinceLastAlert = workingHours.workingHoursBetween(
                lastAlerted, now, ticket.projectId(), null);

        return sinceLastAlert.compareTo(oneWorkingDay()) >= 0 ? lastAlerted : Instant.EPOCH;
    }

    /**
     * A working day in hours, from the calendar rather than written as nine.
     *
     * <p>B-024 measures in hours and has no day count; an org on a seven-hour
     * day would otherwise find "daily" quietly meaning something else.
     */
    private BigDecimal oneWorkingDay() {
        long minutes = calendars.getCalendar().workDayLength().toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * §11: PM and Support Desk.
     *
     * <p>The desk is the project's support members rather than every support
     * agent in the company — see the repository for why mailing somebody about
     * a ticket their scope would 404 is worse than not mailing them at all.
     * Ordered with the PM first, and a set because one person can easily be
     * both.
     */
    private Set<Long> recipientsOf(UnassignedTicketRepository.UnassignedTicket ticket) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (ticket.projectManagerId() != null) {
            recipients.add(ticket.projectManagerId());
        }
        recipients.addAll(tickets.supportDeskFor(ticket.projectId()));
        return recipients;
    }
}
