package com.edunext.edutrack.worker.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP transport, active with {@code edutrack.outbox.transport=smtp}.
 *
 * <p>The body is currently the subject line. D-029/D-030 replace this with the
 * Thymeleaf render from the notification template master — the level chip,
 * stage, PCD and the primary "Open ticket" button — and D-031/D-032 add the
 * subject pattern and the {@code Message-ID}/{@code In-Reply-To} threading
 * headers. This class exists now to prove the transport seam, not to be the
 * finished mail engine, which is why {@code logging} stays the default.
 */
@Component
@ConditionalOnProperty(name = "edutrack.outbox.transport", havingValue = "smtp")
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;

    public SmtpMailTransport(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public SendOutcome send(OutboxMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(message.toEmail());
        mail.setSubject(message.subject() == null ? "" : message.subject());
        mail.setText(message.subject() == null ? "" : message.subject());

        try {
            mailSender.send(mail);
            // JavaMailSender does not surface the provider's message id. D-033
            // needs one for delivery proof, so a real provider integration
            // (SES/SendGrid) reports it here instead.
            return new SendOutcome.Sent(null);
        } catch (MailParseException | MailPreparationException e) {
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
