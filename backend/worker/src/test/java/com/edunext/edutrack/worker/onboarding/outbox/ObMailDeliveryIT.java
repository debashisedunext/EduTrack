package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-111 · an onboarding mail proved against a real SMTP server.
 *
 * <p>A unit test can only assert what we <em>asked</em> for, and asking is not
 * the hard part. D-032 learned it the expensive way: JavaMail's
 * {@code saveChanges()} overwrites {@code Message-ID} during send, so a mail
 * engine that looks correct against a mock can put a different id on the wire
 * and every journey's mail arrives as unrelated messages, with nothing in any
 * log to say so. The same applies to the multipart envelope — a
 * {@code multipart/alternative} that is malformed still leaves the process, and
 * the failure is only visible in somebody's inbox.
 *
 * <p>So this sends through {@code JavaMailSenderImpl} to Mailpit — the server
 * {@code application.yml} already points local development at — and reads back
 * what was received.
 */
@Testcontainers
class ObMailDeliveryIT {

    private static final int SMTP = 1025;
    private static final int HTTP = 8025;

    @Container
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:latest")
                    .withExposedPorts(SMTP, HTTP)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private SmtpObMailTransport transport;

    @BeforeEach
    void wire() throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAILPIT.getHost());
        sender.setPort(MAILPIT.getMappedPort(SMTP));

        // The real renderer over the real layout. Unlike MailThreadingIT, which
        // mocks a template repository because its wording lives in the database,
        // B-111's catalogue is in the code — so there is nothing to seed and no
        // reason to render anything but the real thing.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine thymeleaf = new SpringTemplateEngine();
        thymeleaf.setTemplateResolver(resolver);
        ObMailRenderer renderer = new ObMailRenderer(
                new ObMailLinks("https://edutrack.example"), new ObDigestBody(), thymeleaf);

        transport = new SmtpObMailTransport(sender, renderer,
                new ObMailThread("edutrack.test"), "no-reply@edutrack.test");

        deleteAllMail();
    }

    @Test
    @DisplayName("a breach mail arrives addressed, subjected and readable")
    void aBreachMailArrives() throws Exception {
        DeliveryOutcome outcome = transport.send(breach(41));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Sent.class);
        JsonNode message = onlyMessage();
        assertThat(message.path("Subject").asText())
                .isEqualTo("Overdue by 2 working days: Data migration — Acme Ltd");
        assertThat(message.path("From").path("Address").asText()).isEqualTo("no-reply@edutrack.test");
        assertThat(message.path("To").get(0).path("Address").asText()).isEqualTo("meera@edunext.test");
    }

    @Test
    @DisplayName("both parts arrive — a text-only client is not sent markup")
    void bothAlternativesArrive() throws Exception {
        transport.send(breach(41));

        JsonNode message = onlyMessage();
        assertThat(message.path("HTML").asText()).contains("Data migration").contains("EduTrack");
        // The plain-text alternative is the subject: a truthful one-line summary
        // of what happened, and the only thing a text-only reader gets.
        assertThat(message.path("Text").asText()).contains("Overdue by 2 working days");
    }

    @Test
    @DisplayName("the escaped body is what reaches the inbox, not the markup it escaped")
    void escapingSurvivesTheWire() throws Exception {
        transport.send(new ObOutboxMessage(
                44, ObNotificationEvent.CLIENT_ESCALATION_RAISED.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test", null, false, true),
                77L, 12L, null,
                Map.of("client_name", "Acme Ltd",
                        "escalation_comment", "<script>alert('x')</script> nothing is moving"),
                0));

        // Mailpit returns the HTML part as received. A client's comment that
        // arrives as live markup here is stored XSS in a manager's mail client.
        assertThat(onlyMessage().path("HTML").asText())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("the Message-ID we set is the one that arrives")
    void ourMessageIdSurvivesTheSend() throws Exception {
        transport.send(breach(41));

        assertThat(header(headersOfOnlyMessage(), "Message-ID"))
                .containsExactly("<ob.41@edutrack.test>");
    }

    @Test
    @DisplayName("a journey's mail is one conversation, and two journeys are two")
    void aJourneysMailIsOneConversation() throws Exception {
        transport.send(breach(41));           // journey 12
        transport.send(breach(42));           // journey 12
        transport.send(reminderOnJourney(43, 99L));

        List<JsonNode> headers = allHeaders();
        assertThat(headers).hasSize(3);

        List<String> roots = headers.stream()
                .map(h -> header(h, "References").getFirst())
                .distinct()
                .toList();
        assertThat(roots).containsExactlyInAnyOrder(
                "<ob-journey.12@edutrack.test>", "<ob-journey.99@edutrack.test>");

        // Outlook threads on In-Reply-To and Gmail builds its conversation from
        // References, so both are set — and every message is still distinct, or a
        // client is free to treat the second as a copy and drop it.
        assertThat(headers).allSatisfy(h ->
                assertThat(header(h, "In-Reply-To")).hasSize(1));
        assertThat(headers.stream().map(h -> header(h, "Message-ID").getFirst()).distinct())
                .hasSize(3);
    }

    @Test
    @DisplayName("a client's sign-off mail carries the token link and no staff link")
    void aSignoffMailCarriesItsToken() throws Exception {
        String signoffUrl = "https://edutrack.example/signoff/a1b2c3";
        transport.send(new ObOutboxMessage(
                45, ObNotificationEvent.SIGNOFF_REQUESTED.key(), ObChannel.EMAIL,
                new ObRecipient.Client(9),
                new ObOutboxMessage.RecipientDetails("Priya Nair", "priya@acme.test", null, true, true),
                77L, 12L, 34L,
                Map.of("client_name", "Acme Ltd", "step_title", "Go-live sign-off",
                        "action_url", signoffUrl),
                0));

        String html = onlyMessage().path("HTML").asText();
        assertThat(html).contains(signoffUrl);
        assertThat(html).doesNotContain("/onboarding/clients/");
    }

    // ───────────────────────────────────────────────────────────────── fixtures

    private static ObOutboxMessage breach(long id) {
        return new ObOutboxMessage(
                id, ObNotificationEvent.TAT_BREACHED.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test", null, false, true),
                77L, 12L, 34L,
                Map.of("client_name", "Acme Ltd", "step_title", "Data migration",
                        "overdue_by", "2 working days"),
                0);
    }

    private static ObOutboxMessage reminderOnJourney(long id, long journeyId) {
        return new ObOutboxMessage(
                id, ObNotificationEvent.TAT_REMINDER.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test", null, false, true),
                77L, journeyId, 35L,
                Map.of("client_name", "Acme Ltd", "step_title", "Configuration & branding",
                        "due_on", "22 Sep 2026"),
                0);
    }

    // ───────────────────────────────────────────────────────────────── helpers

    /**
     * Mailpit returns an array per header name, because a header can repeat.
     *
     * <p>Matched case-insensitively: it canonicalises names on the way in, so the
     * {@code Message-ID} we set is read back as {@code Message-Id}. RFC 5322
     * field names are case-insensitive, so this is Mailpit being correct rather
     * than lossy — but an exact match here finds nothing and passes for the wrong
     * reason.
     */
    private static List<String> header(JsonNode headers, String name) {
        for (var field : headers.properties()) {
            if (field.getKey().equalsIgnoreCase(name)) {
                return field.getValue().isArray()
                        ? field.getValue().valueStream().map(JsonNode::asText).toList()
                        : List.of(field.getValue().asText());
            }
        }
        return List.of();
    }

    private JsonNode onlyMessage() throws Exception {
        List<String> ids = messageIds();
        assertThat(ids).hasSize(1);
        return JSON.readTree(get("/api/v1/message/" + ids.getFirst()));
    }

    private JsonNode headersOfOnlyMessage() throws Exception {
        List<String> ids = messageIds();
        assertThat(ids).hasSize(1);
        return JSON.readTree(get("/api/v1/message/" + ids.getFirst() + "/headers"));
    }

    private List<JsonNode> allHeaders() throws Exception {
        return messageIds().stream().map(id -> {
            try {
                return JSON.readTree(get("/api/v1/message/" + id + "/headers"));
            } catch (Exception e) {
                throw new IllegalStateException("could not read message " + id, e);
            }
        }).toList();
    }

    private List<String> messageIds() throws Exception {
        JsonNode list = JSON.readTree(get("/api/v1/messages"));
        return list.path("messages").valueStream().map(m -> m.path("ID").asText()).toList();
    }

    private void deleteAllMail() throws Exception {
        HTTP_CLIENT.send(HttpRequest.newBuilder(mailpit("/api/v1/messages")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private String get(String path) throws Exception {
        return HTTP_CLIENT.send(HttpRequest.newBuilder(mailpit(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static URI mailpit(String path) {
        return URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(HTTP) + path);
    }
}
