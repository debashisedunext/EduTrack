package com.edunext.edutrack.worker.outbox;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D-033 · tells someone, in-app, when a mail has finally failed.
 *
 * <p>Blueprint §4B.6: three retries with exponential backoff, "then a failure
 * notification in-app so nobody assumes a mail arrived that never did". §17
 * puts the same idea more sharply — a missed alert must be <em>provable rather
 * than deniable</em>. {@code email_log} already records the failure, but a row
 * nobody reads is not proof to the person who was waiting on the mail.
 *
 * <p><strong>Who gets told.</strong> The intended recipient, when the mail was
 * addressed to a user: they are the one who would otherwise sit unaware that a
 * handoff was assigned to them. When the recipient is a client contact there is
 * no in-app identity to notify, so it goes to the Admins instead — the same
 * audience D-034 alerts on a bounce.
 *
 * <p>Notification failure never propagates. The mail is already marked FAILED
 * by then, and letting a notification error escape would roll the worker into
 * retrying a message the transport has already given up on.
 */
@Component
public class MailFailureNotifier {

    /** §11 event code for the bell entry. */
    static final NotificationEvent EVENT_CODE = NotificationEvent.MAIL_DELIVERY_FAILED;

    private static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(MailFailureNotifier.class);

    private final NotificationWriter notifications;

    public MailFailureNotifier(NotificationWriter notifications) {
        this.notifications = notifications;
    }

    /**
     * @param message the message that has just been marked FAILED
     * @param reason  why it failed, shown to the recipient
     */
    public void notifyDeliveryFailed(OutboxMessage message, String reason) {
        try {
            List<Long> recipients = resolveRecipients(message);
            if (recipients.isEmpty()) {
                // Nobody to tell is itself worth recording: the failure would
                // otherwise be invisible outside email_log.
                log.warn("outbox: mail {} failed with no in-app recipient to notify (to={})",
                        message.id(), message.toEmail());
                return;
            }
            for (Long userId : recipients) {
                notifications.write(new NewNotification(
                        userId,
                        message.ticketId(),
                        EVENT_CODE,
                        "Email delivery failed",
                        body(message, reason),
                        message.ticketId() == null ? null : "/tickets/" + message.ticketId()));
            }
        } catch (RuntimeException e) {
            log.error("outbox: could not raise failure notification for mail {}", message.id(), e);
        }
    }

    private List<Long> resolveRecipients(OutboxMessage message) {
        if (message.toUserId() != null) {
            return List.of(message.toUserId());
        }
        return notifications.activeUsersInRole(ADMIN_ROLE);
    }

    private static String body(OutboxMessage message, String reason) {
        StringBuilder body = new StringBuilder()
                .append("The ").append(message.eventCode())
                .append(" email to ").append(message.toEmail())
                .append(" could not be delivered");
        // A permanent failure gives up on the first attempt, so "after 0
        // retries" would be both wrong-sounding and misleading.
        int retries = message.retryCount();
        if (retries > 0) {
            body.append(" after ").append(retries).append(retries == 1 ? " retry" : " retries");
        }
        body.append('.');
        if (reason != null && !reason.isBlank()) {
            body.append(" Reason: ").append(reason);
        }
        return body.toString();
    }
}
