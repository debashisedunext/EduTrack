package com.edunext.edutrack.worker.sla;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * D-026 · the triage sweep.
 *
 * <p>Every fifteen minutes, unlike the daily-scale scanners around it. The
 * threshold is two hours, so a slower sweep would routinely report a ticket as
 * "unassigned for two hours" when it had in fact been three — and the number in
 * the alert is the one the desk judges itself on.
 */
@Component
class UnassignedTicketScanner {

    private static final Logger log = LoggerFactory.getLogger(UnassignedTicketScanner.class);

    private static final int MAX_PER_PASS = 500;

    /**
     * The wall-clock prefilter. Working hours are never more than wall-clock
     * hours, so nothing two working hours old can be newer than this — the
     * filter can include tickets that turn out not to qualify and can never
     * exclude one that does.
     */
    private static final Duration EARLIEST_POSSIBLE =
            Duration.ofHours(UnassignedTicketAlert.UNASSIGNED_WORKING_HOURS.longValue());

    private final UnassignedTicketRepository tickets;
    private final UnassignedTicketAlert alert;
    private final Clock clock;

    UnassignedTicketScanner(UnassignedTicketRepository tickets,
                            UnassignedTicketAlert alert,
                            Clock clock) {
        this.tickets = tickets;
        this.alert = alert;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.unassigned-scan-interval:PT15M}",
               initialDelayString = "${edutrack.sla.initial-delay:PT30S}")
    @SchedulerLock(name = "unassignedTicketScanner", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("triage: unassigned scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many tickets this pass alerted on */
    int scanOnce() {
        Instant now = clock.instant();

        // Before reading candidates, not after: a ticket assigned since the last
        // pass must lose its alert row now, so that if it is ever unassigned
        // again the next alert is immediate rather than a working day late.
        int forgotten = tickets.forgetAssigned();
        if (forgotten > 0) {
            log.debug("triage: {} ticket(s) picked up since the last pass", forgotten);
        }

        List<UnassignedTicketRepository.UnassignedTicket> candidates =
                tickets.candidates(now.minus(EARLIEST_POSSIBLE), MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("triage: hit the {}-ticket cap on the unassigned scan; "
                    + "the rest follow next interval", MAX_PER_PASS);
        }

        int alerted = 0;
        for (UnassignedTicketRepository.UnassignedTicket ticket : candidates) {
            try {
                if (alert.alertIfUntouched(ticket, now)) {
                    alerted++;
                }
            } catch (RuntimeException e) {
                log.error("triage: could not evaluate {} for triage", ticket.ticketCode(), e);
            }
        }

        if (alerted > 0) {
            log.info("triage: {} ticket(s) still have nobody assigned", alerted);
        }
        return alerted;
    }
}
