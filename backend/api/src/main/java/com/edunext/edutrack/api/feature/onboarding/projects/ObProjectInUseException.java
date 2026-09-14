package com.edunext.edutrack.api.feature.onboarding.projects;

import java.util.List;

/**
 * Something records that this project ran, so it cannot be deleted.
 *
 * <p>409 rather than 403: the caller is permitted to delete projects, and this
 * particular one is simply not deletable — a state of the data, not of the
 * caller.
 *
 * <p>The message names what is in the way and points at the alternative,
 * because the person almost always wanted the project <em>off the grid</em>
 * rather than erased, and {@code DROPPED} does that while keeping the record.
 * {@link ObProjectDeletionGuard} explains which nine tables it asks about.
 */
class ObProjectInUseException extends RuntimeException {

    private final List<String> blockers;

    ObProjectInUseException(List<String> blockers) {
        super("This project has " + join(blockers) + ", so it cannot be deleted. "
                + "Set it to Dropped instead — that keeps the record and takes it off the running list.");
        this.blockers = List.copyOf(blockers);
    }

    List<String> blockers() {
        return blockers;
    }

    /** "sign-offs", "history and sign-offs", "history, sign-offs and documents". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}
