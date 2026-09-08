package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;

/**
 * B-125 · the task is not in a state this transition accepts.
 *
 * <p>422, and <b>the problem body names the status it is in</b> — the
 * contract asks for that specifically. "Not submittable" without saying what
 * it is instead sends the caller to re-read the task to find out, which is
 * the round trip the status code was supposed to save.
 *
 * <p>{@link #problemCode()} carries the contract's own slug
 * ({@code ob-prereq-not-submittable} and its three siblings) so the handler
 * does not have to infer it from the message.
 */
class PrereqTransitionException extends RuntimeException {

    private final ObPrereqTaskStatus status;
    private final String problemCode;

    PrereqTransitionException(long prereqTaskId, ObPrereqTaskStatus status,
                              String attempted, String problemCode) {
        super("prerequisite task " + prereqTaskId + " is " + status
                + " and cannot be " + attempted);
        this.status = status;
        this.problemCode = problemCode;
    }

    ObPrereqTaskStatus status() {
        return status;
    }

    String problemCode() {
        return problemCode;
    }
}
