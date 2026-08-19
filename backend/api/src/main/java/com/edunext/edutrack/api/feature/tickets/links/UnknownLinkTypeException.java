package com.edunext.edutrack.api.feature.tickets.links;

/**
 * C-064 · {@code linkType} was not one of the contract's five names.
 *
 * <p>400, on {@code UnknownLevelException}'s precedent: the offending value is
 * safe to echo — it names no row, just a fixed vocabulary of five strings —
 * and the fix is for the caller to resend one it knows.
 */
class UnknownLinkTypeException extends RuntimeException {

    private final String linkType;

    UnknownLinkTypeException(String linkType) {
        super("Unknown link type " + linkType);
        this.linkType = linkType;
    }

    String linkType() {
        return linkType;
    }
}
