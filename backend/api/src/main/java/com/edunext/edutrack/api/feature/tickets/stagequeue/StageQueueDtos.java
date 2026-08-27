package com.edunext.edutrack.api.feature.tickets.stagequeue;

import com.edunext.edutrack.api.feature.tickets.list.TicketListDtos;
import com.edunext.edutrack.common.pagination.PageMeta;

import java.time.Instant;
import java.util.List;

/**
 * The shapes {@code GET /stages/queue} answers with — the contract's
 * {@code StageQueueResponse}, property for property.
 *
 * <h2>The row carries a {@code TicketSummary}, not a {@code Ticket}</h2>
 *
 * <p>The contract declared {@code Ticket} here from D-001, when no server
 * existed to disagree with it, and it is changed to {@code TicketSummary} in
 * the same commit as this file. The reason is the one {@code TicketSummary}'s
 * own contract text already gives for {@code GET /tickets}: {@code Ticket} is
 * 58 properties including {@code description} and {@code stepsToGenerate},
 * which are sanitised HTML running to kilobytes each, and S-31 draws six
 * columns — id, title, project, level, held-by and time-in-stage. A queue that
 * shipped a page of ticket bodies to render six columns would be slower than
 * the screen it replaces.
 *
 * <p>It is the same record S-17 binds, deliberately. Two ticket-row shapes with
 * different names for the same fact is precisely what produced S-17's blank ID
 * column (D-061), and a queue is a list of tickets by any reading.
 *
 * <h2>{@code timeInStageMins} is working minutes</h2>
 *
 * <p>CLAUDE.md's rule, and the contract says so in the field's own description.
 * A Friday-18:00 handoff has not been waiting three days on Monday morning, and
 * a queue sorted by a wall-clock figure would put every weekend on top.
 */
final class StageQueueDtos {

    private StageQueueDtos() {
    }

    record QueueResponse(List<QueueRow> data, PageMeta meta) {
    }

    /**
     * One row of "Waiting in QA".
     *
     * @param ticket          the row S-17 already knows how to draw
     * @param enteredStageAt  when this ticket entered the stage it is in now —
     *                        {@code tickets.stage_entered_at}, which the genesis
     *                        hop stamps at creation and every transition
     *                        restamps, so it is set for a ticket nobody has
     *                        moved yet
     * @param timeInStageMins working minutes since {@code enteredStageAt},
     *                        against the org calendar and the ticket's project
     *                        holidays
     * @param stageSlaBreached {@code true} only when the stage actually declares
     *                        an SLA and it has been passed. A stage with
     *                        {@code sla_hours} NULL — which is most of them, per
     *                        the seed's own note — is never breached rather than
     *                        always breached
     */
    record QueueRow(TicketListDtos.TicketSummary ticket,
                    Instant enteredStageAt,
                    long timeInStageMins,
                    boolean stageSlaBreached) {
    }
}
