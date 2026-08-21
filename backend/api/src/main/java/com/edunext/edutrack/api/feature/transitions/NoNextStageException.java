package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 400 — a move with no explicit {@code toStageCode} could not resolve
 * one from the template on its own.
 *
 * <p>Two callers reach it. {@link TransitionService#resolveToStage} defaults
 * {@code FORWARD}'s destination, and since C-047 {@code SkipService} defaults
 * a skip's the same way — which is why neither the message nor this javadoc
 * names an action code any more: both would be telling half the callers about
 * a move they did not make.
 *
 * <p>Two distinct causes share this exception rather than each inventing its
 * own, because a caller acts on both the same way — send the destination
 * explicitly:
 *
 * <ul>
 *   <li>the ticket carries no {@code workflow_template_id}, so there is no
 *       stage sequence to read "next" from;</li>
 *   <li>the stage being left is the template's last stage, so there is no
 *       next stage to default to.</li>
 * </ul>
 */
class NoNextStageException extends RuntimeException {

    NoNextStageException(long ticketId, String currentStage) {
        super("ticket " + ticketId + " has no next workflow-template stage after " + currentStage
                + " — pass toStageCode explicitly.");
    }
}
