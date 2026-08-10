package com.edunext.edutrack.worker.outbox;

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

/**
 * SMTP transport, active with {@code edutrack.outbox.transport=smtp}.
 *
 * <p>The body is still the subject line. D-029/D-030 replace that with the
 * Thymeleaf render from the notification template master — the level chip,
 * stage, PCD and the primary "Open ticket" button. What is finished here is
 * the envelope: D-031's subject arrives already composed by
 * {@code OutboxEnqueuer}, and D-032's threading headers are set below.
 */
@Component
@ConditionalOnProperty(name = "edutrack.outbox.transport", havingValue = "smtp")
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;
    private final MailThread thread;

    /**
     * SMTP refuses a message with no sender, so this cannot be left to the
     * session default — an unset From turns every send into a permanent
     * failure, which the outbox would faithfully retry three times and give up
     * on.
     */
    private final String from;

    public SmtpMailTransport(JavaMailSender mailSender,
                             MailThread thread,
                             @Value("${edutrack.mail.from:no-reply@edutrack.local}") String from) {
        this.mailSender = mailSender;
        this.thread = thread;
        this.from = from;
    }

    @Override
    public SendOutcome send(OutboxMessage message) {
        String subject = message.subject() == null ? "" : message.subject();

        try {
            MimeMessage mail = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mail, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.toEmail());
            helper.setSubject(subject);
            helper.setText(subject);

            // D-032. Set before send on purpose: JavaMail's saveChanges()
            // replaces Message-ID with one of its own, and
            // JavaMailSenderImpl.doSend reads the header first and puts ours
            // back afterwards precisely so an explicit id survives. Setting it
            // after the send would be too late, and generating it inside a
            // MimeMessagePreparator too early. MailThreadingIT proves what
            // actually reaches the server rather than what we asked for.
            mail.setHeader("Message-ID", thread.messageIdOf(message.ticketId(), message.id()));

            if (message.ticketId() != null) {
                String root = thread.rootOf(message.ticketId());
                // Both, deliberately. Outlook leans on In-Reply-To and Gmail
                // builds its conversation from References; setting only one
                // threads correctly in one client and not the other.
                mail.setHeader("In-Reply-To", root);
                mail.setHeader("References", root);
            }

            mailSender.send(mail);
            // JavaMailSender does not surface the provider's message id. D-033
            // needs one for delivery proof, so a real provider integration
            // (SES/SendGrid) reports it here instead.
            return new SendOutcome.Sent(null);
        } catch (MessagingException | MailParseException | MailPreparationException e) {
            // The message itself is wrong — a malformed address or an
            // unrenderable body. Retrying only delays the failure notice.
            return new SendOutcome.PermanentFailure(describe(e));
        } catch (MailAuthenticationException e) {
            // Bad credentials are usually a deploy-time mistake, but they are
            // fixed without touching this row, so let it retry.
            return new SendOutcome.TransientFailure(describe(e));
        } catch (RuntimeException e) {
            return new SendOutcome.TransientFailure(describe(e));
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
