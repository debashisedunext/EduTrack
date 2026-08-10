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
 * D-021 · the pre-breach sweep.
 *
 * <p>Runs on the same cadence as D-020 but asks the opposite question. The
 * breach scan reports what has already gone wrong; this one is the only alert
 * in the engine that can still change the outcome, which is why it goes to the
 * assignee alone rather than to the escalation chain.
 */
@Component
class PreBreachScanner {

    private static final Logger log = LoggerFactory.getLogger(PreBreachScanner.class);

    private static final int MAX_PER_PASS = 500;

    private final PreBreachRepository tickets;
    private final PreBreachWarning warning;
    private final Clock clock;

    PreBreachScanner(PreBreachRepository tickets, PreBreachWarning warning, Clock clock) {
        this.tickets = tickets;
        this.warning = warning;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.pre-breach-scan-interval:PT15M}")
    @SchedulerLock(name = "preBreachScanner", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("sla: pre-breach scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many assignees this pass warned */
    int scanOnce() {
        Instant now = clock.instant();
        List<PreBreachRepository.ApproachingTicket> candidates =
                tickets.candidates(now, MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("sla: hit the {}-candidate cap on the pre-breach scan; "
                    + "the rest follow next interval", MAX_PER_PASS);
        }

        int warned = 0;
        for (PreBreachRepository.ApproachingTicket ticket : candidates) {
            try {
                if (warning.warnIfApproaching(ticket, now)) {
                    warned++;
                }
            } catch (RuntimeException e) {
                log.error("sla: could not evaluate {} for a pre-breach warning",
                        ticket.ticketCode(), e);
            }
        }

        if (warned > 0) {
            log.info("sla: warned {} assignee(s) approaching their planned close date", warned);
        }
        return warned;
    }
}
