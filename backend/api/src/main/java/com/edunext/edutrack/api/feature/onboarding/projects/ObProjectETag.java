package com.edunext.edutrack.api.feature.onboarding.projects;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code ETag} for {@code /onboarding/projects/{obProjectId}} —
 * {@code ObClientETag}'s rules, applied to this resource.
 *
 * <p>Derived from the content rather than from {@code updated_at}, for the
 * reason that class states: a timestamp tag moves when a save rewrites
 * identical values, failing an edit that conflicts with nothing.
 *
 * <p><b>The stage roll-up is inside the tag, and that is deliberate rather than
 * incidental.</b> {@code ObProjectDetail} carries the stages and their
 * outstanding counts, so a step completed by an owner while somebody had the
 * project header open moves this tag and costs the editor a reload. That is the
 * same strictness {@code ObClientETag} chose when it put the journeys inside the
 * client's tag, and the same trade: on the one screen where the record and the
 * work underneath it are shown together, a stale editor is worse than an extra
 * reload.
 *
 * <p><b>A 32-bit hash, and two states of one project can collide.</b> Recorded
 * rather than fixed here — every tag in this repository is derived the same way,
 * and a stronger one is a change worth making across all of them at once.
 */
final class ObProjectETag {

    private ObProjectETag() {
    }

    static String of(ObProjectDtos.ObProjectDetail project) {
        return Integer.toHexString(project.hashCode());
    }

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is 428 rather than allowed through: treating a
     * missing precondition as "no conflict" means the guard protects only the
     * callers that already opted in, which is the set that needed it least.
     *
     * <p>Callers must resolve the project — and answer 404 for one they cannot
     * see — <b>before</b> calling this, so a 428 is never returned for a URL
     * that would have 404'd.
     */
    static void require(String ifMatch, ObProjectDtos.ObProjectDetail current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the project first and send back its ETag.");
        }
        if (!matches(ifMatch, of(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This project changed since you read it. Reload and reapply your edit.");
        }
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
