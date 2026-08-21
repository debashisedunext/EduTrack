package com.edunext.edutrack.api.feature.transitions;

import java.util.Set;

/**
 * C-046 · an action code that is real but is not a backward move.
 *
 * <h2>Why not {@link UnknownActionCodeException}</h2>
 *
 * <p>That one means "the ribbon has never heard of this", and its message says
 * so — "not one of {@code [...]} — the eight moves the ribbon knows". Reusing
 * it here would tell a caller who sent {@code FORWARD} that {@code FORWARD} is
 * not a move, which is both wrong and the sort of message that costs somebody
 * twenty minutes. {@code FORWARD} is perfectly valid; it is valid on
 * {@code /handoff}.
 *
 * <h2>Why this is refused at all</h2>
 *
 * <p>{@code TransitionService.advance} accepts all eight codes and would write
 * {@code FORWARD} into the ledger through this route quite happily — a forward
 * move recorded as a rework, with {@code iterationNo} left alone because
 * {@code BACKWARD_ACTIONS} would not match it. {@code ticket_stage_transitions}
 * is append-only and hash-chained, so that row could never be corrected, only
 * compensated. The narrowing costs one check and removes a class of
 * uncorrectable mistake.
 */
class NotABackwardActionException extends RuntimeException {

    NotABackwardActionException(String actionCode, Set<String> backwardActions) {
        super(actionCode + " is not a backward move. This route records one of "
                + backwardActions.stream().sorted().toList()
                + " (§4A.1); a forward move is POST /tickets/{ticketId}/handoff, "
                + "and moving anywhere else is POST /tickets/{ticketId}/force-move.");
    }
}
