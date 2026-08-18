package com.edunext.edutrack.api.feature.tickets.links;

/**
 * C-064 · {@code targetTicketId} named the same ticket as the path.
 *
 * <p>400, not 422: the database's own {@code ck_ticket_links_not_self} would
 * refuse this too, but as an unlabelled constraint violation, and the caller
 * can fix it by resending with a different target — which is CONVENTIONS.md's
 * own line between the two statuses. Checked before the target is even
 * resolved, so this never depends on whether the target exists.
 */
class SelfTicketLinkException extends RuntimeException {

    SelfTicketLinkException() {
        super("A ticket cannot be linked to itself.");
    }
}
