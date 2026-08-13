package com.edunext.edutrack.api.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * D-059 · what a team's queue is told, and what it is deliberately not told.
 *
 * <p>The interesting assertion here is a negative one. A stage topic is a single
 * frame delivered to everyone watching that queue, so it cannot be scoped per
 * recipient — the same constraint D-054's ticket cards hit. Carrying no ticket
 * identity is what allows {@link StageQueueSubscriptionScope} to grant the room
 * on project membership without that granting a view of anybody's tickets, and
 * a field added here later would quietly undo that.
 */
class StageQueueBroadcasterTest {

    private final RealtimePublisher realtime = mock(RealtimePublisher.class);
    private final StageQueueBroadcaster broadcaster = new StageQueueBroadcaster(realtime);

    @Test
    @DisplayName("an arrival goes to that team's room on that project")
    void arrivalAddressing() {
        broadcaster.arrived("QA", 7L);

        assertThat(destination()).isEqualTo("/topic/stage.QA.7");
        assertThat(frame()).containsEntry("event", "stage.arrived");
    }

    @Test
    @DisplayName("a departure is a separate event, so a queue can shrink without a refetch race")
    void departureIsItsOwnEvent() {
        broadcaster.left("DEPLOYMENT", 3L);

        assertThat(destination()).isEqualTo("/topic/stage.DEPLOYMENT.3");
        assertThat(frame()).containsEntry("event", "stage.left");
    }

    @Test
    @DisplayName("the frame names the room and nothing else — no ticket, ever")
    void theFrameCarriesNoTicket() {
        broadcaster.arrived("QA", 7L);

        // This is the assertion that keeps the room openable. If a ticket id,
        // code, title or assignee is ever added here, every subscriber sees
        // every ticket passing through their team's queue, and
        // StageQueueSubscriptionScope's rule has to change from "are you on this
        // project" to "could you GET this ticket" — which nobody can be, for a
        // queue whose whole purpose is unclaimed work.
        assertThat(frame()).containsOnlyKeys("event", "stageCode", "projectId");
        assertThat(frame().values())
                .as("the queue's contents come from GET /stages/queue, which is scoped")
                .containsExactlyInAnyOrder("stage.arrived", "QA", 7L);
    }

    @Test
    @DisplayName("a ticket with no stage announces nothing")
    void noStageNoRoom() {
        // tickets.current_stage is nullable, so this is representable. A frame
        // addressed to "stage.null.7" would go to a room nothing subscribes to
        // and nothing reports — there is no queue to update.
        broadcaster.arrived(null, 7L);
        broadcaster.left("", 7L);
        broadcaster.left("   ", 7L);

        verify(realtime, never()).publish(anyString(), any());
    }

    private String destination() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(realtime).publish(captor.capture(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frame() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(realtime).publish(anyString(), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }
}
