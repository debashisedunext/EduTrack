package com.edunext.edutrack.api.feature.onboarding.mytasks;

/**
 * No such task, <b>or one that is not the caller's</b>. Both answer 404 and this
 * exception cannot tell them apart, because {@code ObMyTaskReadRepository}
 * applies the ownership predicate inside the statement and returns nothing
 * either way.
 *
 * <p>That is the point. A 403 on a task id confirms the task exists, and
 * {@code ob_journey_steps} ids are sequential, so the difference between the two
 * statuses is an enumeration oracle over every onboarding task in the
 * organisation. CONVENTIONS.md §7 and CLAUDE.md both refuse it.
 */
class ObMyTaskNotFoundException extends RuntimeException {

    ObMyTaskNotFoundException(long taskId) {
        super("no task " + taskId + " belonging to this caller");
    }
}
