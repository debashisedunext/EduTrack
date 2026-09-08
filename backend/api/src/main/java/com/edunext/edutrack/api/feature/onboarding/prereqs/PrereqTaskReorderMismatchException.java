package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124 · the reorder list is not exactly the draft's current task set.
 *
 * <p>400 rather than a silent partial reorder: the request names positions,
 * and positions only mean something against the complete set. Accepting a
 * partial list would reorder around tasks the caller could not see.
 */
class PrereqTaskReorderMismatchException extends RuntimeException {

    PrereqTaskReorderMismatchException(String reason) {
        super("the prerequisite task reorder list does not match the draft: " + reason);
    }
}
