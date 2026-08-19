package com.edunext.edutrack.api.feature.notifications.events;

import com.edunext.edutrack.api.feature.notifications.events.TicketMailRecipients.Recipient;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D-037 · the shared producer for §4B.6's ticket events.
 *
 * <p>Four of the fifteen rows are wired here — created-and-assigned, comment
 * added, closed and reopened. They are the four whose trigger now exists:
 * {@code TicketWriteService.create} (C-067), {@code CommentService.post}
 * (C-029), {@code CloseService} (C-040) and {@code ReopenService} (C-038) are
 * all built and none of them told anybody. The other eleven are accounted for
 * at the bottom of this comment.
 *
 * <h2>One producer, not one per feature package</h2>
 *
 * <p>{@code CommentMentionNotifier} asked for this in as many words — "D-037
 * still owns wiring §4B.6's fifteen events as a set … if D-037 also enqueues a
 * {@code MENTIONED} mail from a shared producer, this call is the one to
 * delete, not to keep alongside it". This class is that producer, and it
 * deliberately does <strong>not</strong> take over {@code MENTIONED}: that one
 * is working, has its own tests, and swallowing it into this change would put a
 * live feature at risk to tidy a seam. It is the fifth event to move here, on
 * its own, when the rest of §4B.6 lands.
 *
 * <p>It lives in {@code api/feature/notifications/}, which is Stream D's per
 * TEAM-PLAN §6. What is <em>not</em> Stream D's is the four call sites, and
 * three of them are in Stream C's directories — flagged in the pull request and
 * requested from Divyansh rather than done quietly.
 *
 * <h2>The actor never gets the mail</h2>
 *
 * <p>Nobody is told about a thing they just did. It is the rule
 * {@code CommentMentionNotifier} already applies to a self-mention — "mentioning
 * yourself is a way of writing a note to the thread, not a request to be told
 * about it" — and it is the difference between a notification system people
 * read and one they filter.
 *
 * <p>This does not weaken §4B.6's "❌ never" column. That column is about a
 * <em>preference</em> being unable to suppress the mail, which is
 * {@link NotificationEvent#isMandatoryMail()} and is enforced inside
 * {@link OutboxEnqueuer}. "Do not mail the person who pressed the button" is
 * not a preference; it is who the event is about.
 *
 * <h2>Failure never costs the write</h2>
 *
 * <p>Every raise is wrapped, and the bell entry is attempted before the mail so
 * one cannot cost the other — the structure {@code CommentMentionNotifier}
 * argues for and the reason is the same here, one step larger: closing a ticket
 * must not roll back because an SMTP host is unreachable. A notification that
 * silently never fired is still the "missed alert" §17 wants provable rather
 * than deniable, so every failure is logged at error with the recipient and the
 * ticket named.
 *
 * <h2>The remaining eleven, so nobody has to re-derive this</h2>
 *
 * <ul>
 *   <li><b>Live already, elsewhere:</b> level raised to Critical
 *       ({@code PriorityChangeService}), SLA breach and stage SLA breach
 *       (D-023's scanners), @mention ({@code CommentMentionNotifier}), status
 *       requested ({@code StatusRequestNotifier}).</li>
 *   <li><b>Still without a trigger:</b> handoff, sent back for rework and
 *       deployment done all fire from C-045's transition, which is in review;
 *       reassigned within a stage is C-049, unstarted.</li>
 *   <li><b>Not this task:</b> the daily digest and the weekly manager summary
 *       are D-038, and they are done.</li>
 * </ul>
 */
@Component
public class TicketEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(TicketEventNotifier.class);

    private final TicketMailRecipients recipients;
    private final NotificationWriter notifications;
    private final OutboxEnqueuer mail;

    TicketEventNotifier(TicketMailRecipients recipients,
                        NotificationWriter notifications,
                        OutboxEnqueuer mail) {
        this.recipients = recipients;
        this.notifications = notifications;
        this.mail = mail;
    }

    /**
     * §4B.6 row 1 — "Ticket created and assigned → Assignee", never optional.
     *
     * <p><strong>Only when it is actually assigned.</strong> A ticket saved as a
     * draft, or raised into a queue for somebody to pick up, has no assignee and
     * this sends nothing — the row's own wording is "created <em>and
     * assigned</em>". {@code NEW_UNASSIGNED_TICKET} exists in the enum for the
     * other case and belongs to whoever builds the intake queue; raising it here
     * would be inventing a recipient list §4B.6 does not give.
     */
    public void createdAndAssigned(Ticket ticket, long actorId, Long assigneeId) {
        if (assigneeId == null) {
            return;
        }
        // Resolved from the id the caller passed rather than by reading the
        // ticket back. `create` is @Transactional and this runs inside it, so a
        // re-read would be querying a row its own transaction has not committed
        // — it happens to work, because Hibernate's IDENTITY generation issues
        // the INSERT eagerly on the same connection, and "happens to work" is
        // not a thing to depend on inside somebody else's transaction.
        Optional<Recipient> assignee = recipients.user(assigneeId);
        if (assignee.isEmpty()) {
            return;
        }
        String level = ticket.getLevel() == null ? "" : " — " + ticket.getLevel();
        fanOut(ticket, actorId, NotificationEvent.TICKET_ASSIGNED,
                List.of(assignee.get()),
                "New ticket assigned to you" + level,
                ticket.getTicketCode() + " · " + ticket.getTitle(),
                link(ticket, null));
    }

    /**
     * §4B.6 row 9 — "Comment added → Assignee, watchers", digestible.
     *
     * @param mentionedUserIds the people {@code CommentMentionNotifier} has
     *        already written to for this same comment. **Excluded here.**
     *        §4B.6 lists mention and comment-added as separate rows, but a
     *        recipient who is both gets two mails about one sentence — and
     *        D-035's one-mail-per-recipient-per-ticket-per-minute limit would
     *        drop the second *silently*, so the reader would get whichever won
     *        the race. Deciding it here makes the outcome the mention mail,
     *        which is the more specific of the two and names why they were
     *        picked out.
     */
    public void commentAdded(Ticket ticket,
                             long commentId,
                             long authorId,
                             String authorName,
                             List<Long> mentionedUserIds) {

        List<Recipient> people = new ArrayList<>();
        recipients.assignee(ticket.getId()).ifPresent(people::add);
        people.addAll(recipients.watchers(ticket.getId()));

        String who = displayName(authorName);
        fanOut(ticket, authorId, NotificationEvent.COMMENT_ADDED,
                exclude(people, mentionedUserIds),
                "New comment from " + who,
                ticket.getTicketCode() + " · " + ticket.getTitle(),
                "/tickets/" + ticket.getTicketCode() + "?tab=comments#comment-" + commentId);
    }

    /**
     * §4B.6 row 12 — "Ticket closed → Reporter, client contact, watchers",
     * optional.
     *
     * <p>The assignee is deliberately absent, and it is the blueprint's list
     * rather than an oversight: on the ordinary close the assignee <em>is</em>
     * the actor, and on a close by somebody else they still find out from the
     * ticket they are working on. The people who need telling are the ones who
     * have stopped watching it — the person who raised it, and the client.
     */
    public void closed(Ticket ticket, long actorId) {
        List<Recipient> people = new ArrayList<>();
        recipients.reporter(ticket.getId()).ifPresent(people::add);
        people.addAll(recipients.watchers(ticket.getId()));
        recipients.clientContact(ticket.getId()).ifPresent(people::add);

        fanOut(ticket, actorId, NotificationEvent.TICKET_CLOSED, people,
                "Resolved and closed",
                ticket.getTicketCode() + " · " + ticket.getTitle(),
                link(ticket, null));
    }

    /**
     * §4B.6 row 13 — "Reopened → New assignee, PM", never optional.
     *
     * @param newAssigneeId the assignee the reopen set, which is <em>not</em>
     *        necessarily the ticket's previous one — S-22 defaults it to the
     *        previous assignee and lets the reopener change it. Passed in rather
     *        than read back, because at the moment this is called the caller
     *        knows it and a re-read would race its own transaction.
     */
    public void reopened(Ticket ticket, long actorId, Long newAssigneeId, int cycleNo) {
        List<Recipient> people = new ArrayList<>();
        if (newAssigneeId != null) {
            recipients.user(newAssigneeId).ifPresent(people::add);
        }
        people.addAll(recipients.projectManagers(ticket.getId()));

        fanOut(ticket, actorId, NotificationEvent.TICKET_REOPENED, people,
                "Reopened — cycle " + cycleNo,
                ticket.getTicketCode() + " · " + ticket.getTitle(),
                link(ticket, cycleNo));
    }

    // ── the shared half ─────────────────────────────────────────────────────

    /**
     * Bell entry then mail, per recipient, each independently wrapped.
     *
     * <p>Deduplicated by identity before anything is sent. The assignee is
     * frequently also a watcher, and §4B.6's lists overlap by design — two mails
     * for one event is the failure people notice fastest. A client contact has
     * no user id and is keyed on their address instead, so a contact who shares
     * an address with a user is still only written to once.
     */
    private void fanOut(Ticket ticket,
                        long actorId,
                        NotificationEvent event,
                        List<Recipient> people,
                        String title,
                        String body,
                        String link) {

        for (Recipient person : dedupe(people, actorId)) {
            if (person.userId() != null) {
                try {
                    notifications.write(new NewNotification(
                            person.userId(), ticket.getId(), event, title, body, link));
                } catch (RuntimeException e) {
                    log.error("mail-events: could not raise {} notification for user {} on ticket {}",
                            event, person.userId(), ticket.getTicketCode(), e);
                }
            }

            if (person.email() == null || person.email().isBlank()) {
                log.warn("mail-events: {} on {} has no address for recipient {}; the bell entry stands",
                        event, ticket.getTicketCode(), person.userId());
                continue;
            }
            try {
                // Subject without the ticket code — D-031's [CRM-26-00347]
                // prefix is OutboxEnqueuer's to add, so no §4B.6 event can ship
                // without it.
                mail.enqueue(new NewMail(
                        ticket.getId(), event.name(), null, person.userId(), person.email(), title));
            } catch (RuntimeException e) {
                log.error("mail-events: could not enqueue {} mail to {} on ticket {}",
                        event, person.email(), ticket.getTicketCode(), e);
            }
        }
    }

    /** Distinct recipients, in the order §4B.6 lists them, minus whoever acted. */
    private static List<Recipient> dedupe(List<Recipient> people, long actorId) {
        Map<String, Recipient> byIdentity = new LinkedHashMap<>();
        for (Recipient person : people) {
            if (person.userId() != null && person.userId() == actorId) {
                continue;
            }
            String key = person.userId() != null
                    ? "u:" + person.userId()
                    : "e:" + (person.email() == null ? "" : person.email().toLowerCase());
            byIdentity.putIfAbsent(key, person);
        }
        return List.copyOf(byIdentity.values());
    }

    private static List<Recipient> exclude(List<Recipient> people, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return people;
        }
        return people.stream()
                .filter(p -> p.userId() == null || !userIds.contains(p.userId()))
                .toList();
    }

    /**
     * The deep link, by ticket <em>code</em>.
     *
     * <p>S-20's URL is {@code /tickets/CRM-26-00347}: the contract's
     * {@code TicketId} is the code, and the router matches on it.
     *
     * <p>⚠ <b>{@code CommentMentionNotifier} builds the same link from
     * {@code getId()}</b>, so its bell entries point at {@code /tickets/201}.
     * That is not simply a typo to correct here, and it is left alone rather
     * than changed in passing: C-020 recorded the matching half of the
     * confusion — {@code TicketDetailController.full} and
     * {@code ReopenController.reopen} both declare {@code @PathVariable long
     * ticketId} against a contract whose {@code TicketId} is the string — so
     * the two halves are wrong in opposite directions and one of them will 400
     * whichever way a link is written today. Fixing the link without fixing the
     * route just moves which one breaks. Raised for Stream C rather than
     * half-corrected; this class writes what the contract says.
     */
    private static String link(Ticket ticket, Integer cycleNo) {
        String base = "/tickets/" + ticket.getTicketCode();
        return cycleNo == null ? base : base + "?cycle=" + cycleNo;
    }

    private static String displayName(String name) {
        return name == null || name.isBlank() ? "Someone" : name;
    }
}
