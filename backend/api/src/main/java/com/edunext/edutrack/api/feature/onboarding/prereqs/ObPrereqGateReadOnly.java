package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * B-125 · the {@link ObPrereqGate} that ships until C-118 lands.
 *
 * <h2>What it does, and what it refuses to pretend</h2>
 *
 * <p>It evaluates plan §5.3's condition and stamps the <b>header</b> —
 * {@code ob_client_prereqs.status} moves to {@code CLEARED} with its
 * timestamp, once, on the transition that satisfies the gate. That much is
 * this task's own table and this task's own fact.
 *
 * <p>It does <b>not</b> flip journeys, activate steps, start clocks or fire
 * the kickoff. Those are C-118's, and each of the three needs machinery
 * Stream C owns. So {@code openedJourneyIds} is always empty here and
 * {@code gateStatus} is read from the client's journeys rather than written
 * to them.
 *
 * <p><b>{@code gateOpened} is still answered truthfully.</b> It reports the
 * transition that cleared the checklist, which is a fact about the tasks and
 * is knowable without touching a journey. A screen is therefore told the
 * right thing about the prerequisites even while the journeys behind them are
 * not yet moving — which is a visible, explicable gap, where returning false
 * would be a quiet lie that C-118 would later have to be trusted to fix.
 *
 * <h2>How C-118 replaces it</h2>
 *
 * <p>{@link ObPrereqGateConfiguration} declares this as the fallback bean, so
 * adding the real implementation is one {@code @Component} in Stream C's
 * package and this backs off. Nothing here has to be deleted or unpicked,
 * which is what makes the deferral a deferral rather than a rewrite — B-110's
 * {@code ObChannelAdapter} registry made the same call.
 *
 * <p>The condition lives on an {@code @Bean} method rather than on this class:
 * {@code @ConditionalOnMissingBean} on a {@code @Component} is evaluated
 * during scanning, before the beans it is asking about are necessarily known,
 * and Spring's own documentation calls that unreliable. It fails as a context
 * load error rather than as a wrong answer, which is how this was found.
 */
class ObPrereqGateReadOnly implements ObPrereqGate {

    private final ObClientPrereqsRepository headers;
    private final ObJourneyGateReader journeys;

    ObPrereqGateReadOnly(ObClientPrereqsRepository headers, ObJourneyGateReader journeys) {
        this.headers = headers;
        this.journeys = journeys;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome evaluate(long obClientId, List<ObClientPrereqTask> tasks) {
        boolean satisfied = ObPrereqGate.isSatisfiedBy(tasks);

        ObClientPrereqs header = headers.findByObClientId(obClientId).orElse(null);
        boolean wasCleared = header != null && header.getStatus() == ObClientPrereqs.Status.CLEARED;

        // The header moves once and only forward. Re-clearing an already
        // cleared checklist would rewrite `cleared_at`, and that timestamp is
        // what plan §5.4's client-attributed time is measured against.
        boolean openedNow = satisfied && !wasCleared;
        if (openedNow && header != null) {
            header.setStatus(ObClientPrereqs.Status.CLEARED);
            header.setClearedAt(Instant.now());
            headers.save(header);
        }

        // **Deliberately not un-clearing.** An ad-hoc mandatory task added
        // after the gate cleared makes `satisfied` false again, and the
        // contract's own answer to that race is a 412 on the create rather
        // than a reversal here — reversing would stop clocks that had already
        // started and contradict a kickoff mail already sent.
        return new Outcome(journeys.gateStatusOf(obClientId), openedNow, List.of());
    }
}
