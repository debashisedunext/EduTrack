package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * A write was attempted on a step sitting in {@code PENDING_REVIEW}.
 *
 * <h2>Why this is not {@link StepAlreadyTerminalException}</h2>
 *
 * <p>Both refuse a write, and the two states are easy to conflate, but they
 * are opposite facts about the task. A terminal step is <em>finished</em> —
 * its check list is the record of how it closed, and the refusal is
 * permanent. A step under review is <em>open</em>: it still counts against
 * its Step, it still sits in its owner's queue, and the refusal lasts exactly
 * as long as the manager takes to read it.
 *
 * <p>Telling an implementor "this service is already closed" about a task
 * they submitted twenty minutes ago would be a lie they could act on — they
 * would go looking for who closed it. Hence its own type, its own problem
 * URI, and a message that names what they are waiting for.
 *
 * @see ObJourneyStepLifecycleService#answerItem
 */
class StepUnderReviewException extends RuntimeException {

    private final long stepId;

    StepUnderReviewException(long stepId) {
        super("journey step " + stepId + " is with an OB Manager for review and cannot be changed until "
                + "the review closes");
        this.stepId = stepId;
    }

    /**
     * The row variant — one check-list row is out with the manager while the
     * rest of the task is still its implementor's to work on.
     *
     * <p>Same problem {@code type} on the wire as the task-level refusal, on
     * purpose: to whoever is reading, both mean "somebody else is holding
     * this, wait". The detail is what differs, and it is the detail that says
     * which. Splitting the type would make every client branch on a
     * distinction it has nothing different to do about.
     */
    StepUnderReviewException(long stepId, long itemId) {
        super("check-list row " + itemId + " of journey step " + stepId + " is with an OB Manager "
                + "for review and cannot be changed until the verdict comes back");
        this.stepId = stepId;
    }

    long stepId() {
        return stepId;
    }
}
