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
 * D-023 · the stage-SLA scan, which is not the ticket-SLA scan.
 *
 * <p>Blueprint §16 item 3b, and the reason this is a second scanner rather than
 * a branch inside {@link SlaScanner}: <strong>a ticket can sit comfortably
 * inside its Planned Close Date while rotting four days in the Deployment
 * queue.</strong> D-020 would never see that — the ticket is not late — and by
 * the time it is, the four days are already spent. Per-stage SLAs are what make
 * the ribbon actionable rather than decorative.
 *
 * <p>The two scanners share nothing but a package, deliberately. They answer
 * different questions, alert different people (the stage owner, not the ticket
 * assignee), and remember what they have announced in different places —
 * {@code tickets.is_delayed} for one, {@code stage_sla_alerts} for the other,
 * because {@code ticket_stage_transitions} is append-only and cannot carry a
 * flag.
 */
@Component
class StageSlaScanner {

    private static final Logger log = LoggerFactory.getLogger(StageSlaScanner.class);

    /** As D-020: a bound on one pass, with whatever is left logged. */
    private static final int MAX_PER_PASS = 500;

    private final StageSlaRepository stages;
    private final StageSlaEscalation escalation;
    private final Clock clock;

    StageSlaScanner(StageSlaRepository stages, StageSlaEscalation escalation, Clock clock) {
        this.stages = stages;
        this.escalation = escalation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${edutrack.sla.stage-scan-interval:PT15M}")
    @SchedulerLock(name = "stageSlaScanner", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            // Escaping a @Scheduled method cancels every future execution.
            log.error("stage-sla: scan failed, retrying at the next interval", e);
        }
    }

    /** @return how many stages this pass announced */
    int scanOnce() {
        Instant now = clock.instant();
        List<StageSlaRepository.OpenStage> candidates = stages.candidates(now, MAX_PER_PASS);

        if (candidates.size() == MAX_PER_PASS) {
            log.warn("stage-sla: hit the {}-candidate cap; the rest follow next interval",
                    MAX_PER_PASS);
        }

        int announced = 0;
        for (StageSlaRepository.OpenStage stage : candidates) {
            try {
                if (escalation.escalateIfBreached(stage, now)) {
                    announced++;
                }
            } catch (RuntimeException e) {
                // Per segment: one project with a broken calendar must not cost
                // every other project its alerts.
                log.error("stage-sla: could not evaluate {} in {}",
                        stage.ticketCode(), stage.stageCode(), e);
            }
        }

        if (announced > 0) {
            log.info("stage-sla: {} stage(s) past their stage SLA", announced);
        }
        return announced;
    }
}
