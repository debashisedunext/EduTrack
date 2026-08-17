package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.NotificationTemplateRepository;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-032 · threading proven against a real SMTP server.
 *
 * <p>A unit test can only assert that we <em>asked</em> for a
 * {@code Message-ID}, and asking is not the hard part. JavaMail's
 * {@code saveChanges()} overwrites that header with one of its own during
 * send — so a mail engine that looks correct in a mock can put a different id
 * on the wire, and every ticket's mail arrives as unrelated messages with
 * nothing in any log to say so. The failure is only visible in somebody's
 * inbox, weeks later, as "why doesn't this thread".
 *
 * <p>So this sends through {@code JavaMailSenderImpl} to Mailpit — the same
 * server {@code application.yml} points local development at — and reads back
 * what was received.
 */
@Testcontainers
class MailThreadingIT {

    private static final int SMTP = 1025;
    private static final int HTTP = 8025;

    @Container
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:latest")
                    .withExposedPorts(SMTP, HTTP)
                    .waitingFor(Wait.forHttp("/").forPort(HTTP));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private SmtpMailTransport transport;

    @BeforeEach
    void wire() throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(MAILPIT.getHost());
        sender.setPort(MAILPIT.getMappedPort(SMTP));

        transport = new SmtpMailTransport(
                sender, new MailThread("edutrack.test"), renderer(), "no-reply@edutrack.test");

        deleteAllMail();
    }

    /**
     * A renderer on its fallback path: no template, no ticket context.
     *
     * <p>This IT is about the headers that reach the server, not about D-029's
     * substitution — {@code MailRendererTest} covers that. Wiring the real
     * repositories here would make a threading test depend on a seeded
     * {@code notification_templates} row, and it would fail for a reason with
     * nothing to do with threading. The fallback still exercises the multipart
     * envelope, which is the part of D-029 that could break a send.
     */
    private static MailRenderer renderer() {
        NotificationTemplateRepository templates = mock(NotificationTemplateRepository.class);
        when(templates.findByEventCodeAndChannel(anyString(), anyString())).thenReturn(Optional.empty());
        MailContextRepository context = mock(MailContextRepository.class);
        when(context.forTicket(anyLong())).thenReturn(MailContext.empty());

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        return new MailRenderer(templates, context, engine);
    }

    @Test
    @DisplayName("the Message-ID we set is the one that arrives")
    void ourMessageIdSurvivesTheSend() throws Exception {
        SendOutcome outcome = transport.send(mail(1L, 347L, "[CRM-26-00347] Handed to you at QA"));

        assertThat(outcome).isInstanceOf(SendOutcome.Sent.class);
        JsonNode received = onlyMessage();
        assertThat(header(received, "Message-ID"))
                .containsExactly("<ticket.347.mail.1@edutrack.test>");
    }

    @Test
    @DisplayName("two mails about one ticket reference the same root")
    void aTicketsMailIsOneConversation() throws Exception {
        transport.send(mail(1L, 347L, "[CRM-26-00347] Handed to you at QA"));
        transport.send(mail(2L, 347L, "[CRM-26-00347] Deployment failed"));

        List<JsonNode> messages = messages();
        assertThat(messages).hasSize(2);

        // What Gmail builds a conversation from and Outlook threads on.
        assertThat(messages).allSatisfy(message -> {
            assertThat(header(message, "References")).containsExactly("<ticket.347@edutrack.test>");
            assertThat(header(message, "In-Reply-To")).containsExactly("<ticket.347@edutrack.test>");
        });

        // ...while still being two distinct messages, not one delivered twice.
        assertThat(messages.stream().map(m -> header(m, "Message-ID").getFirst()).distinct())
                .hasSize(2);
    }

    @Test
    @DisplayName("a different ticket is a different conversation")
    void twoTicketsDoNotCollapseIntoOneThread() throws Exception {
        transport.send(mail(1L, 347L, "[CRM-26-00347] Handed to you at QA"));
        transport.send(mail(2L, 348L, "[CRM-26-00348] Handed to you at QA"));

        List<String> roots = messages().stream()
                .map(m -> header(m, "References").getFirst())
                .distinct()
                .toList();

        assertThat(roots).hasSize(2);
    }

    @Test
    @DisplayName("non-ticket mail threads with nothing")
    void aSystemMailCarriesNoReferences() throws Exception {
        transport.send(mail(9L, null, "Your weekly summary"));

        JsonNode received = onlyMessage();
        assertThat(header(received, "Message-ID")).containsExactly("<mail.9@edutrack.test>");
        // A digest is not part of any ticket's conversation, and referencing a
        // root would file it under whichever ticket it happened to name.
        assertThat(header(received, "References")).isEmpty();
        assertThat(header(received, "In-Reply-To")).isEmpty();
    }

    @Test
    @DisplayName("the subject arrives with the ticket code first — D-031")
    void theSubjectLeadsWithTheTicketCode() throws Exception {
        transport.send(mail(1L, 347L, "[CRM-26-00347] Handed to you at QA by Ravi Kumar"));

        assertThat(header(onlyMessage(), "Subject"))
                .containsExactly("[CRM-26-00347] Handed to you at QA by Ravi Kumar");
    }

    // ------------------------------------------------------------- helpers

    private static OutboxMessage mail(long id, Long ticketId, String subject) {
        return new OutboxMessage(id, ticketId, "TICKET_ASSIGNED", null, 4L,
                "ravi@edunext.test", subject, 0);
    }

    /**
     * Mailpit returns an array per header name, because a header can repeat.
     *
     * <p>Matched case-insensitively: it canonicalises names on the way in, so
     * the {@code Message-ID} we set is read back as {@code Message-Id}. RFC
     * 5322 field names are case-insensitive, so this is Mailpit being correct
     * rather than lossy — but an exact match here silently finds nothing and
     * the test passes for the wrong reason.
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
        List<JsonNode> messages = messages();
        assertThat(messages).hasSize(1);
        return messages.getFirst();
    }

    /**
     * Each message's raw headers.
     *
     * <p>Neither the list nor the message endpoint returns them — the list is
     * envelope fields and the message is parsed content — so this goes to
     * {@code /headers}, which is the only view of what was actually received.
     */
    private List<JsonNode> messages() throws Exception {
        JsonNode list = JSON.readTree(get("/api/v1/messages"));
        List<String> ids = list.path("messages").valueStream()
                .map(m -> m.path("ID").asText())
                .toList();

        return ids.stream().map(id -> {
            try {
                return JSON.readTree(get("/api/v1/message/" + id + "/headers"));
            } catch (Exception e) {
                throw new IllegalStateException("could not read message " + id, e);
            }
        }).toList();
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
