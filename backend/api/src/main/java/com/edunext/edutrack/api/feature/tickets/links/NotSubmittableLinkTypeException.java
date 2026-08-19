package com.edunext.edutrack.api.feature.tickets.links;

/**
 * C-064 · {@code linkType} was {@code DUPLICATED_BY}.
 *
 * <p>That value exists only as a computed label for the far side of a
 * {@code DUPLICATE_OF} row — see {@link TicketLinkType}'s javadoc — and
 * blueprint §7.5's create-form row offers no "duplicated by" option for a
 * caller to have meant. 400, on {@link SelfTicketLinkException}'s reasoning:
 * resending with {@code DUPLICATE_OF} and the two tickets swapped says the
 * same thing correctly.
 */
class NotSubmittableLinkTypeException extends RuntimeException {

    NotSubmittableLinkTypeException() {
        super("DUPLICATED_BY is a computed label, not a relationship you can create directly. "
                + "Use DUPLICATE_OF from the other ticket instead.");
    }
}
