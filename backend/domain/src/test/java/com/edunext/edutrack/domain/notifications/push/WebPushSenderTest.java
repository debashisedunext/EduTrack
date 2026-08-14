package com.edunext.edutrack.domain.notifications.push;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-045 · the delivery half, against a real HTTP server.
 *
 * <p>A stub server rather than a mocked client, because what is worth pinning
 * is the <em>request a push service actually receives</em> — the headers, the
 * body, the method. A mock would only prove that the code calls the mock.
 */
class WebPushSenderTest {

    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    private static final PushKeys KEYS = new PushKeys(
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
            "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
            "mailto:ops@edunext.test");

    private HttpServer server;
    private String endpoint;
    private final AtomicInteger status = new AtomicInteger(201);
    private final AtomicReference<Map<String, String>> received = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    private WebPushSender sender;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", this::handle);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/subscription-abc";

        sender = new WebPushSender(
                new PushEncryption(),
                new VapidSigner(Clock.fixed(Instant.parse("2026-08-11T09:00:00Z"), ZoneOffset.UTC)),
                KEYS,
                HttpClient.newHttpClient());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        received.set(Map.of(
                "TTL", header(exchange, "TTL"),
                "Content-Encoding", header(exchange, "Content-Encoding"),
                "Content-Type", header(exchange, "Content-Type"),
                "Urgency", header(exchange, "Urgency"),
                "Authorization", header(exchange, "Authorization"),
                "method", exchange.getRequestMethod()));
        body.set(exchange.getRequestBody().readAllBytes());
        exchange.sendResponseHeaders(status.get(), -1);
        exchange.close();
    }

    private static String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    // -------------------------------------------------------------- sending

    @Test
    @DisplayName("posts the encrypted body with the headers a push service requires")
    void theRequestIsShapedForAPushService() {
        WebPushSender.Result result = sender.send(endpoint, P256DH, AUTH, "{\"title\":\"hello\"}");

        assertThat(result).isEqualTo(WebPushSender.Result.DELIVERED);
        Map<String, String> headers = received.get();
        assertThat(headers.get("method")).isEqualTo("POST");
        assertThat(headers.get("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(headers.get("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(headers.get("TTL")).isEqualTo(String.valueOf(4 * 60 * 60));
        assertThat(headers.get("Urgency")).isEqualTo("normal");
        assertThat(headers.get("Authorization")).startsWith("vapid t=").contains(", k=" + KEYS.publicKey());
    }

    @Test
    @DisplayName("the body is the aes128gcm record, not the plaintext")
    void thePayloadIsEncryptedOnTheWire() {
        sender.send(endpoint, P256DH, AUTH, "{\"title\":\"Payment gateway timeout\"}");

        byte[] sent = body.get();
        // 86-byte header, then ciphertext and tag. The obvious failure this
        // catches is a refactor that posts the payload straight through — which
        // would work against a service that ignored Content-Encoding and leak
        // ticket titles to the push provider.
        assertThat(sent.length).isGreaterThan(86);
        assertThat(new String(sent, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("Payment gateway timeout");
        assertThat(java.nio.ByteBuffer.wrap(sent, 16, 4).getInt()).isEqualTo(PushEncryption.RECORD_SIZE);
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    @DisplayName("410 means this browser is gone for good")
    void anExpiredSubscriptionIsReportedGone() {
        status.set(410);

        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.GONE);
    }

    @Test
    @DisplayName("404 means the same — the subscription is not there")
    void anUnknownSubscriptionIsReportedGone() {
        status.set(404);

        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.GONE);
    }

    @Test
    @DisplayName("200 and 202 count as delivered, because push services differ")
    void otherSuccessCodesAreAccepted() {
        status.set(200);
        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.DELIVERED);
        status.set(202);
        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.DELIVERED);
    }

    @Test
    @DisplayName("429 and 5xx are worth another go; the subscription is fine")
    void congestionIsRetryable() {
        status.set(429);
        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.RETRYABLE);
        status.set(503);
        assertThat(sender.send(endpoint, P256DH, AUTH, "{}")).isEqualTo(WebPushSender.Result.RETRYABLE);
    }

    @Test
    @DisplayName("a rejected token is ours to fix, and repeating will not fix it")
    void aRejectedTokenIsNotRetried() {
        status.set(401);

        // Deliberately not GONE: deleting a perfectly good subscription because
        // our own VAPID key was wrong would turn a config error into permanent
        // data loss across every user at once.
        assertThat(sender.send(endpoint, P256DH, AUTH, "{}"))
                .isEqualTo(WebPushSender.Result.UNDELIVERABLE);
    }

    @Test
    @DisplayName("an unreachable push service is retryable, not fatal")
    void aDeadServiceIsRetryable() {
        server.stop(0);

        assertThat(sender.send(endpoint, P256DH, AUTH, "{}"))
                .isEqualTo(WebPushSender.Result.RETRYABLE);
    }

    // --------------------------------------------------------- not sending

    @Test
    @DisplayName("with no VAPID pair, nothing is sent and nothing pretends otherwise")
    void anUnconfiguredDeploymentSendsNothing() {
        WebPushSender unconfigured = new WebPushSender(
                new PushEncryption(),
                new VapidSigner(Clock.systemUTC()),
                new PushKeys(null, null, "mailto:ops@edunext.test"),
                HttpClient.newHttpClient());

        assertThat(unconfigured.send(endpoint, P256DH, AUTH, "{}"))
                .isEqualTo(WebPushSender.Result.NOT_CONFIGURED);
        assertThat(calls.get()).as("no request was made at all").isZero();
    }

    @Test
    @DisplayName("bad stored key material fails this subscription, not the batch")
    void aCorruptSubscriptionIsUndeliverable() {
        // A p256dh that decodes to the wrong length. The caller is looping over
        // somebody's devices; one bad row must not take the others down.
        assertThat(sender.send(endpoint, "c2hvcnQ", AUTH, "{}"))
                .isEqualTo(WebPushSender.Result.UNDELIVERABLE);
        assertThat(calls.get()).isZero();
    }
}
