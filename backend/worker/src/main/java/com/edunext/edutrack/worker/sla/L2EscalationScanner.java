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
 * D-024 · the sweep that raises a stuck breach to the second level.
 */
@Component
class L2EscalationScanner {

    private static final Logger log = LoggerFactory.getLogger(L2EscalationScanner.class);

    private static final int MAX_PER_PASS = 500;

    /**
     * The wall-clock prefilter: a ticket whose date passed less than 48 hours
     * ago cannot be 48 <em>working</em> hours past it, because working hours
     * are never the larger of the two. Sound in the only direction that
     * matters — it can include tickets that turn out not to qualify, and can
     * never exclude one that does.
     */
    private static final Duration EARLIEST_POSSIBLE =
            Duration.ofHours(L2Escalation.OVERDUE_WORKING_HOURS.longValue());

    private final L2EscalationRepository tickets;
    private final L2Escalation escalation;
    private final Clock clock;

    L2EscalationScanner(L2EscalationRepository tickets, L2Escalation escalation, Clock clock) {
        this.tickets = tickets;
        this.escalation = escalation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.l2-scan-interval:PT30M}")
    @SchedulerLock(name = "l2EscalationScanner", lockAtMostFor = "PT28M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("sla: L2 escalation scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many tickets this pass escalated to the second level */
    int scanOnce() {
        Instant now = clock.instant();
        List<L2EscalationRepository.OverdueTicket> candidates =
                tickets.candidates(now.minus(EARLIEST_POSSIBLE), MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("sla: hit the {}-ticket cap on the L2 scan; the rest follow next interval",
                    MAX_PER_PASS);
        }

        int escalated = 0;
        for (L2EscalationRepository.OverdueTicket ticket : candidates) {
            try {
                if (escalation.escalateIfDue(ticket, now)) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                log.error("sla: could not evaluate {} for L2 escalation", ticket.ticketCode(), e);
            }
        }

        if (escalated > 0) {
            log.info("sla: escalated {} ticket(s) to the second level", escalated);
        }
        return escalated;
    }
}
