package com.edunext.edutrack.worker.onboarding.tat;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * C-113 · the TAT scanner worker job — {@code worker/onboarding/}, D-020's
 * own infrastructure pattern (fixed-delay sweep, {@code ShedLock}, a bounded
 * pass, a separate transactional collaborator per candidate) one module
 * over.
 *
 * <p>What it does not do, stated as a boundary rather than left to be
 * discovered: no Amber warning (the "before Red" half of the module plan's
 * own risk table needs the TAT-consumed percentage {@code ObJourneyStepDetail}'s
 * own note says is still C-120's to build — {@code TAT_REMINDER} stays in
 * {@code ObNotificationEvent}'s catalogue, declared and unused, exactly
 * {@code --ribbon-breached} was through phase 1), and no L1→L2→L3 ladder
 * (§5.11's escalation matrix, C-115, which reads {@code tat_breached_at} as
 * one of its own inputs rather than this class writing {@code ob_escalations}
 * itself). This class answers one question only: has a running step's TAT
 * passed, and if so, has anybody been told.
 *
 * @see ObTatBreach for the flip, the history row, and the notifications
 */
@Component
class ObTatScanner {

    private static final Logger log = LoggerFactory.getLogger(ObTatScanner.class);

    /** A bound on one pass, not on the backlog — {@code SlaScanner}'s own reasoning. */
    private static final int MAX_PER_PASS = 500;

    private final ObTatRepository steps;
    private final ObTatBreach breach;
    private final Clock clock;

    ObTatScanner(ObTatRepository steps, ObTatBreach breach, Clock clock) {
        this.steps = steps;
        this.breach = breach;
        this.clock = clock;
    }

    /**
     * {@code initialDelayString} is load-bearing, not tidiness — {@code
     * SlaScanner}'s own account of the {@code fixedDelay}-fires-at-startup
     * deadlock applies to every scanner in this process, this one included.
     */
    @Scheduled(fixedDelayString = "${edutrack.onboarding.tat.scan-interval:PT15M}",
               initialDelayString = "${edutrack.onboarding.tat.initial-delay:PT30S}")
    @SchedulerLock(name = "obTatScanner", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            // Escaping a @Scheduled method cancels every future execution —
            // the scanner would stop for good, silently.
            log.error("ob-tat: scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many steps this pass flagged */
    int scanOnce() {
        Instant now = clock.instant();
        List<ObTatRepository.OverdueStep> candidates = steps.candidates(now, MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("ob-tat: hit the {}-candidate cap in one pass; the rest follow next interval",
                    MAX_PER_PASS);
        }

        int flagged = 0;
        for (ObTatRepository.OverdueStep step : candidates) {
            try {
                if (breach.flag(step, now)) {
                    flagged++;
                }
            } catch (RuntimeException e) {
                // Per step, so one bad row cannot cost the rest of the pass —
                // it is simply picked up again next interval.
                log.error("ob-tat: could not flag step {}", step.stepId(), e);
            }
        }

        if (flagged > 0) {
            log.info("ob-tat: {} step(s) past their TAT", flagged);
        }
        return flagged;
    }
}
