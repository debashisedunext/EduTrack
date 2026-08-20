package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.realtime.RealtimePublisher;
import com.edunext.edutrack.api.realtime.StageQueueBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * C-045 · {@link RibbonLiveBroadcaster} — what a sealed {@link
 * TicketStageAdvanced} turns into on the wire: D-058's ticket-topic push and
 * the two-call seam {@code StageQueueBroadcaster}'s own javadoc names as
 * waiting on this task.
 *
 * <p>This only proves what the listener does when invoked — that it fires
 * strictly after commit is Spring's {@code @TransactionalEventListener}
 * contract, not something a unit test re-verifies, on {@code PushDispatcher}'s
 * own precedent for not re-testing the framework.
 */
class RibbonLiveBroadcasterTest {

    private static final long TICKET_ID = 347L;
    private static final long PROJECT_ID = 8L;

    private final RealtimePublisher realtime = mock(RealtimePublisher.class);
    private final StageQueueBroadcaster stageQueue = mock(StageQueueBroadcaster.class);

    private final RibbonLiveBroadcaster broadcaster = new RibbonLiveBroadcaster(realtime, stageQueue);

    @Test
    @DisplayName("pushes stage.changed to the ticket topic with both stages")
    void pushesTicketTopic() {
        broadcaster.on(new TicketStageAdvanced(TICKET_ID, PROJECT_ID, "DEV", "QA"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(realtime).publish(org.mockito.ArgumentMatchers.eq("/topic/ticket.347"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> frame = (Map<String, Object>) payload.getValue();
        assertThat(frame)
                .containsEntry("event", "stage.changed")
                .containsEntry("ticketId", TICKET_ID)
                .containsEntry("fromStage", "DEV")
                .containsEntry("toStage", "QA");
    }

    @Test
    @DisplayName("tells the stage queues the ticket left and arrived, left before arrived")
    void updatesStageQueuesInOrder() {
        broadcaster.on(new TicketStageAdvanced(TICKET_ID, PROJECT_ID, "DEV", "QA"));

        InOrder order = inOrder(stageQueue);
        order.verify(stageQueue).left("DEV", PROJECT_ID);
        order.verify(stageQueue).arrived("QA", PROJECT_ID);
    }

    @Test
    @DisplayName("a failed ticket-topic push does not stop the stage queues from updating")
    void ticketPushFailureDoesNotBlockStageQueues() {
        doThrow(new RuntimeException("redis is briefly unreachable"))
                .when(realtime).publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        broadcaster.on(new TicketStageAdvanced(TICKET_ID, PROJECT_ID, "DEV", "QA"));

        verify(stageQueue).left("DEV", PROJECT_ID);
        verify(stageQueue).arrived("QA", PROJECT_ID);
    }

    @Test
    @DisplayName("a failed stage-queue update does not propagate out of the listener")
    void stageQueueFailureDoesNotPropagate() {
        doThrow(new RuntimeException("redis is briefly unreachable"))
                .when(stageQueue).left("DEV", PROJECT_ID);

        broadcaster.on(new TicketStageAdvanced(TICKET_ID, PROJECT_ID, "DEV", "QA"));

        verify(realtime).publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
