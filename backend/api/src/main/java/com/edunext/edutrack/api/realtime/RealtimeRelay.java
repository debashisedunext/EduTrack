package com.edunext.edutrack.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import java.nio.charset.StandardCharsets;

/**
 * The multi-instance half of D-012.
 *
 * <p>Spring's simple broker is per-JVM. With two api instances behind a load
 * balancer, a user connected to instance A sees nothing that happens on
 * instance B — the ribbon simply stops advancing for half the users, which is
 * the kind of fault that only shows up once the deployment scales past one pod.
 * This listens on the Redis channel every instance publishes to and hands each
 * message to its own local broker.
 *
 * <p>It never republishes what it receives. That is what stops a message
 * looping around the cluster forever.
 */
@Configuration
public class RealtimeRelay {

    @Bean
    RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory, RelayListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener,
                List.of(new ChannelTopic(RealtimePublisher.RELAY_CHANNEL)));
        return container;
    }

    /**
     * Delivers a relayed message to this instance's own STOMP sessions.
     *
     * <p>Implements {@link MessageListener} rather than going through
     * {@code MessageListenerAdapter}: the adapter deserialises the body with
     * JDK serialization by default, which cannot read the UTF-8 JSON the
     * publisher writes, and fails by silently dropping every message.
     */
    @Component
    static class RelayListener implements MessageListener {

        private static final Logger log = LoggerFactory.getLogger(RelayListener.class);

        private final SimpMessagingTemplate broker;
        private final ObjectMapper objectMapper;

        RelayListener(SimpMessagingTemplate broker, ObjectMapper objectMapper) {
            this.broker = broker;
            this.objectMapper = objectMapper;
        }

        @Override
        public void onMessage(@NonNull Message message, byte[] pattern) {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                JsonNode envelope = objectMapper.readTree(json);
                String destination = envelope.path("destination").asText(null);
                if (destination == null || destination.isBlank()) {
                    log.warn("realtime: relay message with no destination, dropped");
                    return;
                }
                broker.convertAndSend(destination, envelope.path("payload"));
            } catch (Exception e) {
                // One malformed message — a rolling deploy mid-format-change,
                // or something else writing to the channel — must not stop the
                // listener and take realtime down for this instance.
                log.warn("realtime: could not relay message, dropped", e);
            }
        }
    }
}
