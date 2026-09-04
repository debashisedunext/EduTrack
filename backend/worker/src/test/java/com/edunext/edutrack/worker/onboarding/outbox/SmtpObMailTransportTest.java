package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-111 · what the SMTP transport does with a send that goes wrong, and what it
 * puts on the message when it goes right.
 *
 * <p>The classification is the part worth pinning. A permanent failure stops the
 * retry ladder and raises the notice that gets somebody to fix an address; a
 * transient one keeps the row in the queue. Getting them the wrong way round
 * either retries a malformed address three times before telling anybody, or
 * gives up on a mail because SMTP credentials were briefly wrong.
 *
 * <p>What reaches the wire is proved in {@link ObMailDeliveryIT} instead — a
 * mocked sender can only show what was asked for, and JavaMail's
 * {@code saveChanges()} is free to overwrite it.
 */
class SmtpObMailTransportTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final ObMailRenderer renderer = mock(ObMailRenderer.class);

    private SmtpObMailTransport transport;

    @BeforeEach
    void setUp() {
        // A real MimeMessage from a real session: MimeMessageHelper writes into
        // it, and a mock would make every assertion about the envelope vacuous.
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        when(renderer.render(any())).thenReturn(
                new ObMailContent("Overdue by 2 working days: Data migration",
                        "<html><body><p>Body</p></body></html>"));
        transport = new SmtpObMailTransport(sender, renderer, new ObMailThread("edutrack.test"),
                "no-reply@edutrack.test");
    }

    @Test
    @DisplayName("a rendered mail is addressed, subjected and sent")
    void aRenderedMailIsSent() throws Exception {
        DeliveryOutcome outcome = transport.send(message(41, 12L));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.Sent.class);
        MimeMessage sent = capture();
        assertThat(sent.getSubject()).isEqualTo("Overdue by 2 working days: Data migration");
        assertThat(sent.getAllRecipients()[0]).hasToString("meera@edunext.test");
        assertThat(sent.getFrom()[0]).hasToString("no-reply@edutrack.test");
    }

    @Test
    @DisplayName("the body goes out as multipart, so a text-only client is not sent markup")
    void theBodyIsMultipart() throws Exception {
        transport.send(message(41, 12L));

        MimeMessage sent = capture();
        // saveChanges() is what fixes the content type on a message JavaMail has
        // not sent yet — before it, getContentType() still reports the default
        // and the assertion would be about nothing. It is also what would
        // overwrite our Message-ID, which is why the threading test uses a
        // message of its own.
        sent.saveChanges();
        assertThat(sent.getContentType()).startsWith("multipart/");

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        sent.writeTo(raw);
        assertThat(raw.toString(StandardCharsets.UTF_8))
                .contains("text/plain")
                .contains("text/html");
    }

    @Test
    @DisplayName("a journey's mail carries its conversation headers")
    void threadingHeadersAreSet() throws Exception {
        transport.send(message(41, 12L));

        MimeMessage sent = capture();
        assertThat(sent.getHeader("Message-ID")).containsExactly("<ob.41@edutrack.test>");
        assertThat(sent.getHeader("References")).containsExactly("<ob-journey.12@edutrack.test>");
        assertThat(sent.getHeader("In-Reply-To")).containsExactly("<ob-journey.12@edutrack.test>");
    }

    @Test
    @DisplayName("mail sent before any journey exists threads on the client")
    void aPreJourneyMailThreadsOnTheClient() throws Exception {
        // A login mail and a prerequisite reminder both arrive before a journey
        // has started, and they belong with everything that follows.
        transport.send(message(42, null));

        assertThat(capture().getHeader("References"))
                .containsExactly("<ob-client.77@edutrack.test>");
    }

    @Test
    @DisplayName("a malformed message is permanent — retrying only delays the notice")
    void aMalformedMessageIsPermanent() {
        doThrow(new MailParseException("bad address")).when(sender).send(any(MimeMessage.class));

        assertThat(transport.send(message(41, 12L)))
                .isInstanceOf(DeliveryOutcome.PermanentFailure.class);
    }

    @Test
    @DisplayName("bad credentials are transient — they are fixed without touching the row")
    void badCredentialsAreTransient() {
        doThrow(new MailAuthenticationException("nope")).when(sender).send(any(MimeMessage.class));

        assertThat(transport.send(message(41, 12L)))
                .isInstanceOf(DeliveryOutcome.TransientFailure.class);
    }

    @Test
    @DisplayName("a refused connection is transient")
    void aRefusedServerIsTransient() {
        doThrow(new MailSendException("connection refused")).when(sender).send(any(MimeMessage.class));

        DeliveryOutcome outcome = transport.send(message(41, 12L));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.TransientFailure.class);
        assertThat(((DeliveryOutcome.TransientFailure) outcome).reason()).contains("refused");
    }

    @Test
    @DisplayName("a renderer that throws costs the attempt, not the message")
    void aRenderingBugIsTransient() {
        // The renderer is built not to throw — a missing template, a missing
        // variable and a broken layout all have answers — so this is a bug. A
        // bug is transient: the row waits for the fix rather than being failed
        // and forgotten, which is the outcome that loses a sign-off request.
        when(renderer.render(any())).thenThrow(new IllegalStateException("boom"));

        DeliveryOutcome outcome = transport.send(message(41, 12L));

        assertThat(outcome).isInstanceOf(DeliveryOutcome.TransientFailure.class);
        assertThat(((DeliveryOutcome.TransientFailure) outcome).reason()).contains("Rendering failed");
    }

    // ───────────────────────────────────────────────────────────────── helpers

    private MimeMessage capture() {
        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    private static ObOutboxMessage message(long id, Long journeyId) {
        return new ObOutboxMessage(
                id, ObNotificationEvent.TAT_BREACHED.key(), ObChannel.EMAIL,
                new ObRecipient.Staff(5),
                new ObOutboxMessage.RecipientDetails("Meera Iyer", "meera@edunext.test",
                        null, false, true),
                77L, journeyId, 34L,
                Map.of("client_name", "Acme Ltd", "step_title", "Data migration",
                        "overdue_by", "2 working days"),
                0);
    }
}
