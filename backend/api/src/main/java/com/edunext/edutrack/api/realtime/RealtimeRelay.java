package com.edunext.edutrack.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <p><strong>The broker must not be able to stop the application booting.</strong>
 * The container subscribes during the lifecycle start phase, and a failure there
 * propagates straight out of {@code SpringApplication.run} — so with Redis
 * unreachable the whole api died on startup, serving nothing at all. That is
 * the wrong trade twice over: it took down every REST endpoint over a
 * degradation confined to realtime, and it meant a Redis restart could roll
 * through the fleet and leave nothing running. Startup is therefore deferred to
 * {@link RelayStarter}, which retries in the background.
 */
@Configuration
public class RealtimeRelay {

    @Bean
    RedisMessageListenerContainer realtimeListenerContainer(
            RedisConnectionFactory connectionFactory, RelayListener listener) {
        // Started by RelayStarter rather than by the lifecycle, so an
        // unreachable broker degrades realtime instead of killing the process.
        // The container exposes no setAutoStartup, so the SmartLifecycle default
        // is overridden here.
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener,
                List.of(new ChannelTopic(RealtimePublisher.RELAY_CHANNEL)));
        return container;
    }

    /**
     * Subscribes the relay once the context is up, and keeps trying until it
     * succeeds.
     *
     * <p>Failure is logged at ERROR with what it actually costs — this instance
     * stops seeing events raised on other instances, so a ribbon advanced
     * elsewhere will not move here. Blueprint §17 asks for a missed alert to be
     * provable rather than deniable, and a relay that never subscribed is
     * exactly the kind of fault that otherwise shows up only as users saying
     * the screen "sometimes doesn't update".
     *
     * <p>{@code ContextRefreshedEvent} rather than {@code ApplicationReadyEvent}
     * because the latter is not published by every test context, and a relay
     * that silently never starts under test would defeat {@code RealtimeRelayIT}.
     */
    @Component
    static class RelayStarter implements DisposableBean {

        private static final Logger log = LoggerFactory.getLogger(RelayStarter.class);

        private static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);

        /**
         * Whether the subscription actually succeeded.
         *
         * <p>Tracked here because the container's own {@code isRunning()} cannot
         * answer it: {@code start()} flips that flag before the subscription is
         * established, so a container that failed to reach Redis still reports
         * itself as running. Gating the retry on {@code isRunning()} therefore
         * looks correct and silently never retries.
         */
        private final AtomicBoolean subscribed = new AtomicBoolean();

        private final RedisMessageListenerContainer container;
        private final ScheduledExecutorService retries =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "realtime-relay-starter");
                    // Daemon: a relay waiting on a broker that never returns
                    // must not hold the JVM open at shutdown.
                    thread.setDaemon(true);
                    return thread;
                });

        RelayStarter(RedisMessageListenerContainer container) {
            this.container = container;
        }

        @EventListener(ContextRefreshedEvent.class)
        void subscribe() {
            attempt();
        }

        private void attempt() {
            if (subscribed.get()) {
                return;
            }
            try {
                // A failed start leaves the container believing it is running,
                // and start() on a running container is a no-op — so reset it
                // first or every retry after the first does nothing at all.
                if (container.isRunning()) {
                    container.stop();
                }
                container.start();
                subscribed.set(true);
                log.info("realtime: relay subscribed to {}", RealtimePublisher.RELAY_CHANNEL);
            } catch (RuntimeException e) {
                log.error("realtime: relay could not subscribe to {} — this instance will not "
                                + "receive events raised on other instances. Retrying in {}s.",
                        RealtimePublisher.RELAY_CHANNEL, RETRY_INTERVAL.toSeconds(), e);
                retries.schedule(this::attempt, RETRY_INTERVAL.toSeconds(), TimeUnit.SECONDS);
            }
        }

        @Override
        public void destroy() {
            retries.shutdownNow();
        }
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
