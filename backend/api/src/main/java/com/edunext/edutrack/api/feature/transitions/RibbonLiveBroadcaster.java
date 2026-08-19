package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import com.edunext.edutrack.api.realtime.StageQueueBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * C-045 · turns a sealed {@link TicketStageAdvanced} into the two pushes the
 * ribbon and team queues were waiting on:
 *
 * <ul>
 *   <li><b>D-058</b> — {@code stage.changed} on {@code /topic/ticket.{id}},
 *       so anyone with the detail page open sees the handoff land without a
 *       refresh. On this codebase's "the client refetches" convention
 *       ({@code StageQueueBroadcaster}'s own doc), the frame is a nudge —
 *       enough to know what moved, not a substitute for {@code GET
 *       /tickets/{id}/ribbon}.</li>
 *   <li><b>D-059's seam</b> — {@code StageQueueBroadcaster#left}/{@code
 *       #arrived}, exactly the two-line call its own javadoc names as
 *       waiting on this task: "wiring it is one call at the end of C-045's
 *       transaction."</li>
 * </ul>
 *
 * <p>Both fire from an {@code AFTER_COMMIT} listener rather than inline in
 * {@code TransitionService.advance} — see {@link TicketStageAdvanced}'s own
 * javadoc for why. Each push is wrapped independently: a Redis hiccup on the
 * stage-queue nudge must not cost the ticket-topic push, or the reverse, and
 * neither can undo a commit that has already happened, {@code
 * PushDispatcher}'s own reasoning for the identical shape.
 */
@Component
class RibbonLiveBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RibbonLiveBroadcaster.class);

    private final RealtimePublisher realtime;
    private final StageQueueBroadcaster stageQueue;

    RibbonLiveBroadcaster(RealtimePublisher realtime, StageQueueBroadcaster stageQueue) {
        this.realtime = realtime;
        this.stageQueue = stageQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TicketStageAdvanced event) {
        try {
            realtime.publish(RealtimeDestinations.ticket(event.ticketId()), Map.of(
                    "event", "stage.changed",
                    "ticketId", event.ticketId(),
                    "fromStage", event.fromStage(),
                    "toStage", event.toStage()));
        } catch (RuntimeException e) {
            log.error("transitions: could not push stage.changed for ticket {}", event.ticketId(), e);
        }

        try {
            stageQueue.left(event.fromStage(), event.projectId());
            stageQueue.arrived(event.toStage(), event.projectId());
        } catch (RuntimeException e) {
            log.error("transitions: could not update stage queues for ticket {} ({} -> {})",
                    event.ticketId(), event.fromStage(), event.toStage(), e);
        }
    }
}
