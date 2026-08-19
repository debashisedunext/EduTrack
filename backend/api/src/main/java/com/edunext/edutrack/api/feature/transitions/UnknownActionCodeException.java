package com.edunext.edutrack.api.feature.transitions;

import java.util.Set;

/**
 * C-042 · 400 — {@code actionCode} is not one of the eight the ribbon knows.
 *
 * <p>{@code ticket_stage_transitions.action_code} is a bare {@code VARCHAR(20)}
 * with no check constraint enumerating the set (PLAN.md §3.1 leaves that to
 * the service layer), so an unchecked value would insert cleanly and render
 * as a ribbon segment nothing in the frontend's icon/colour map recognises.
 */
class UnknownActionCodeException extends RuntimeException {

    UnknownActionCodeException(String actionCode, Set<String> valid) {
        super("action_code " + actionCode + " is not one of " + valid
                + " — the eight moves the ribbon knows (TicketStageTransition, §4A.2).");
    }
}
