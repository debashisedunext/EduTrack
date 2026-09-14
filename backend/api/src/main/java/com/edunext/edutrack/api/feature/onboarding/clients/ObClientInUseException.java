package com.edunext.edutrack.api.feature.onboarding.clients;

import java.util.List;

/**
 * Something depends on this client, so it cannot be deleted.
 *
 * <p>409 rather than 403: the caller is permitted to delete clients, and this
 * particular one is simply not deletable — a state of the data, not of the
 * caller. A 403 would send somebody to ask for a permission that would not help
 * them.
 *
 * <p>The message names what is in the way, because the person almost always
 * wanted to <em>drop</em> the client rather than erase it and cannot tell which
 * until they know what is there. {@link ObClientDeletionGuard} explains why the
 * four things it names are the four it checks.
 */
class ObClientInUseException extends RuntimeException {

    private final List<String> blockers;

    ObClientInUseException(long obClientId, List<String> blockers) {
        super("This client has " + join(blockers) + ", so it cannot be deleted. "
                + "Set it to Dropped instead — that keeps the record and hides nothing.");
        this.blockers = List.copyOf(blockers);
    }

    List<String> blockers() {
        return blockers;
    }

    /** "projects", "projects and a portal login", "projects, documents and a portal login". */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}
