package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * D-055 / D-056 · the bell and the toast for a status request and its answer.
 *
 * <p>Blueprint §11 gives both directions popup and bell. The ask also gets mail
 * — §4B.6 lists it as <em>"Status requested by manager → Assignee →
 * {@code [CRM-26-00347] Status requested}"</em>, marked <strong>❌ never</strong>
 * in the "can be disabled" column — and the answer gets none, which is §11's
 * one dash on the "Reply to status request" row.
 *
 * <p><strong>No mail is sent from here yet, and that is a gap, not a
 * decision.</strong> D-029 and D-030 own the template engine and the body, and
 * there is nothing yet to render a mail with; D-037 wires the fifteen §4B.6
 * events once there is. The same position {@link MentionNotifier} takes, and
 * for the same reason — but recorded more loudly here, because a mention mail
 * is optional and this one is not. What this task <em>can</em> do in advance is
 * make sure the lock is already right when the wiring lands, which is why
 * {@link NotificationEvent#isMandatoryMail()} now covers
 * {@link NotificationEvent.Category#STATUS_REQUEST}.
 */
@Component
class StatusRequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(StatusRequestNotifier.class);

    private final NotificationWriter notifications;
    private final RealtimePublisher realtime;
    private final NotificationPreferences preferences;

    StatusRequestNotifier(NotificationWriter notifications,
                          RealtimePublisher realtime,
                          NotificationPreferences preferences) {
        this.notifications = notifications;
        this.realtime = realtime;
        this.preferences = preferences;
    }

    /** Somebody has been asked for a status. */
    void requested(long askedOfId,
                   long ticketId,
                   String ticketCode,
                   long threadId,
                   String managerName) {
        raise(NotificationEvent.STATUS_REQUESTED,
                askedOfId,
                ticketId,
                nameOr(managerName, "Your manager") + " asked for a status update",
                "on ticket " + nameOr(ticketCode, "a ticket you are assigned"),
                threadId);
    }

    /**
     * A manager's question has been answered.
     *
     * <p>Sent to the manager, not the room. The point of the metric is the round
     * trip, and the person who has been waiting is the one who wants to know it
     * is over — everybody else in the thread has just watched the reply arrive.
     */
    void answered(long requestedById,
                  long ticketId,
                  String ticketCode,
                  long threadId,
                  String responderName) {
        raise(NotificationEvent.STATUS_REQUEST_ANSWERED,
                requestedById,
                ticketId,
                nameOr(responderName, "Someone") + " answered your status request",
                "on ticket " + nameOr(ticketCode, "a ticket"),
                threadId);
    }

    private void raise(NotificationEvent event,
                       long recipientId,
                       long ticketId,
                       String title,
                       String body,
                       long threadId) {
        String link = "/chat/threads/" + threadId;

        long notificationId;
        try {
            notificationId = notifications.write(
                    new NewNotification(recipientId, ticketId, event, title, body, link));
        } catch (RuntimeException e) {
            // The request row and the message are already written and are the
            // record; letting a bell failure roll back the ask would be the
            // wrong trade. But a status request that silently never notified is
            // precisely the "missed alert" §17 wants provable rather than
            // deniable, so it is logged at error.
            log.error("chat: could not raise {} for user {} on ticket {}",
                    event, recipientId, ticketId, e);
            return;
        }

        Map<String, Object> frame = new HashMap<>();
        frame.put("event", "notification.created");
        frame.put("id", notificationId);
        frame.put("eventCode", event.name());
        frame.put("title", title);
        frame.put("body", body);
        frame.put("link", link);
        frame.put("threadId", threadId);
        frame.put("ticketId", ticketId);

        // D-042. The preference silences the popup and never the record — the
        // bell entry above is already written either way, and S-26's "Status
        // requests" tab is where somebody goes to find what they missed.
        if (preferences.allows(recipientId, event.name(), NotificationChannel.IN_APP)) {
            realtime.publish(RealtimeDestinations.user(recipientId), frame);
        }
    }

    private static String nameOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
