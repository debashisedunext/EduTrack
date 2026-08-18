package com.edunext.edutrack.api.feature.tickets.bulkreassign;

/**
 * C-063 · {@code toUserId} names nobody.
 *
 * <p>A whole-request 400, not a per-ticket refusal — unlike a ticket that is
 * missing or out of scope, the target resource is one shared value for the
 * entire batch, and a request that cannot be honoured for anybody is not
 * "499 succeeded, 1 failed", it is a request that named the wrong person.
 *
 * <p>The id is safe to echo: it is a request parameter the caller already
 * supplied, not a row this response would otherwise be confirming exists.
 */
class UnknownUserException extends RuntimeException {

    private final long userId;

    UnknownUserException(long userId) {
        super("No resource with id " + userId);
        this.userId = userId;
    }

    long userId() {
        return userId;
    }
}
