package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D-052 · raises the bell entry when somebody is mentioned in chat.
 *
 * <p>Blueprint §11 lists "@mention in chat/comment" as popup, bell and email to
 * the mentioned user. This covers the two in-app channels. <strong>Email is
 * deliberately not sent here</strong> — §4B.6 marks mention mail optional, and
 * the per-user preference matrix that makes it optional is D-042. Sending an
 * unsuppressible mail for an event the spec says users may switch off is worse
 * than sending it late, so D-037 wires it once there is something to consult.
 *
 * <p><strong>The notification carries no message text.</strong> §7.6 lets an
 * author delete what they said and have the words withheld from then on; a bell
 * entry quoting the body would be the one copy the tombstone cannot reach, and
 * it would sit in the recipient's notification list indefinitely. The blueprint's
 * own example subject — {@code [CRM-26-00347] You were mentioned} — carries no
 * preview either. Where and by whom is enough to decide whether to look.
 */
@Component
class MentionNotifier {

    /** §11's "@mention in chat/comment". Also the {@code tab=mentions} filter in S-26. */
    static final NotificationEvent EVENT_CODE = NotificationEvent.MENTIONED;

    private static final Logger log = LoggerFactory.getLogger(MentionNotifier.class);

    private final NotificationWriter notifications;
    private final RealtimePublisher realtime;

    MentionNotifier(NotificationWriter notifications, RealtimePublisher realtime) {
        this.notifications = notifications;
        this.realtime = realtime;
    }

    /**
     * @param mentions who was mentioned, already narrowed to thread participants
     * @param authorId excluded — mentioning yourself is a way of addressing the
     *                 room, not a request to be told about it
     */
    void mentioned(ChatRepository.ThreadAnchor anchor,
                   long messageId,
                   long authorId,
                   String authorName,
                   List<ChatRepository.MentionedUser> mentions) {
        for (ChatRepository.MentionedUser mentioned : mentions) {
            if (mentioned.id() == authorId) {
                continue;
            }
            raise(anchor, messageId, authorName, mentioned);
        }
    }

    private void raise(ChatRepository.ThreadAnchor anchor,
                       long messageId,
                       String authorName,
                       ChatRepository.MentionedUser mentioned) {
        String title = (authorName == null || authorName.isBlank() ? "Someone" : authorName)
                + " mentioned you";
        String where = whereItHappened(anchor);
        String link = "/chat/threads/" + anchor.id();

        long notificationId;
        try {
            notificationId = notifications.write(new NewNotification(
                    mentioned.id(), anchor.ticketId(), EVENT_CODE, title, where, link));
        } catch (RuntimeException e) {
            // The message itself is already written and is the record. Letting a
            // bell failure roll back somebody's post would be the wrong trade,
            // but a mention that silently never notified is exactly the "missed
            // alert" §17 wants provable rather than deniable — so it is logged.
            log.error("chat: could not raise mention notification for user {} on message {}",
                    mentioned.id(), messageId, e);
            return;
        }

        // A separate push from the chat.message broadcast on purpose. The
        // mentioned user is already in the thread and will receive that one; a
        // toast is a different surface with a different lifetime, and D-043
        // needs the notification id to act on it.
        Map<String, Object> event = new HashMap<>();
        event.put("event", "notification.created");
        event.put("id", notificationId);
        event.put("eventCode", EVENT_CODE.name());
        event.put("title", title);
        event.put("body", where);
        event.put("link", link);
        event.put("threadId", anchor.id());
        event.put("messageId", messageId);
        // HashMap rather than Map.of: ticketId is legitimately null on a direct
        // message and a project channel, and Map.of rejects a null value.
        event.put("ticketId", anchor.ticketId());

        realtime.publish(RealtimeDestinations.user(mentioned.id()), event);
    }

    /**
     * The whole body of the notification: enough context to decide whether to
     * open it, and nothing that survives a deletion.
     */
    private static String whereItHappened(ChatRepository.ThreadAnchor anchor) {
        return switch (anchor.kind()) {
            case TICKET -> "in ticket " + nameOr(anchor.ticketCode(), "a ticket thread");
            case PROJECT -> "in the " + nameOr(anchor.projectName(), "project") + " channel";
            case DIRECT -> "in a direct message";
        };
    }

    private static String nameOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
