package com.edunext.edutrack.worker.sla;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * D-022 · nudging a ticket that has gone quiet.
 *
 * <p>"No update for 3 working days, to assignee cc RM."
 */
@Component
class StaleTicketNudge {

    /** Blueprint's figure. Days, not hours — see {@link #staleAfterWorkingHours()}. */
    static final int STALE_AFTER_WORKING_DAYS = 3;

    private final StaleTicketRepository tickets;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    StaleTicketNudge(StaleTicketRepository tickets,
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

    /**
     * Three working <em>days</em>, expressed in the working hours B-024 counts.
     *
     * <p>Derived from {@code workDayLength()} rather than written as 27. The
     * seeded calendar happens to run 09:30–18:30, but an org that configures a
     * seven-hour day means three days is 21 hours, and a constant would quietly
     * become "nearly four days" for them. B-024 measures in hours and has no
     * day count; if it ever grows a {@code workingDaysBetween}, this should
     * move to it and stop doing the conversion here.
     */
    private BigDecimal staleAfterWorkingHours() {
        long minutesPerDay = calendars.getCalendar().workDayLength().toMinutes();
        return BigDecimal.valueOf(minutesPerDay * STALE_AFTER_WORKING_DAYS)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** @return true if this ticket was stale and somebody was told */
    @Transactional
    public boolean nudgeIfStale(StaleTicketRepository.OpenTicket ticket, Instant now) {
        Instant lastActivity = ticket.lastActivityAt().toInstant();
        BigDecimal threshold = staleAfterWorkingHours();

        // D-027. Three working days means three days somebody could have
        // worked. A ticket last touched on Friday afternoon is not stale on
        // Monday morning, and counting the weekend would make every Monday a
        // burst of nudges for tickets nobody had a chance to move.
        BigDecimal quietFor = workingHours.workingHoursBetween(
                lastActivity, now, ticket.projectId(), ticket.assignedTo());

        if (quietFor.compareTo(threshold) < 0) {
            return false;
        }

        // A nudge repeats, unlike D-021's warning: a ticket still untouched
        // three working days later is stale again, and saying so again is the
        // point. The claim below only succeeds if the last nudge is itself
        // older than the window.
        Instant nudgeableIfBefore = lastNudgeCutoff(now, ticket, threshold);
        if (!tickets.claim(ticket.id(), now, lastActivity, nudgeableIfBefore)) {
            return false;
        }

        BigDecimal days = quietFor.divide(
                BigDecimal.valueOf(calendars.getCalendar().workDayLength().toMinutes())
                        .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP),
                1, RoundingMode.DOWN);

        String title = ticket.ticketCode() + " has had no update in "
                + days.toPlainString() + " working days";
        String body = "No comment, effort log or stage change since "
                + lastActivity.toString().substring(0, 10)
                + ". Still assigned and still open.";
        String link = "/tickets/" + ticket.ticketCode();

        Set<Long> recipients = recipientsOf(ticket);
        Map<Long, String> emails = tickets.emailsOf(recipients);

        for (long recipient : recipients) {
            notifications.write(new NewNotification(
                    recipient, ticket.id(), NotificationEvent.STALE_TICKET_NUDGE,
                    title, body, link));

            String address = emails.get(recipient);
            if (address != null) {
                outbox.enqueue(NewMail.forTicket(
                        ticket.id(), NotificationEvent.STALE_TICKET_NUDGE.name(),
                        recipient, address, body));
            }
        }
        return true;
    }

    /**
     * The instant a previous nudge must predate for another to be due.
     *
     * <p>Walking back through the working calendar is the honest way to express
     * "another three working days have passed since we last said something",
     * and B-024 only walks forward. Rather than invent a subtraction, this asks
     * the equivalent question the service can answer: a nudge is repeatable
     * once the working hours since it exceed the window. Callers that have
     * never nudged pass the epoch, which trivially satisfies it.
     */
    private Instant lastNudgeCutoff(Instant now,
                                    StaleTicketRepository.OpenTicket ticket,
                                    BigDecimal threshold) {
        if (ticket.lastNudgedAt() == null) {
            return now;
        }
        Instant lastNudged = ticket.lastNudgedAt().toInstant();
        BigDecimal sinceLastNudge = workingHours.workingHoursBetween(
                lastNudged, now, ticket.projectId(), ticket.assignedTo());

        // Returning the previous nudge instant makes the claim's `<=` test
        // true; returning something earlier makes it false without a second
        // round trip.
        return sinceLastNudge.compareTo(threshold) >= 0 ? lastNudged : Instant.EPOCH;
    }

    /** Assignee, cc the Reporting Manager — blueprint's wording for this one. */
    private static Set<Long> recipientsOf(StaleTicketRepository.OpenTicket ticket) {
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(ticket.assignedTo());
        if (ticket.reportingManagerId() != null) {
            recipients.add(ticket.reportingManagerId());
        }
        return recipients;
    }
}
