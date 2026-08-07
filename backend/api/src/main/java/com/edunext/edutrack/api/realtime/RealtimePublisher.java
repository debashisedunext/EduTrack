package com.edunext.edutrack.api.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The one way application code emits a realtime event.
 *
 * <p>It publishes to Redis and never touches the local broker directly, even
 * though the local broker is one Redis round-trip away. That is deliberate:
 * every instance — including the one that produced the event — receives it back
 * through {@link RealtimeRelay} and delivers it the same way. A "send locally
 * <em>and</em> publish for the others" design needs each instance to work out
 * whether a message is its own, and gets a duplicate to every browser attached
 * to the originating instance when it gets that wrong.
 *
 * <p>Delivery is best-effort, and that is the right guarantee here. Redis
 * pub/sub is fire-and-forget: an instance that is down, or a browser that is
 * disconnected, misses the message with no replay. For live UI nudges — a
 * ribbon advancing, a badge incrementing — that is fine, because the next page
 * load reads authoritative state from the database. It is <em>not</em> fine for
 * anything the user must not miss, which is why notifications are persisted
 * and replayed on next login by D-046 rather than relying on this.
 */
@Component
public class RealtimePublisher {

    /** Every instance subscribes to this one channel. */
    static final String RELAY_CHANNEL = "edutrack:realtime";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RealtimePublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Broadcast to every instance, which each deliver to their own sessions. */
    public void publish(RealtimeEvent event) {
        try {
            redis.convertAndSend(RELAY_CHANNEL, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // The payload is not serialisable — a programming error in the
            // caller, not a transport failure. Surface it rather than dropping
            // the event silently.
            throw new IllegalArgumentException(
                    "realtime payload for " + event.destination() + " is not JSON-serialisable", e);
        }
    }

    /** Convenience for the common {@code destination + body} call. */
    public void publish(String destination, Object payload) {
        publish(new RealtimeEvent(destination, payload));
    }
}
