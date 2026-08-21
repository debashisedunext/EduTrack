package com.edunext.edutrack.api.feature.tickets;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C-071 · fields this project requires that the request left empty.
 *
 * <p><b>All of them at once, not the first.</b> A form with ten optional fields
 * can have several of them configured, and reporting one per round trip turns
 * one refusal into a conversation. The map is request-property → message, which
 * is the {@code errors: {field: [messages]}} shape the contract's
 * {@code ValidationFailed} uses and what {@code CreateTicketPage} maps onto its
 * controls — {@code ProjectTicketRules.RequiredField} owns the key.
 *
 * <p>Insertion order is preserved so the {@code detail} line reads in the order
 * the project stored its settings rather than in whatever order a hash produced.
 */
class MandatoryFieldsMissingException extends RuntimeException {

    private final Map<String, String> missing;

    MandatoryFieldsMissingException(Map<String, String> missing) {
        super(detail(missing));
        this.missing = new LinkedHashMap<>(missing);
    }

    /** Request property → the message that belongs on that control. */
    Map<String, String> missing() {
        return missing;
    }

    private static String detail(Map<String, String> missing) {
        return missing.size() == 1
                ? missing.values().iterator().next()
                : "This project requires " + missing.size() + " fields this ticket has left empty: "
                        + String.join(", ", missing.keySet()) + ".";
    }
}
