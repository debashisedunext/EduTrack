package com.edunext.edutrack.api.realtime;

/**
 * One realtime message in transit between instances.
 *
 * <p>{@code destination} is the STOMP destination the payload should land on —
 * {@code /topic/ticket.4471}, {@code /user/12/queue/events} — and is resolved
 * per blueprint §9.3 by D-014. {@code payload} is whatever the feature sends;
 * it is serialised as JSON both across Redis and down to the browser.
 *
 * @param destination STOMP destination, including its {@code /topic} or
 *                    {@code /queue} prefix
 * @param payload     the event body
 */
public record RealtimeEvent(String destination, Object payload) {

    public RealtimeEvent {
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination is required");
        }
    }
}
