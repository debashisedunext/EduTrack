package com.edunext.edutrack.api.realtime;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * D-059 · tells a team their queue moved (blueprint §9.3, S-31).
 *
 * <p>Two events, both on {@code /topic/stage.{code}.{projectId}}:
 * {@code stage.arrived} when a ticket enters a stage and {@code stage.left}
 * when it goes. "Waiting in QA" then updates without a refresh, which is the
 * whole point of a queue-driven screen — §17 item 12 names the failure it
 * prevents: <em>"tickets stall between the handoff and someone noticing"</em>.
 *
 * <h2>The frame carries no ticket, and that is the design</h2>
 *
 * <p>{@code {event, stageCode, projectId}}. No id, no code, no title, no
 * assignee. Three reasons, and the first is the one that matters:
 *
 * <ul>
 *   <li><strong>It is what lets the room be open to the team at all.</strong>
 *       A stage topic is one frame to everyone watching that queue, so it cannot
 *       be scoped per recipient — the same constraint D-054's ticket cards ran
 *       into. Putting a ticket in the frame would mean every subscriber sees
 *       every ticket that passes through their team's queue, and the
 *       subscription rule would have to become "could you GET this ticket",
 *       which nobody can be, for a queue whose whole purpose is unclaimed work.
 *       Carrying nothing keeps the two questions separate.</li>
 *   <li>The client refetches {@code GET /stages/queue}, so the database is the
 *       only answer and a missed frame leaves a client stale rather than wrong
 *       — D-044's argument, and realtime here is explicitly best-effort.</li>
 *   <li>A queue changes by arrival and departure, not by content. Two tickets
 *       arriving together are one refetch either way.</li>
 * </ul>
 *
 * <p>Anyone adding a ticket identity to these frames has to change
 * {@link StageQueueSubscriptionScope} first. In a diff that looks like adding a
 * field.
 *
 * <h2>Nothing calls this yet</h2>
 *
 * <p><strong>C-042 writes the {@code ticket_stage_transitions} rows and C-045
 * seals and inserts them on handoff; neither exists.</strong> There is no code
 * path in the application today that moves a ticket between stages, so there is
 * nothing to announce. This is the seam, with the frame decided and tested, so
 * that wiring it is one call at the end of C-045's transaction rather than a
 * design conversation held under time pressure:
 *
 * <pre>{@code
 * stageQueue.left(previousStage, projectId);
 * stageQueue.arrived(nextStage, projectId);
 * }</pre>
 *
 * <p>After the commit, not inside it — a queue told to refetch before the row is
 * visible reads the state it was told had changed and finds it unchanged.
 */
@Component
public class StageQueueBroadcaster {

    private final RealtimePublisher realtime;

    StageQueueBroadcaster(RealtimePublisher realtime) {
        this.realtime = realtime;
    }

    /** A ticket has entered this stage on this project. */
    public void arrived(String stageCode, long projectId) {
        publish("stage.arrived", stageCode, projectId);
    }

    /** A ticket has left this stage on this project. */
    public void left(String stageCode, long projectId) {
        publish("stage.left", stageCode, projectId);
    }

    private void publish(String event, String stageCode, long projectId) {
        if (stageCode == null || stageCode.isBlank()) {
            // A ticket with no current stage is representable — tickets.current_stage
            // is nullable — and addressing a room called "stage.null.7" would
            // create a destination nothing subscribes to and nothing reports.
            // Dropping it is right: there is no queue to update.
            return;
        }
        realtime.publish(
                RealtimeDestinations.stage(stageCode, projectId),
                Map.of("event", event, "stageCode", stageCode, "projectId", projectId));
    }
}
