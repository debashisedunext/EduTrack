package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-125 · <b>a mandatory task cannot be skipped.</b>
 *
 * <p>422, and deliberately not a permission the right role unlocks. Plan §5.3
 * leaves exactly one valve on a gate a client cannot clear, and it is
 * non-mandatory tasks only: a skippable mandatory task is not a mandatory
 * task, and the gate would be a convention rather than a guarantee.
 *
 * <p>Plan §14's mitigation for the stalling risk is "the mandatory list kept
 * short in the master" — a decision made in OB-14 at authoring time, not one
 * made per client under delivery pressure. This exception is what keeps that
 * the only place it can be made.
 *
 * <p>{@code ck_ob_client_prereq_tasks_mandatory_not_skipped} says the same
 * thing at the column, so a caller that bypassed this service meets it again.
 */
class MandatoryTaskNotSkippableException extends RuntimeException {

    MandatoryTaskNotSkippableException(long prereqTaskId) {
        super("prerequisite task " + prereqTaskId + " is mandatory and cannot be skipped; "
                + "the gate's only valve is non-mandatory tasks");
    }
}
