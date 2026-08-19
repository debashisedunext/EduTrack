package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 400 — the requested stage is not one of this ticket's workflow
 * template.
 *
 * <p>{@code ticket_stage_transitions.to_stage} carries the code as text with
 * no foreign key onto {@code workflow_stages} (A-005), so an unchecked value
 * would insert cleanly and leave the ribbon unable to draw the segment it
 * just wrote — the same failure {@code UnknownRestartStageException} exists
 * to prevent on reopen, restated here because {@code tickets.cycles} is a
 * different package and the two are not meant to depend on each other.
 */
class UnknownTransitionStageException extends RuntimeException {

    UnknownTransitionStageException(String stageCode, Long templateId) {
        super("stage " + stageCode + " is not a stage of workflow template " + templateId
                + ". The ribbon is drawn from that template's stages, so a code it does not "
                + "define would render no segment for this hop.");
    }
}
