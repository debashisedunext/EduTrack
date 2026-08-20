package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · the transition service's own request shape.
 *
 * <p>Not the contract's {@code HandoffRequest} / {@code ReworkRequest} / the
 * skip-stage body — those are one shape per route, each carrying exactly the
 * fields that route's dialog collects (C-044…C-048). This is the one shape
 * the engine underneath all of them needs; each future route maps its own
 * request onto this before calling {@link TransitionService#advance}.
 */
final class TransitionDtos {

    private TransitionDtos() {
    }

    /**
     * @param actionCode  one of {@link TransitionService#VALID_ACTIONS}
     * @param toStageCode required for every action except {@code FORWARD},
     *                    which defaults to the next stage in the ticket's
     *                    workflow template
     * @param assigneeId  the receiving owner; {@code null} keeps the current
     *                    one — unless the destination stage's role has
     *                    nobody active on the project, in which case the
     *                    ticket is left unassigned rather than kept with an
     *                    owner who no longer holds the role (C-050)
     * @param handoffNote optional, carried on the hop as written
     * @param reason      mandatory on a backward move —
     *                    {@link com.edunext.edutrack.domain.journal.TicketJournal}
     *                    enforces that at the append itself, so it is not
     *                    restated here
     */
    record TransitionRequest(String actionCode, String toStageCode, Long assigneeId,
                              String handoffNote, String reason) {
    }
}
