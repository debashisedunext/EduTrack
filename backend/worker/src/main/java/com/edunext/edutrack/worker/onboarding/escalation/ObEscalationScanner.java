package com.edunext.edutrack.worker.onboarding.escalation;

import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * C-115 · the escalation matrix's own sweep — {@code worker/onboarding/},
 * {@code ObTatScanner}'s exact shape one package over: fixed-delay,
 * {@code ShedLock}, a bounded pass per level, a separate transactional
 * collaborator per candidate.
 *
 * <p><b>Reads {@code tat_breached_at}; never writes it.</b> {@code
 * ObTatScanner}'s own javadoc names this task as the reader of that flag
 * rather than a second writer of it — the two scanners answer different
 * questions (has this step breached; how far up the ladder has it climbed)
 * against the same fact, and neither's cadence depends on the other's.
 *
 * <p><b>Three levels, one pass, each a separate query.</b> {@code
 * uq_ob_escalations_open (step_id, level, open_key)} lets a step hold all
 * three rungs at once by design (plan §5.11: each names a different person
 * who now owns it), so a long enough gap between passes — a restart, a
 * deploy — can raise more than one rung for the same step in a single
 * sweep, and that is correct rather than a bug: the ladder is a record of
 * who was told, not of what fired first.
 *
 * <p><b>L1's own threshold is zero, so it needs no calendar read at all.</b>
 * {@code ObEscalationRung.afterWorkingHours} is "zero on L1, which fires at
 * the breach itself" — the moment {@code tat_breached_at} is non-null, L1 is
 * already due. Only L2 and L3 ask {@link WorkingHoursService} how much
 * working time has actually elapsed since the breach.
 */
@Component
class ObEscalationScanner {

    private static final Logger log = LoggerFactory.getLogger(ObEscalationScanner.class);

    /** A bound on one level's pass, not on the backlog — {@code ObTatScanner}'s own reasoning. */
    private static final int MAX_PER_PASS = 500;

    private final ObEscalationLadderRepository escalations;
    private final ObEscalationRaise raise;
    private final WorkingHoursService workingHours;
    private final Clock clock;

    /**
     * The seeded ladder (plan §5.11, PHASE-2-BUILD-PLAN §2: breach → +4
     * working hours → +8), as configuration rather than a constant — {@code
     * ObTatScanner}'s own {@code scan-interval} pattern. Genuinely editable
     * configuration (a settings table an admin edits on OB-11) is B-113's;
     * this is the same boundary {@code ObJourneyStepRagService}'s amber
     * threshold names: a property a deploy can change, not yet a screen.
     */
    private final int l2AfterWorkingHours;
    private final int l3AfterWorkingHours;

    ObEscalationScanner(ObEscalationLadderRepository escalations,
                        ObEscalationRaise raise,
                        WorkingHoursService workingHours,
                        Clock clock,
                        @Value("${edutrack.onboarding.escalation.l2-after-working-hours:4}") int l2AfterWorkingHours,
                        @Value("${edutrack.onboarding.escalation.l3-after-working-hours:8}") int l3AfterWorkingHours) {
        this.escalations = escalations;
        this.raise = raise;
        this.workingHours = workingHours;
        this.clock = clock;
        this.l2AfterWorkingHours = l2AfterWorkingHours;
        this.l3AfterWorkingHours = l3AfterWorkingHours;
    }

    /** {@code initialDelayString} is load-bearing — {@code ObTatScanner}'s own account of the fixedDelay-at-startup deadlock. */
    @Scheduled(fixedDelayString = "${edutrack.onboarding.escalation.scan-interval:PT5M}",
               initialDelayString = "${edutrack.onboarding.escalation.initial-delay:PT45S}")
    @SchedulerLock(name = "obEscalationScanner", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            // Escaping a @Scheduled method cancels every future execution.
            log.error("ob-escalation: scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many rungs this pass raised, across all three levels */
    int scanOnce() {
        Instant now = clock.instant();
        int raised = 0;
        raised += sweep(ObEscalationLevel.L1, 0, now);
        raised += sweep(ObEscalationLevel.L2, l2AfterWorkingHours, now);
        raised += sweep(ObEscalationLevel.L3, l3AfterWorkingHours, now);
        if (raised > 0) {
            log.info("ob-escalation: {} rung(s) raised", raised);
        }
        return raised;
    }

    private int sweep(ObEscalationLevel level, int afterWorkingHours, Instant now) {
        List<ObEscalationLadderRepository.Candidate> candidates = escalations.candidates(level, MAX_PER_PASS);
        if (candidates.size() == MAX_PER_PASS) {
            log.warn("ob-escalation: hit the {}-candidate cap for {} in one pass; the rest follow next interval",
                    MAX_PER_PASS, level);
        }

        int raised = 0;
        for (ObEscalationLadderRepository.Candidate step : candidates) {
            try {
                if (!due(step, afterWorkingHours, now)) {
                    continue;
                }
                if (raise.raise(step, level, now)) {
                    raised++;
                }
            } catch (RuntimeException e) {
                // Per step, so one bad row cannot cost the rest of the pass.
                log.error("ob-escalation: could not raise {} for step {}", level, step.stepId(), e);
            }
        }
        return raised;
    }

    private boolean due(ObEscalationLadderRepository.Candidate step, int afterWorkingHours, Instant now) {
        if (afterWorkingHours <= 0) {
            return true;
        }
        BigDecimal elapsed = workingHours.workingHoursBetween(step.tatBreachedAt(), now);
        return elapsed.compareTo(BigDecimal.valueOf(afterWorkingHours)) >= 0;
    }
}
