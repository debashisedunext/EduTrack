package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * C-030 · the bell entry and the mail when somebody is mentioned in a comment.
 *
 * <p>Blueprint §11 lists "@mention in chat/comment" as popup, bell <em>and</em>
 * email to the mentioned user, and C-030's own task text is "firing notification
 * + email". This sends both.
 *
 * <h2>Why this sends mail where D-052 deliberately did not</h2>
 *
 * <p>D-052 built the same fan-out for chat and stopped at the two in-app
 * channels, with a reason worth quoting: <em>"§4B.6 marks mention mail optional,
 * and the per-user preference matrix that makes it optional is D-042. Sending an
 * unsuppressible mail for an event the spec says users may switch off is worse
 * than sending it late."</em>
 *
 * <p><strong>D-042 has since landed, and the suppression is inside
 * {@link OutboxEnqueuer#enqueue} rather than in each caller.</strong>
 * {@code MENTIONED} is {@link NotificationEvent.Category#MENTION}, so
 * {@link NotificationEvent#isMandatoryMail()} is false and D-036 does not defeat
 * the preference — a user who has turned mention mail off gets none from here,
 * with nothing for this class to remember. D-035's one-mail-per-recipient-per-
 * ticket-per-minute limit applies in the same call. So the condition D-052 was
 * waiting for is met; this is not a disagreement with that task.
 *
 * <p>D-037 still owns wiring §4B.6's fifteen events as a set, and this is one
 * of them arriving early because the feature that needs it is being built now.
 * <b>Flagged for Stream D</b> rather than done quietly: if D-037 also enqueues
 * a {@code MENTIONED} mail from a shared producer, this call is the one to
 * delete, not to keep alongside it.
 *
 * <h2>The notification quotes nothing that was said</h2>
 *
 * <p>D-052's reasoning transfers exactly, and C-033 is what makes it bite:
 * §4B.5 gives an author five minutes to edit and leaves a tombstone on delete,
 * so a bell entry carrying the body would be the one copy a correction cannot
 * reach — sitting in the recipient's list indefinitely, quoting a sentence its
 * author has since withdrawn. §4B.6's own example subject,
 * {@code [CRM-26-00347] You were mentioned}, carries no preview either. Who and
 * where is enough to decide whether to look.
 *
 * <p>The ticket <em>title</em> is not the comment, and is carried: a recipient
 * with forty notifications needs to tell them apart, and the title is already
 * on any screen the link leads to.
 *
 * <h2>Failure never costs the comment</h2>
 *
 * <p>Every raise is wrapped. The comment is written and is the record; letting
 * an unreachable mail queue roll back somebody's paragraph would be the wrong
 * trade by a wide margin. A mention that silently never notified is still
 * exactly the "missed alert" §17 wants provable rather than deniable, so it is
 * logged at error with the user and the comment named.
 */
@Component
class CommentMentionNotifier {

    /** §11's "@mention in chat/comment". Also the {@code tab=mentions} filter in S-26. */
    static final NotificationEvent EVENT = NotificationEvent.MENTIONED;

    private static final Logger log = LoggerFactory.getLogger(CommentMentionNotifier.class);

    private final NotificationWriter notifications;
    private final OutboxEnqueuer mail;

    CommentMentionNotifier(NotificationWriter notifications, OutboxEnqueuer mail) {
        this.notifications = notifications;
        this.mail = mail;
    }

    /**
     * @param mentioned already narrowed to active project members by
     *                  {@link CommentMentions#resolveProjectMembers}
     * @param authorId  excluded — mentioning yourself is a way of writing a note
     *                  to the thread, not a request to be told about it
     */
    void mentioned(Ticket ticket,
                   long commentId,
                   long authorId,
                   String authorName,
                   List<CommentMentions.MentionedUser> mentioned) {

        for (CommentMentions.MentionedUser person : mentioned) {
            if (person.id() == authorId) {
                continue;
            }
            raise(ticket, commentId, authorName, person);
        }
    }

    private void raise(Ticket ticket,
                       long commentId,
                       String authorName,
                       CommentMentions.MentionedUser person) {

        String who = authorName == null || authorName.isBlank() ? "Someone" : authorName;
        String title = who + " mentioned you in a comment";
        String body = ticket.getTicketCode() + " · " + ticket.getTitle();
        // The comment's own anchor, not just the ticket. A thread forty comments
        // long makes "go and find it" the actual cost of the notification, and
        // the id is the only thing that survives the thread being re-paged.
        String link = "/tickets/" + ticket.getId() + "?tab=comments#comment-" + commentId;

        try {
            notifications.write(new NewNotification(
                    person.id(), ticket.getId(), EVENT, title, body, link));
        } catch (RuntimeException e) {
            log.error("comments: could not raise mention notification for user {} on comment {}",
                    person.id(), commentId, e);
        }

        // Enqueued separately from the bell entry, and after it, because they
        // fail independently and the bell is the one a logged-in user sees
        // first. A mail failure must not cost the notification that already
        // succeeded, which is what one shared try block would do.
        if (person.email() == null || person.email().isBlank()) {
            // users.email is NOT NULL, so this is unreachable through the schema.
            // Checked anyway because NewMail throws on a blank address, and the
            // throw would land on the write path of somebody's comment.
            log.warn("comments: user {} was mentioned on comment {} but has no email address; "
                    + "the bell entry stands", person.id(), commentId);
            return;
        }
        try {
            // Subject deliberately without the ticket code — D-031's
            // [CRM-26-00347] prefix is OutboxEnqueuer's to add, so that no §4B.6
            // event can ship without it.
            mail.enqueue(NewMail.forTicket(
                    ticket.getId(), EVENT.name(), person.id(), person.email(),
                    "You were mentioned by " + who));
        } catch (RuntimeException e) {
            log.error("comments: could not enqueue mention mail for user {} on comment {}",
                    person.id(), commentId, e);
        }
    }
}
