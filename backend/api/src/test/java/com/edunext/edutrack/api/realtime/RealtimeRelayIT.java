package com.edunext.edutrack.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-012 · realtime proven over a real WebSocket.
 *
 * <p>A real STOMP client against a real server, because the interesting
 * failures live in the wiring — SockJS transport paths, the broker's
 * destination prefixes, the relay's serialisation — and every one of those is
 * invisible to a test that calls {@code SimpMessagingTemplate} directly.
 *
 * <p><b>The datasource is no longer excluded.</b> It was, because realtime
 * touches no tables — until A-020 added {@code AuthUserRepository}, which needs
 * a {@code JdbcClient}, which needs a {@code DataSource}. Excluding it made
 * this context unbuildable, the same breakage A-020 fixed in
 * {@code ApplicationSmokeTest}.
 *
 * <p>It still costs nothing and still needs no MySQL: Spring Boot builds the
 * {@code HikariDataSource} without starting its pool, and the pool opens on the
 * first {@code getConnection()}, which nothing here performs. Only Hibernate
 * and Flyway have to stay excluded — those two do dial the database during
 * context refresh.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class RealtimeRelayIT {

    private static final String TICKET_TOPIC = "/topic/ticket.4471";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Autowired
    RealtimePublisher publisher;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;
    private StompSession session;

    @BeforeEach
    void connect() throws Exception {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        // SockJS exposes the raw WebSocket transport under /websocket.
        session = stompClient
                .connectAsync("ws://localhost:" + port + WebSocketConfig.STOMP_ENDPOINT + "/websocket",
                        new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        stompClient.stop();
    }

    @Test
    void anEventPublishedOnThisInstanceReachesASubscribedBrowser() throws Exception {
        BlockingQueue<Map<String, Object>> received = subscribe(TICKET_TOPIC);

        publisher.publish(TICKET_TOPIC, Map.of("event", "stage.changed", "stage", "QA"));

        Map<String, Object> frame = received.poll(10, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame).containsEntry("event", "stage.changed").containsEntry("stage", "QA");
    }

    /**
     * The reason the relay exists. Publishing straight onto the Redis channel
     * is exactly what a second api instance does, so a client attached to
     * <em>this</em> instance receiving it is the multi-instance guarantee:
     * without the relay the ribbon would stop advancing for every user who
     * happened to land on a different pod.
     */
    @Test
    void anEventFromAnotherInstanceReachesThisInstancesBrowser() throws Exception {
        BlockingQueue<Map<String, Object>> received = subscribe(TICKET_TOPIC);

        String envelopeFromElsewhere = objectMapper.writeValueAsString(
                new RealtimeEvent(TICKET_TOPIC, Map.of("event", "comment.added", "author", "Ravi")));
        redisTemplate.convertAndSend(RealtimePublisher.RELAY_CHANNEL, envelopeFromElsewhere);

        Map<String, Object> frame = received.poll(10, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        assertThat(frame).containsEntry("event", "comment.added").containsEntry("author", "Ravi");
    }

    @Test
    void aSubscriberOnlyReceivesItsOwnDestination() throws Exception {
        BlockingQueue<Map<String, Object>> ticketFrames = subscribe(TICKET_TOPIC);

        publisher.publish("/topic/ticket.9999", Map.of("event", "stage.changed"));

        assertThat(ticketFrames.poll(2, TimeUnit.SECONDS))
                .as("a different ticket's topic must not leak into this subscription")
                .isNull();
    }

    /** One bad message must not take realtime down for the whole instance. */
    @Test
    void aMalformedRelayMessageIsDroppedAndTheListenerSurvives() throws Exception {
        BlockingQueue<Map<String, Object>> received = subscribe(TICKET_TOPIC);

        redisTemplate.convertAndSend(RealtimePublisher.RELAY_CHANNEL, "this is not json");
        redisTemplate.convertAndSend(RealtimePublisher.RELAY_CHANNEL, "{\"payload\":{\"a\":1}}"); // no destination

        publisher.publish(TICKET_TOPIC, Map.of("event", "still.working"));

        Map<String, Object> frame = received.poll(10, TimeUnit.SECONDS);
        assertThat(frame).as("the listener kept running after two bad messages").isNotNull();
        assertThat(frame).containsEntry("event", "still.working");
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(String destination) throws Exception {
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                frames.add((Map<String, Object>) payload);
            }
        });
        // The SUBSCRIBE frame is asynchronous; without this the first publish
        // can race ahead of the broker registering the subscription.
        Thread.sleep(300);
        return frames;
    }
}
