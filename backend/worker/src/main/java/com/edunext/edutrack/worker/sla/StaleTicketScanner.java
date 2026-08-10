package com.edunext.edutrack.worker.sla;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * D-022 · the stale-task sweep.
 *
 * <p>The other three scanners are about deadlines. This one is about silence,
 * which is a different failure: a ticket can be nowhere near its Planned Close
 * Date, sitting in the right stage with the right owner, and simply forgotten.
 * Nothing else in the engine would ever mention it.
 *
 * <p>Runs hourly rather than every fifteen minutes. The threshold is measured
 * in working <em>days</em>, so a quarter-hour sweep asks a question whose answer
 * cannot have changed ninety-five times out of ninety-six, and each pass costs
 * a working-hours computation per open ticket.
 */
@Component
class StaleTicketScanner {

    private static final Logger log = LoggerFactory.getLogger(StaleTicketScanner.class);

    private static final int MAX_PER_PASS = 500;

    private final StaleTicketRepository tickets;
    private final StaleTicketNudge nudge;
    private final Clock clock;

    StaleTicketScanner(StaleTicketRepository tickets, StaleTicketNudge nudge, Clock clock) {
        this.tickets = tickets;
        this.nudge = nudge;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.stale-scan-interval:PT1H}")
    @SchedulerLock(name = "staleTicketScanner", lockAtMostFor = "PT55M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("sla: stale-task scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many tickets this pass nudged */
    int scanOnce() {
        Instant now = clock.instant();
        List<StaleTicketRepository.OpenTicket> candidates = tickets.candidates(MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("sla: hit the {}-ticket cap on the stale-task scan; "
                    + "the rest follow next interval", MAX_PER_PASS);
        }

        int nudged = 0;
        for (StaleTicketRepository.OpenTicket ticket : candidates) {
            try {
                if (nudge.nudgeIfStale(ticket, now)) {
                    nudged++;
                }
            } catch (RuntimeException e) {
                log.error("sla: could not evaluate {} for staleness", ticket.ticketCode(), e);
            }
        }

        if (nudged > 0) {
            log.info("sla: nudged {} ticket(s) with no recent activity", nudged);
        }
        return nudged;
    }
}
