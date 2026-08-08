package com.edunext.edutrack.api.feature.tickets;

/**
 * C-011 · no project row with that id.
 *
 * <p>Named for what is true, not for the HTTP status. The mapping is
 * <b>404, never 403</b> (contract §Scoping, CLAUDE.md): once A-034's
 * {@code ScopeResolver} lands, a project outside the caller's scope is filtered
 * out of the query and reaches this class as "does not exist". If one of those
 * answered 403 and the other 404, the pair would confirm which project ids are
 * real to anyone willing to enumerate them.
 *
 * <p>The {@code @ExceptionHandler} that renders it lives with C-010's
 * {@code TicketController}, alongside the other creation failures — there is no
 * ticket route yet, and a handler with no controller to advise cannot be tested.
 * The id is carried for the server log, and must not reach the response body.
 */
class UnknownProjectException extends RuntimeException {

    private final long projectId;

    UnknownProjectException(long projectId) {
        super("No project with id " + projectId);
        this.projectId = projectId;
    }

    long projectId() {
        return projectId;
    }
}
