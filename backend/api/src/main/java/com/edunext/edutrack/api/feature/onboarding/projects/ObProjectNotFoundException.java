package com.edunext.edutrack.api.feature.onboarding.projects;

/**
 * No such project, or one outside the caller's scope — the two are the same
 * answer by design.
 *
 * <p>CLAUDE.md's no-existence-leak rule: {@code ObProjectReadRepository} applies
 * the client scope to every read, so an out-of-scope id simply returns nothing
 * and this class cannot tell the difference either. A 403 here would confirm
 * that a project with that id exists.
 */
class ObProjectNotFoundException extends RuntimeException {

    ObProjectNotFoundException(long projectId) {
        super("no onboarding project " + projectId);
    }
}
