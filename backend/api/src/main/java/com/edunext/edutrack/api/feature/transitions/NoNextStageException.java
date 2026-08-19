package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 400 — a {@code FORWARD} move with no explicit {@code toStageCode}
 * could not resolve one on its own.
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
        super("ticket " + ticketId + " cannot default FORWARD's next stage from " + currentStage
                + " — pass toStageCode explicitly.");
    }
}
