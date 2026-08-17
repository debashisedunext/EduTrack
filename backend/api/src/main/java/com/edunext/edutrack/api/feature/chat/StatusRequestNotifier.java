package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationPreferences;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
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
 * <p><strong>The ask now sends mail; the answer still does not.</strong> That
 * asymmetry is the blueprint's, not an oversight: §4B.6 lists "Status requested
 * by manager" as ❌ never optional, and §11 gives "Reply to status request" a
 * dash in the email column. Somebody waiting on an answer is already looking at
 * the thread.
 *
 * <p>Wiring it had to wait for D-029 and D-030 — until those landed there was
 * no template engine and no body to render, and a mandatory mail whose body was
 * its own subject line is not what §4B.6 asks for. D-036 made the lock correct
 * in advance ({@link NotificationEvent#isMandatoryMail()} covers
 * {@link NotificationEvent.Category#STATUS_REQUEST}, and
 * {@code NotificationPreferences.allows} answers before it reads a row), so
 * there was never a preference that could silence this once it existed. This is
 * the first of D-037's fifteen.
 */
@Component
class StatusRequestNotifier {

    private static final Logger log = LoggerFactory.getLogger(StatusRequestNotifier.class);

    private final NotificationWriter notifications;
    private final RealtimePublisher realtime;
    private final NotificationPreferences preferences;
    private final StatusRequestRepository people;
    private final OutboxEnqueuer outbox;

    StatusRequestNotifier(NotificationWriter notifications,
                          RealtimePublisher realtime,
                          NotificationPreferences preferences,
                          StatusRequestRepository people,
                          OutboxEnqueuer outbox) {
        this.notifications = notifications;
        this.realtime = realtime;
        this.preferences = preferences;
        this.people = people;
        this.outbox = outbox;
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
        mailTheAsk(askedOfId, ticketId);
    }

    /**
     * §4B.6's {@code [CRM-26-00347] Status requested}, which nobody may turn off.
     *
     * <p>The subject is the blueprint's wording and nothing more. A status
     * request is a manager asking a question in a thread, and putting the
     * question itself in a subject line would put whatever they typed in front
     * of a mail client's preview pane and its search index — the thread is
     * where the conversation belongs. D-031 adds the ticket code at the front.
     *
     * <p><strong>A failure here never propagates.</strong> The request row and
     * the message are already written and are the record; letting the mail path
     * roll back the ask would lose the thing the manager actually did. Logged at
     * error for the same reason the bell failure above is — a mandatory alert
     * that silently never went is precisely what §17 wants provable rather than
     * deniable.
     */
    private void mailTheAsk(long askedOfId, long ticketId) {
        try {
            people.activeEmailOf(askedOfId).ifPresent(address -> outbox.enqueue(NewMail.forTicket(
                    ticketId,
                    NotificationEvent.STATUS_REQUESTED.name(),
                    askedOfId,
                    address,
                    "Status requested")));
        } catch (RuntimeException e) {
            log.error("chat: could not queue the status-request mail for user {} on ticket {}",
                    askedOfId, ticketId, e);
        }
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
