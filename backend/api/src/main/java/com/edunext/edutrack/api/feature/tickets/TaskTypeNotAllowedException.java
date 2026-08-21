package com.edunext.edutrack.api.feature.tickets;

/**
 * C-071 · the project restricts its task types and this is not one of them.
 *
 * <p>Always 400, keyed on {@code taskTypeId}. <b>The id is in the message on
 * purpose</b>, which is the opposite of {@link UnknownProjectException}'s rule
 * and for a reason that does not apply here: a task type is master data every
 * role may read, and {@code getProjectSettings} hands the caller this project's
 * whole allow-list on request. There is no existence to leak, and a caller told
 * only "not allowed" cannot tell whether they picked the wrong row or the
 * project's configuration has moved under them.
 */
class TaskTypeNotAllowedException extends RuntimeException {

    private final int taskTypeId;

    TaskTypeNotAllowedException(int taskTypeId) {
        super(("Task type %d may not be raised on this project. The project restricts which task "
                + "types it accepts — a PM or Admin can change that on the project's Settings tab.")
                .formatted(taskTypeId));
        this.taskTypeId = taskTypeId;
    }

    int taskTypeId() {
        return taskTypeId;
    }
}
