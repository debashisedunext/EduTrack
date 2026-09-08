package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;

import java.util.List;

/**
 * B-125 · the seam C-118 fills.
 *
 * <h2>Why a seam rather than the thing itself</h2>
 *
 * <p>Plan §5.3's gate does four things when the last outstanding task
 * settles: flips every {@code LOCKED} journey to {@code OPEN}, activates
 * every dependency-free step, starts clocks, and fires the kickoff
 * automation. Three of those four are Stream C's — step activation is C-119's
 * dependency graph, the clocks are C-105's, and the journey flip is
 * {@code ObJourney}'s. <b>C-118 owns that method</b>, and it is a separate
 * task that depends on this one.
 *
 * <p>So this task builds everything that decides <em>whether</em> the gate is
 * satisfied — which is arithmetic over the task rows, and belongs with the
 * task rows — and stops at the boundary where the consequences begin.
 * {@link ObPrereqGateReadOnly} is the implementation that ships today: it
 * reports honestly and flips nothing.
 *
 * <p>The same shape B-110 used for {@code ObChannelAdapter}, and for the same
 * reason: the deferral stays a deferral rather than becoming a redesign,
 * because C-118 adds one bean instead of unpicking a service.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>There is no {@code openGate(clientId)}. Plan §5.3: "There is no 'open
 * gate anyway' override — the only valve is skipping non-mandatory tasks."
 * The gate opens as a <em>consequence</em> of the transition that clears the
 * last task, inside that transition, and there is no route and no method that
 * opens it directly. An interface method taking a client id and opening its
 * gate would be exactly that override, reachable from anywhere the bean is
 * injected.
 */
public interface ObPrereqGate {

    /**
     * Evaluate a client's gate after a transition, and apply whatever
     * consequences the implementation owns.
     *
     * <p>Called inside the transaction that moved the task, always — the
     * journey flip and the header's {@code CLEARED} stamp have to commit with
     * the transition that caused them or a crash between the two leaves a
     * client whose tasks are all verified and whose journeys are all locked.
     *
     * @param obClientId the client whose gate to evaluate
     * @param tasks      that client's complete task set, as it now stands.
     *                   Passed rather than re-read so the caller's own
     *                   in-transaction changes are certainly visible, and so
     *                   an implementation cannot quietly evaluate a different
     *                   set from the one the caller just wrote.
     */
    Outcome evaluate(long obClientId, List<ObClientPrereqTask> tasks);

    /**
     * What the transition did to the gate.
     *
     * @param gateStatus       the client's gate as it now stands
     * @param gateOpened       <b>this transition</b> is what opened it — true
     *                         at most once in a client's life. Distinct from
     *                         {@code gateStatus == OPEN}, which is true on
     *                         every subsequent call too: a screen refreshing
     *                         the ribbon whenever the gate reads open would
     *                         refresh it forever.
     * @param openedJourneyIds the journeys that flipped in this transaction.
     *                         Empty unless {@code gateOpened}, and <b>not
     *                         necessarily every journey the client has</b> —
     *                         one held behind a sibling (plan §5.5) stays
     *                         held, because the gate and the service
     *                         dependency are two different holds.
     */
    record Outcome(ObGateStatus gateStatus,
                   boolean gateOpened,
                   List<Long> openedJourneyIds) {

        public Outcome {
            openedJourneyIds = List.copyOf(openedJourneyIds);
        }
    }

    /**
     * Plan §5.3's condition, and the one place it is spelled: every mandatory
     * task {@code VERIFIED}, every non-mandatory one {@code VERIFIED} or
     * {@code SKIPPED}.
     *
     * <p>A default method rather than each implementation's own, so C-118
     * cannot accidentally ship a second reading of the condition this
     * module's whole shape depends on. The per-task half lives on
     * {@link ObClientPrereqTask#holdsTheGate()} for the same reason.
     *
     * <p><b>An empty checklist satisfies it.</b> That is correct and worth
     * stating: a client boarded against a master with no tasks has nothing to
     * clear. B-124 refuses to publish a version with no mandatory task, so
     * the only way to reach it is a client with no checklist at all.
     */
    static boolean isSatisfiedBy(List<ObClientPrereqTask> tasks) {
        return tasks.stream().noneMatch(ObClientPrereqTask::holdsTheGate);
    }
}
