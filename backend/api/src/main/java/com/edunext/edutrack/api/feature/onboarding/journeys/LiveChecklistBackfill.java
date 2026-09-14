package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B-131 · carries a newly added Task List entry from a Module Service that is
 * <b>already in use</b> onto the journeys currently running from it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A running step is a <em>snapshot</em>. {@code ObJourneyInstantiationService}
 * copies every {@link ObJourneyTemplateStepItem} into {@code ob_journey_step_items}
 * at instantiation and nothing has ever reached back afterwards, which is what
 * makes a published version safe to leave frozen — the journey renders its own
 * rows, not the template's.
 *
 * <p>That is also why adding an item to a live service used to be worth
 * nothing to the clients on it. The catalogue would show the new entry and
 * every client already onboarding would keep the checklist they started with,
 * so the admin's edit reached exactly the clients who did not need it yet.
 * Adding the item and <b>not</b> back-filling is the behaviour that reads as a
 * bug; this class is the other half of the write.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><b>It writes items, never answers.</b> A back-filled row arrives
 * unanswered, exactly as instantiation leaves one. {@code answer},
 * {@code remark}, {@code answeredBy} and {@code answeredAt} stay null and
 * {@code ck_ob_journey_step_items_remark} is never in play.
 *
 * <p><b>It does not reopen a finished step.</b> A step already {@code DONE} or
 * {@code SKIPPED} takes the row too — its checklist is the record of what that
 * step covers, and leaving a hole there would make the same service read
 * differently on two clients for no reason a reader could recover. What it does
 * not do is re-run the completion gate: {@code ObJourneyStepLifecycleService}
 * evaluates that on the {@code complete} transition only, so a step that has
 * already passed it stays passed. A step still open gains a genuine obligation
 * — which is the point of adding a mandatory item to a live service — and a
 * step that later reverts on a client objection is re-gated against the fuller
 * list, correctly.
 *
 * <p><b>It reaches one version.</b> Only journeys pinned to the version that
 * was edited, via {@code ObJourneyStepRepository#findLiveByTemplateStepId}'s
 * {@code templateStepId}. Clients boarded on an <em>earlier</em> version of the
 * same service are not touched, and cannot be from here: their steps point at
 * that version's rows, which are frozen for as long as those journeys exist.
 */
@Component
class LiveChecklistBackfill {

    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyStepItemRepository journeyStepItems;

    LiveChecklistBackfill(ObJourneyStepRepository journeySteps,
                          ObJourneyStepItemRepository journeyStepItems) {
        this.journeySteps = journeySteps;
        this.journeyStepItems = journeyStepItems;
    }

    /**
     * Puts {@code item} on every live step snapshotted from {@code templateStepId}.
     *
     * <p>Runs inside the caller's transaction, which is what makes the
     * catalogue row and the client rows one write: if the back-fill fails the
     * template item is not added either, rather than leaving a service whose
     * checklist half its clients have.
     *
     * @return how many running journeys picked the item up — reported back to
     *         the admin, who is otherwise guessing whether an edit to a live
     *         service reached anybody
     */
    int addToLiveJourneys(long templateStepId, ObJourneyTemplateStepItem item) {
        List<ObJourneyStep> liveSteps = journeySteps.findLiveByTemplateStepId(templateStepId);
        if (liveSteps.isEmpty()) {
            return 0;
        }

        Map<Long, Integer> nextSequence = nextSequenceByStepId(liveSteps);
        for (ObJourneyStep step : liveSteps) {
            ObJourneyStepItem row = new ObJourneyStepItem();
            row.setStepId(step.getId());
            // Provenance, and the join the completion gate reads mandatory-ness
            // back through — ObJourneyStepItem carries no `mandatory` column of
            // its own. Without this the gate would fall through to its
            // "no template item, assume mandatory" default, which happens to be
            // right for a mandatory item and silently wrong for an optional one.
            row.setTemplateItemId(item.getId());
            row.setSequence(nextSequence.get(step.getId()));
            row.setLabel(item.getLabel());
            journeyStepItems.save(row);
        }
        return liveSteps.size();
    }

    private Map<Long, Integer> nextSequenceByStepId(List<ObJourneyStep> liveSteps) {
        List<Long> stepIds = liveSteps.stream().map(ObJourneyStep::getId).toList();
        Map<Long, Integer> next = new HashMap<>();
        for (Long stepId : stepIds) {
            // A step with no items yet still needs an entry: the loop below
            // only sees steps that have rows, and `get` on a missing key would
            // be a null sequence on precisely the emptiest case.
            next.put(stepId, 1);
        }
        for (ObJourneyStepItem existing : journeyStepItems.findByStepIdInOrderByStepIdAscSequenceAsc(stepIds)) {
            next.merge(existing.getStepId(), existing.getSequence() + 1, Math::max);
        }
        return next;
    }
}
