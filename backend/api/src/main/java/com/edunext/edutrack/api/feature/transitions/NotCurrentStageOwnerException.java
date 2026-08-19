package com.edunext.edutrack.api.feature.transitions;

/**
 * C-043 · 422 — {@link StageOwnership#mayAdvance} refused. Only the ticket's
 * current stage owner, plus PM and Admin, may advance it.
 *
 * <p>422, not 403: CONVENTIONS.md §3 draws that line on whether a refusal
 * depends on a row existing, and this one depends on <em>this</em> ticket's
 * own {@code assigned_to} rather than on a permission the caller either
 * holds or does not — {@code ticket.handoff}/{@code ticket.rework} are
 * granted to every role, so a 403 here would be false. Contract type is
 * {@code https://edutrack/errors/stage-owner-required}, title "Only the
 * current stage owner may advance this ticket" (CONVENTIONS.md §3) —
 * whichever controller C-044+ adds maps this exception onto that body; none
 * exists yet, on {@link NoOpenStageException}'s own precedent for the same
 * gap.
 */
class NotCurrentStageOwnerException extends RuntimeException {

    NotCurrentStageOwnerException(long ticketId, Long currentOwnerId) {
        super("ticket " + ticketId + " may only be advanced by its current stage owner"
                + (currentOwnerId != null ? " (user " + currentOwnerId + ")" : " (unassigned)")
                + ", plus PM and Admin.");
    }
}
