package com.edunext.edutrack.worker.sla;

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

/**
 * D-021 · warning the assignee before the deadline rather than after it.
 *
 * <p>D-020 tells three people a ticket is late. This tells one person it is
 * <em>about</em> to be, which is the only one of the two that can change the
 * outcome.
 *
 * <p>A separate bean from the scanner so {@code @Transactional} is applied by
 * the proxy — the same trap D-020 documented.
 */
@Component
class PreBreachWarning {

    /**
     * Blueprint's figure. Late enough to be a real signal, early enough to
     * leave a fifth of the window to act in — a warning at 95% is just an
     * earlier way of saying it is already too late.
     */
    static final BigDecimal THRESHOLD = new BigDecimal("0.80");

    private final PreBreachRepository tickets;
    private final WorkingHoursService workingHours;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer outbox;

    PreBreachWarning(PreBreachRepository tickets,
                     WorkingHoursService workingHours,
                     NotificationWriter notifications,
                     OutboxEnqueuer outbox) {
        this.tickets = tickets;
        this.workingHours = workingHours;
        this.notifications = notifications;
        this.outbox = outbox;
    }

    /** @return true if this ticket had reached the threshold and was warned */
    @Transactional
    public boolean warnIfApproaching(PreBreachRepository.ApproachingTicket ticket, Instant now) {
        Instant reported = ticket.dateReported().toInstant();
        Instant due = ticket.plannedCloseDate().toInstant();

        // D-027. Both sides in working hours, from B-024. The committed window
        // is what somebody promised, and 80% of it is 80% of the time they
        // actually had — a percentage of wall-clock would put the warning in
        // the wrong place for every ticket that spans a weekend, which is most
        // tickets with a window longer than a few days.
        BigDecimal committed = workingHours.workingHoursBetween(
                reported, due, ticket.projectId(), ticket.assignedTo());
        BigDecimal elapsed = workingHours.workingHoursBetween(
                reported, now, ticket.projectId(), ticket.assignedTo());

        if (committed.signum() <= 0) {
            // A window entirely inside non-working time — a ticket raised and
            // due over the same weekend. There is no proportion to be 80% of,
            // and dividing would throw. D-020 still catches it when the date
            // passes.
            return false;
        }

        BigDecimal fraction = elapsed.divide(committed, 4, RoundingMode.HALF_UP);
        if (fraction.compareTo(THRESHOLD) < 0) {
            return false;
        }

        BigDecimal percent = fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
        if (!tickets.claim(ticket.id(), ticket.cycleNo(), percent)) {
            return false;
        }

        BigDecimal remaining = committed.subtract(elapsed).max(BigDecimal.ZERO);
        String title = ticket.ticketCode() + " is approaching its SLA";
        String body = percent.toPlainString() + "% of the window used — about "
                + remaining.toPlainString() + " working hours left before it breaches.";
        String link = "/tickets/" + ticket.ticketCode();

        // The assignee alone, deliberately. This is a nudge to the one person
        // who can still finish it; copying their manager turns a helpful
        // reminder into being reported on, and D-020 already escalates to
        // three people if the deadline actually passes.
        notifications.write(new NewNotification(
                ticket.assignedTo(), ticket.id(), NotificationEvent.SLA_80_PERCENT_ELAPSED,
                title, body, link));

        // SLA_80_PERCENT_ELAPSED is an ESCALATION in the event catalogue, so
        // D-036 makes this mail unsuppressable. That follows from the category
        // rather than being decided here, and it is the right side to err on:
        // the whole value of a pre-breach warning is that it arrives.
        tickets.emailOf(ticket.assignedTo()).ifPresent(address ->
                outbox.enqueue(NewMail.forTicket(
                        ticket.id(), NotificationEvent.SLA_80_PERCENT_ELAPSED.name(),
                        ticket.assignedTo(), address, body)));

        return true;
    }
}
