package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * C-045 · the bell entry and mail for {@code HANDOFF_RECEIVED} — §11's
 * "assignments" row for a forward handoff, {@code CommentMentionNotifier}'s
 * own precedent for a Stream C feature declaring its own notification write
 * rather than reaching for a shared producer that does not exist yet (D-037
 * still owns wiring all fifteen §4B.6 mails as a set).
 *
 * <p>{@code ASSIGNMENT} category, {@code Mail.ALWAYS} — D-036 makes this mail
 * unsuppressible by preference, exactly like {@code TICKET_ASSIGNED}. D-035's
 * one-per-recipient-per-ticket-per-minute limit still applies; that is
 * {@link OutboxEnqueuer#enqueue}'s job, not this class's.
 *
 * <p>Called from {@link HandoffService} <b>after</b> {@code
 * TransitionService.advance} has succeeded — an unauthorised caller's
 * attempted handoff must never notify the person it did not actually reach.
 */
@Component
class HandoffNotifier {

    /** §11's "assignments" row for a forward handoff into a new owner. */
    static final NotificationEvent EVENT = NotificationEvent.HANDOFF_RECEIVED;

    private static final Logger log = LoggerFactory.getLogger(HandoffNotifier.class);

    private final NotificationWriter notifications;
    private final OutboxEnqueuer mail;
    private final UserRepository users;

    HandoffNotifier(NotificationWriter notifications, OutboxEnqueuer mail, UserRepository users) {
        this.notifications = notifications;
        this.mail = mail;
        this.users = users;
    }

    /**
     * @param fromUserId the caller confirming the handoff, per {@code
     *                    CallerIdentity} — null only for a SYSTEM actor,
     *                    which {@code advance} itself never produces on this
     *                    route (the golden rule refuses an unidentified
     *                    caller before this is ever reached), kept nullable
     *                    only so a future SYSTEM-driven handoff does not
     *                    have to revisit this signature
     */
    void received(Ticket ticket, long toUserId, Long fromUserId, String toStageCode) {
        if (fromUserId != null && fromUserId == toUserId) {
            // A PM or Admin covering a stage solo hands a ticket to
            // themselves via the same dialog — no "you handed this to you"
            // noise for that.
            return;
        }

        String who = fromUserId == null ? "Someone" : users.findById(fromUserId)
                .map(HandoffNotifier::displayNameOf)
                .orElse("Someone");
        String title = who + " handed off " + ticket.getTicketCode() + " to you";
        String body = ticket.getTitle() + " · now in " + toStageCode;
        String link = "/tickets/" + ticket.getId() + "?tab=ribbon";

        try {
            notifications.write(new NewNotification(toUserId, ticket.getId(), EVENT, title, body, link));
        } catch (RuntimeException e) {
            // The handoff itself already committed by the time this runs —
            // see HandoffService's call site — so a bell failure must not be
            // allowed to look like the handoff failed. Logged at error for
            // the same reason CommentMentionNotifier gives: a missed
            // mandatory alert has to be provable, not deniable (§17).
            log.error("transitions: could not raise handoff notification for user {} on ticket {}",
                    toUserId, ticket.getId(), e);
        }

        User recipient = users.findById(toUserId).orElse(null);
        String recipientEmail = recipient == null ? null : recipient.getEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            // users.email is NOT NULL, so this is unreachable through the
            // schema — checked anyway on CommentMentionNotifier's own
            // precedent, since NewMail throws on a blank address and that
            // throw must not land on the handoff's write path.
            log.warn("transitions: handoff receiver {} on ticket {} has no email address; the bell entry stands",
                    toUserId, ticket.getId());
            return;
        }
        try {
            mail.enqueue(NewMail.forTicket(
                    ticket.getId(), EVENT.name(), toUserId, recipientEmail,
                    "Handed off to you by " + who));
        } catch (RuntimeException e) {
            log.error("transitions: could not enqueue handoff mail for user {} on ticket {}",
                    toUserId, ticket.getId(), e);
        }
    }

    /** {@code full_name}, falling back to the username — {@code TransitionUserRefs}' rule, verbatim. */
    private static String displayNameOf(User user) {
        String fullName = blankToNull(user.getFullName());
        if (fullName != null) {
            return fullName;
        }
        return Objects.requireNonNullElse(blankToNull(user.getUsername()), "Unknown");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
