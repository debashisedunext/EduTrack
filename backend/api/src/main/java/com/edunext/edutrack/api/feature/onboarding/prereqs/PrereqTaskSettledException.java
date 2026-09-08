package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;

/**
 * B-125 · a settled task is not reworded — the client agreed to what it said.
 *
 * <p>422 rather than 409: the row exists and the caller may edit tasks, but
 * this particular content change is one the workflow forbids.
 */
class PrereqTaskSettledException extends RuntimeException {

    PrereqTaskSettledException(long prereqTaskId, ObPrereqTaskStatus status) {
        super("prerequisite task " + prereqTaskId + " is " + status
                + " and can no longer be edited; the client agreed to what it said");
    }
}
