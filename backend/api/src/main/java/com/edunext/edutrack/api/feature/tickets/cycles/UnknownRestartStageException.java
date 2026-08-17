package com.edunext.edutrack.api.feature.tickets.cycles;

/**
 * C-038 · <b>400</b>, field-keyed onto {@code restartStageCode} — S-22's
 * "restart stage" picker chose a stage this ticket's workflow template does not
 * have.
 *
 * <p>Validated rather than trusted, because {@code tickets.current_stage} is a
 * bare {@code VARCHAR(20)} with no foreign key onto {@code workflow_stages} — the
 * ribbon is drawn by laying the ticket's stage against the template's stage
 * sequence, so a stage code absent from the template renders <em>no</em> current
 * segment. The ticket disappears from its own ribbon, on a page that shows
 * nothing wrong anywhere else, and no later write repairs it: the next handoff
 * has no segment to leave.
 *
 * <p>400 rather than 422 because this one <em>is</em> about the value sent, and
 * the form can put the message against the picker and let the user choose again.
 * That is the distinction {@code TicketExceptionHandler} already draws between
 * {@code UnknownLevelException} and everything else.
 */
class UnknownRestartStageException extends RuntimeException {

    UnknownRestartStageException(String stageCode, Long templateId) {
        super("restart stage " + stageCode + " is not a stage of workflow template " + templateId
                + ". The reopened cycle's ribbon is drawn from that template's stages, so a code it "
                + "does not define would leave the ticket with no current segment at all.");
    }
}
