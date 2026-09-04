package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B-110 · tells someone, in-app, when an onboarding notification has finally
 * failed.
 *
 * <p>D-033's rule carried over: three retries with backoff, "then a failure
 * notification in-app so nobody assumes a mail arrived that never did". The
 * queue row records the failure; a row nobody reads is not proof to the
 * person who was waiting on the message.
 *
 * <p><strong>Who gets told.</strong> A staff recipient is told directly —
 * they are the one who would otherwise sit unaware. A client contact has no
 * bell, so the Admins are told instead, the same audience D-034 alerts on a
 * bounce. B-112's notification centre may narrow that to the client's owner
 * once OB-13 knows who that is; today {@code ob_clients} has a sales person
 * and no owner column.
 *
 * <p>Reuses {@link NotificationEvent#MAIL_DELIVERY_FAILED} rather than adding
 * an onboarding event to Stream D's enum. It is the operational "this system
 * gave up on a message" code, which is exactly what happened; the channel is
 * named in the body.
 *
 * <p>Notification failure never propagates. The row is already FAILED, and an
 * error here escaping would roll the dispatcher into retrying a message the
 * adapter has already given up on.
 */
@Component
public class ObDeliveryFailureNotifier {

    static final NotificationEvent EVENT = NotificationEvent.MAIL_DELIVERY_FAILED;
    static final String TITLE = "Onboarding notification not delivered";

    private static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(ObDeliveryFailureNotifier.class);

    private final NotificationWriter notifications;

    public ObDeliveryFailureNotifier(NotificationWriter notifications) {
        this.notifications = notifications;
    }

    /**
     * @param message  the row that has just been marked FAILED
     * @param attempts how many deliveries were tried, including the last
     * @param reason   why it failed, shown to the recipient
     */
    public void notifyDeliveryFailed(ObOutboxMessage message, int attempts, String reason) {
        try {
            List<Long> recipients = resolveRecipients(message);
            if (recipients.isEmpty()) {
                log.warn("ob-outbox: row {} failed with no in-app recipient to notify ({})",
                        message.id(), message.details().email());
                return;
            }
            String body = body(message, attempts, reason);
            String link = message.obClientId() == null ? null : "/onboarding/clients/" + message.obClientId();
            for (Long userId : recipients) {
                notifications.write(new NewNotification(userId, null, EVENT, TITLE, body, link));
            }
        } catch (RuntimeException e) {
            log.error("ob-outbox: could not raise failure notification for row {}", message.id(), e);
        }
    }

    private List<Long> resolveRecipients(ObOutboxMessage message) {
        return switch (message.recipient()) {
            case ObRecipient.Staff s -> List.of(s.userId());
            case ObRecipient.Client c -> notifications.activeUsersInRole(ADMIN_ROLE);
        };
    }

    static String body(ObOutboxMessage message, int attempts, String reason) {
        StringBuilder body = new StringBuilder()
                .append("The ").append(message.eventKey())
                .append(' ').append(message.channel().name().toLowerCase(java.util.Locale.ROOT))
                .append(" message to ");
        String email = message.details().email();
        body.append(email == null || email.isBlank() ? message.details().name() : email);
        body.append(" could not be delivered");
        // A permanent failure gives up on the first attempt, so "after 0
        // retries" would be both wrong-sounding and misleading.
        int retries = attempts - 1;
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
