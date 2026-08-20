package com.edunext.edutrack.api.feature.tickets.assign;

/**
 * C-049 · {@code assigneeId} names nobody.
 *
 * <p>{@code BulkReassignService}'s {@code UnknownUserException} carries the
 * identical argument for the identical shape of mistake — the id is safe to
 * echo, since it is a request parameter the caller already supplied, not a
 * row this response would otherwise be confirming exists.
 */
class UnknownAssigneeException extends RuntimeException {

    private final long assigneeId;

    UnknownAssigneeException(long assigneeId) {
        super("No resource with id " + assigneeId);
        this.assigneeId = assigneeId;
    }

    long assigneeId() {
        return assigneeId;
    }
}
