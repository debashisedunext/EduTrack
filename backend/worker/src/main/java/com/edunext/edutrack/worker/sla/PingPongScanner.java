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
 * D-025 · the ping-pong sweep.
 *
 * <p>Hourly, because §17's promise is "visible on the PM dashboard within a
 * day" and the underlying counter only moves when somebody performs a handoff.
 * A fifteen-minute sweep would ask a question whose answer almost never changed
 * in the interval.
 *
 * <p>The dashboard reads {@code ping_pong_flags} rather than counting live —
 * CLAUDE.md's rule, and the table is written here.
 */
@Component
class PingPongScanner {

    private static final Logger log = LoggerFactory.getLogger(PingPongScanner.class);

    private static final int MAX_PER_PASS = 500;

    private final PingPongRepository tickets;
    private final PingPongFlag flag;
    private final Clock clock;

    PingPongScanner(PingPongRepository tickets, PingPongFlag flag, Clock clock) {
        this.tickets = tickets;
        this.flag = flag;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.ping-pong-scan-interval:PT1H}",
               initialDelayString = "${edutrack.sla.initial-delay:PT30S}")
    @SchedulerLock(name = "pingPongScanner", lockAtMostFor = "PT55M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("sla: ping-pong scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many tickets this pass flagged */
    int scanOnce() {
        Instant now = clock.instant();
        List<PingPongRepository.BouncingTicket> candidates =
                tickets.candidates(PingPongFlag.ITERATION_THRESHOLD, MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("sla: hit the {}-ticket cap on the ping-pong scan; "
                    + "the rest follow next interval", MAX_PER_PASS);
        }

        int flagged = 0;
        for (PingPongRepository.BouncingTicket ticket : candidates) {
            try {
                if (flag.flag(ticket, now)) {
                    flagged++;
                }
            } catch (RuntimeException e) {
                log.error("sla: could not flag {} as ping-ponging", ticket.ticketCode(), e);
            }
        }

        if (flagged > 0) {
            log.info("sla: flagged {} ticket(s) bouncing between stages", flagged);
        }
        return flagged;
    }
}
