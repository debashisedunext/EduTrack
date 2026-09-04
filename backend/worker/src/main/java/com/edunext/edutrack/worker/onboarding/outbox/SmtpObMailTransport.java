package com.edunext.edutrack.worker.onboarding.outbox;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B-111 · onboarding mail over SMTP, active with
 * {@code edutrack.ob-outbox.email.transport=smtp}.
 *
 * <p><strong>"No new transport" is what this class is careful about.</strong>
 * Everything below the rendering is phase 1's: the {@code JavaMailSender} Boot
 * configures from {@code spring.mail.*}, the sender address from
 * {@code edutrack.mail.from}, the Message-ID domain from
 * {@code edutrack.mail.message-id-domain}, and D-034's suppression list
 * consulted by {@link EmailChannelAdapter} before this is reached. There is no
 * second SMTP configuration, no second provider, and no environment in which
 * onboarding mail could be pointed somewhere ticket mail is not.
 *
 * <p>What is genuinely new is the wording ({@link ObMailTemplate}) and the
 * layout it renders into — because §7's mail goes to a client's SPOC about their
 * implementation, and D-030's chrome talks about tickets.
 *
 * <p>The failure classification is {@code SmtpMailTransport}'s, unchanged and
 * for its reasons: a malformed address or an unrenderable body is permanent,
 * because retrying only delays telling somebody to fix it; bad credentials are
 * transient, because they are fixed without touching the row.
 */
@Component
@ConditionalOnProperty(name = "edutrack.ob-outbox.email.transport", havingValue = "smtp")
class SmtpObMailTransport implements ObMailTransport {

    private final JavaMailSender mailSender;
    private final ObMailRenderer renderer;
    private final ObMailThread thread;

    /**
     * SMTP refuses a message with no sender, so this cannot be left to the
     * session default: an unset From turns every send into a permanent failure,
     * which the dispatcher would faithfully retry and give up on.
     */
    private final String from;

    SmtpObMailTransport(JavaMailSender mailSender,
                        ObMailRenderer renderer,
                        ObMailThread thread,
                        @Value("${edutrack.mail.from:no-reply@edutrack.local}") String from) {
        this.mailSender = mailSender;
        this.renderer = renderer;
        this.thread = thread;
        this.from = from;
    }

    @Override
    public DeliveryOutcome send(ObOutboxMessage message) {
        ObMailContent content;
        try {
            content = renderer.render(message);
        } catch (RuntimeException e) {
            // The renderer is built not to throw — a missing template, a missing
            // variable and a broken layout all have answers. Reaching here is a
            // bug, and a bug is transient: the row waits for the fix rather than
            // being failed and forgotten.
            return new DeliveryOutcome.TransientFailure("Rendering failed: " + describe(e));
        }

        String subject = content.subject() == null ? "" : content.subject();

        try {
            MimeMessage mail = mailSender.createMimeMessage();
            // Multipart, because the body is HTML: a multipart/alternative means
            // a text-only client still gets something readable rather than
            // markup.
            MimeMessageHelper helper = new MimeMessageHelper(mail, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.details().email());
            helper.setSubject(subject);
            // Plain-text alternative first, HTML second — the order
            // MimeMessageHelper expects and the order RFC 2046 wants on the
            // wire, least-preferred part first. The subject is a truthful
            // fallback, being the one-line summary of what happened.
            helper.setText(subject, content.html());

            // Set before send, for D-032's reason: JavaMail's saveChanges()
            // replaces Message-ID with one of its own, and
            // JavaMailSenderImpl.doSend reads the header first and puts ours
            // back afterwards precisely so an explicit id survives. Setting it
            // after the send would be too late.
            mail.setHeader("Message-ID", thread.messageIdOf(message.id()));

            Optional<String> root = thread.rootOf(message);
            if (root.isPresent()) {
                // Both headers, deliberately: Outlook leans on In-Reply-To and
                // Gmail builds its conversation from References, so setting one
                // threads correctly in one client and not the other.
                mail.setHeader("In-Reply-To", root.get());
                mail.setHeader("References", root.get());
            }

            mailSender.send(mail);
            // JavaMailSender does not surface a provider message id. The
            // delivered_at column is filled in later by the provider's feed,
            // keyed on whatever a real provider integration reports here.
            return new DeliveryOutcome.Sent(null);
        } catch (MessagingException | MailParseException | MailPreparationException e) {
            return new DeliveryOutcome.PermanentFailure(describe(e));
        } catch (MailAuthenticationException e) {
            return new DeliveryOutcome.TransientFailure(describe(e));
        } catch (RuntimeException e) {
            return new DeliveryOutcome.TransientFailure(describe(e));
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
