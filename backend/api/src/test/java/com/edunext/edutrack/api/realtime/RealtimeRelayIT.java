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
import java.util.concurrent.CountDownLatch;
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
 * <p>Realtime touches no tables, and this test still starts no MySQL — but the
 * datasource <i>bean</i> can no longer be excluded, only the things that
 * connect. See below.
 *
 * <p><b>Stream A edit, A-021 — flagged for Debashis rather than changed
 * quietly (CLAUDE.md, code ownership).</b> {@code DataSourceAutoConfiguration}
 * and its transaction manager were dropped from this exclusion list. A-020's
 * {@code AuthUserRepository} needs a {@code JdbcClient}, which needs a
 * {@code JdbcTemplate}, which needs a {@code DataSource}; with the datasource
 * excluded the whole context fails to refresh on a bean this test never uses.
 * That break landed the moment A-020 merged and is Stream A's to fix — the same
 * three-line change is already in {@code ApplicationSmokeTest} and
 * {@code ContractConformanceTest}.
 *
 * <p>Nothing dials MySQL as a result: Spring Boot builds the
 * {@code HikariDataSource} without starting its pool, and the pool opens on the
 * first {@code getConnection()}, which no test here triggers. JPA and Flyway
 * stay excluded because both <i>do</i> connect during context refresh, and
 * {@code JpaRepositoriesAutoConfiguration} joins them — with a datasource
 * present it registers a shared {@code EntityManagerFactory} whose backing bean
 * {@code HibernateJpaAutoConfiguration} is no longer there to supply.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles({"local", "dev-noauth"})
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class RealtimeRelayIT {

    private static final String TICKET_TOPIC = "/topic/ticket.4471";

    /**
     * D-013 · this test now has to get past the subscription guard to do its
     * own job, which makes it a second, independent proof of two things: that
     * the {@code dev-noauth} principal survives the WebSocket handshake, and
     * that the interceptor is genuinely wired onto the inbound channel.
     *
     * <p>Granting exactly one ticket rather than everything, so the allow path
     * is a real decision and not a bypass.
     *
     * <p><b>Ordered first, and that is load-bearing.</b> The interceptor unions
     * the scopes with a short-circuiting {@code anyMatch}, and the other
     * implementation in the context — {@code ChatSubscriptionScope} — answers
     * from {@code chat_participants}. This test deliberately starts no MySQL
     * (see the class comment), so letting chat's scope be consulted would put a
     * doomed {@code getConnection()} on the SUBSCRIBE path: it turned CI red
     * while passing on any machine with a database on 3306. Answering here
     * first keeps the guarantee this class is built on — realtime is provable
     * without a database.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class GrantTheTestRoom {

        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        SubscriptionScope grantTicket4471() {
            return new SubscriptionScope() {
                @Override
                public boolean mayObserveTicket(long userId, long ticketId) {
                    return ticketId == 4471L;
                }
            };
        }
    }

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

    /** Marks the readiness probes below so no assertion ever sees one. */
    private static final String PROBE = "subscription.probe";

    /**
     * Subscribe, and do not return until this subscription has been proven to
     * deliver.
     *
     * <p>This used to be {@code session.subscribe(...)} followed by
     * {@code Thread.sleep(300)}, with a comment conceding the race it covered:
     * SUBSCRIBE is asynchronous, and a publish issued before the broker has
     * registered the subscription is simply dropped — pub/sub has no replay, so
     * the test then waits its whole poll timeout for a frame that was discarded
     * before it was ever routed. Three hundred milliseconds was enough on a
     * developer laptop and not on a loaded CI runner, which is the worst kind
     * of test: green where it is written, red where it is trusted. D-013 made
     * that visible by putting an authorisation check in front of every
     * SUBSCRIBE, but the race predates it and would have surfaced eventually
     * on its own.
     *
     * <p>A STOMP RECEIPT would be the obvious answer and does not work here:
     * the simple in-memory broker does not send them, so every subscription
     * would wait for an acknowledgement that is never coming. Instead the probe
     * below settles the question the only way this stack can — by delivering
     * something. Once a frame published to this destination has come back, the
     * subscription is registered <em>by demonstration</em>, and any later
     * publish has somewhere to land however slow the inbound channel was.
     *
     * <p>Probes are filtered out in the handler rather than drained afterwards,
     * because more than one is usually in flight by the time the first arrives
     * and a straggler landing mid-assertion would be read as the event under
     * test.
     *
     * <p>This also repairs {@code aSubscriberOnlyReceivesItsOwnDestination},
     * which asserts that nothing arrives: it passed in CI for the wrong reason,
     * since a subscription that was never registered receives nothing either.
     * It now has to be live before it can claim to be quiet.
     */
    @SuppressWarnings("unchecked")
    private BlockingQueue<Map<String, Object>> subscribe(String destination) throws Exception {
        BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
        CountDownLatch delivering = new CountDownLatch(1);

        session.subscribe(destination, new StompFrameHandler() {
            @Override
            @NonNull
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                Map<String, Object> frame = (Map<String, Object>) payload;
                if (PROBE.equals(frame.get("event"))) {
                    delivering.countDown();
                    return;
                }
                frames.add(frame);
            }
        });

        for (int attempt = 0; attempt < 100 && delivering.getCount() > 0; attempt++) {
            publisher.publish(destination, Map.of("event", PROBE));
            delivering.await(100, TimeUnit.MILLISECONDS);
        }
        assertThat(delivering.getCount())
                .as("the subscription to %s is live and delivering", destination)
                .isZero();
        return frames;
    }
}
