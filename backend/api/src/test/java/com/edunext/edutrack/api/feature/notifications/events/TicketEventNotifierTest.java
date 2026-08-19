package com.edunext.edutrack.api.feature.notifications.events;

import com.edunext.edutrack.api.feature.notifications.events.TicketMailRecipients.Recipient;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-037 · the four §4B.6 events whose triggers now exist.
 *
 * <p>The recipient <em>lists</em> are the blueprint's and are asserted here
 * against its table. What this file is really for is the three rules that sit
 * on top of those lists and are invisible until they go wrong in somebody's
 * inbox: the actor is never told about their own act, an overlap between two
 * lists sends one mail rather than two, and a failing mail never costs the bell
 * entry or the write underneath it.
 */
class TicketEventNotifierTest {

    private static final long TICKET_ID = 201L;
    private static final long ACTOR = 7L;

    private final TicketMailRecipients recipients = mock(TicketMailRecipients.class);
    private final NotificationWriter notifications = mock(NotificationWriter.class);
    private final OutboxEnqueuer mail = mock(OutboxEnqueuer.class);
    private final TicketEventNotifier notifier = new TicketEventNotifier(recipients, notifications, mail);

    private static Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET_ID);
        t.setTicketCode("CRM-26-00347");
        t.setTitle("Checkout fails with 500 on the payment step");
        t.setLevel("CRITICAL");
        return t;
    }

    private static Recipient person(long id) {
        return new Recipient(id, "User " + id, "user" + id + "@edunext.example");
    }

    private List<NewMail> queued() {
        ArgumentCaptor<NewMail> captor = ArgumentCaptor.forClass(NewMail.class);
        verify(mail, org.mockito.Mockito.atLeast(0)).enqueue(captor.capture());
        return captor.getAllValues();
    }

    private List<NewNotification> raised() {
        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).write(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("§4B.6 row 1 — ticket created and assigned")
    class Created {

        @Test
        @DisplayName("mails the assignee, with the level in the subject")
        void mailsAssignee() {
            when(recipients.user(9L)).thenReturn(Optional.of(person(9)));

            notifier.createdAndAssigned(ticket(), ACTOR, 9L);

            assertThat(queued()).singleElement().satisfies(m -> {
                assertThat(m.toUserId()).isEqualTo(9L);
                assertThat(m.eventCode()).isEqualTo(NotificationEvent.TICKET_ASSIGNED.name());
                // §4B.6's subject pattern is "New ticket assigned to you —
                // Critical". The [CRM-26-00347] prefix is OutboxEnqueuer's, so
                // no event can ship without it and none carries it twice.
                assertThat(m.subject()).isEqualTo("New ticket assigned to you — CRITICAL");
                assertThat(m.subject()).doesNotContain("CRM-26-00347");
            });
        }

        @Test
        @DisplayName("says nothing when the ticket was saved unassigned")
        void unassignedSendsNothing() {
            // The row is "created *and assigned*". A draft, or a ticket raised
            // into a queue for somebody to pick up, has no recipient — and
            // inventing one would be inventing a list §4B.6 does not give.
            notifier.createdAndAssigned(ticket(), ACTOR, null);

            verify(mail, never()).enqueue(any());
            verify(notifications, never()).write(any());
        }

        @Test
        @DisplayName("does not mail somebody who assigned it to themselves")
        void selfAssignmentIsSilent() {
            when(recipients.user(ACTOR)).thenReturn(Optional.of(person(ACTOR)));

            notifier.createdAndAssigned(ticket(), ACTOR, ACTOR);

            // Not a weakening of §4B.6's "❌ never": that column is about a
            // *preference* being unable to suppress the mail. "Do not tell
            // somebody what they just did" is not a preference.
            verify(mail, never()).enqueue(any());
        }
    }

    @Nested
    @DisplayName("§4B.6 row 9 — comment added")
    class CommentAdded {

        @Test
        @DisplayName("goes to the assignee and the watchers, never the author")
        void assigneeAndWatchers() {
            when(recipients.assignee(TICKET_ID)).thenReturn(Optional.of(person(9)));
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of(person(11), person(ACTOR)));

            notifier.commentAdded(ticket(), 55L, ACTOR, "Meera Iyer", List.of());

            assertThat(queued()).extracting(NewMail::toUserId).containsExactly(9L, 11L);
            assertThat(queued()).allSatisfy(m ->
                    assertThat(m.subject()).isEqualTo("New comment from Meera Iyer"));
        }

        @Test
        @DisplayName("skips anybody CommentMentionNotifier has already written to")
        void mentionedAreExcluded() {
            // §4B.6 lists mention and comment-added as two rows, so a recipient
            // who is both would get two mails about one sentence — and D-035's
            // one-per-recipient-per-ticket-per-minute limit would drop one
            // *silently*, leaving which one arrives to a race. Deciding it here
            // makes the survivor the mention, which says why they were singled
            // out.
            when(recipients.assignee(TICKET_ID)).thenReturn(Optional.of(person(9)));
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of(person(11)));

            notifier.commentAdded(ticket(), 55L, ACTOR, "Meera Iyer", List.of(9L));

            assertThat(queued()).extracting(NewMail::toUserId).containsExactly(11L);
        }

        @Test
        @DisplayName("sends one mail to somebody who is both the assignee and a watcher")
        void overlappingListsSendOnce() {
            when(recipients.assignee(TICKET_ID)).thenReturn(Optional.of(person(9)));
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of(person(9), person(11)));

            notifier.commentAdded(ticket(), 55L, ACTOR, "Meera Iyer", List.of());

            assertThat(queued()).extracting(NewMail::toUserId).containsExactly(9L, 11L);
        }

        @Test
        @DisplayName("deep-links to the comment, not just the ticket")
        void linksToTheComment() {
            when(recipients.assignee(TICKET_ID)).thenReturn(Optional.of(person(9)));
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of());

            notifier.commentAdded(ticket(), 55L, ACTOR, "Meera Iyer", List.of());

            // By ticket *code* — S-20's URL is /tickets/CRM-26-00347, and the
            // contract's TicketId is the code rather than the surrogate.
            assertThat(raised()).singleElement().satisfies(n ->
                    assertThat(n.linkUrl()).isEqualTo("/tickets/CRM-26-00347?tab=comments#comment-55"));
        }
    }

    @Nested
    @DisplayName("§4B.6 row 12 — ticket closed")
    class Closed {

        @Test
        @DisplayName("goes to the reporter, the watchers and the client contact")
        void reporterWatchersAndContact() {
            when(recipients.reporter(TICKET_ID)).thenReturn(Optional.of(person(3)));
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of(person(11)));
            when(recipients.clientContact(TICKET_ID))
                    .thenReturn(Optional.of(new Recipient(null, "Sara Kapoor", "sara@acme.example")));

            notifier.closed(ticket(), ACTOR);

            assertThat(queued()).extracting(NewMail::toEmail)
                    .containsExactly("user3@edunext.example", "user11@edunext.example", "sara@acme.example");
            // The assignee is deliberately absent — §4B.6's list, and on the
            // ordinary close the assignee *is* the actor.
            assertThat(queued()).extracting(NewMail::subject).containsOnly("Resolved and closed");
        }

        @Test
        @DisplayName("writes no bell entry for a client contact, who has no account to show one in")
        void contactGetsMailOnly() {
            when(recipients.reporter(TICKET_ID)).thenReturn(Optional.empty());
            when(recipients.watchers(TICKET_ID)).thenReturn(List.of());
            when(recipients.clientContact(TICKET_ID))
                    .thenReturn(Optional.of(new Recipient(null, "Sara Kapoor", "sara@acme.example")));

            notifier.closed(ticket(), ACTOR);

            assertThat(queued()).hasSize(1);
            verify(notifications, never()).write(any());
        }
    }

    @Nested
    @DisplayName("§4B.6 row 13 — reopened")
    class Reopened {

        @Test
        @DisplayName("goes to the new assignee and the project's PMs, naming the cycle")
        void newAssigneeAndPms() {
            when(recipients.user(9L)).thenReturn(Optional.of(person(9)));
            when(recipients.projectManagers(TICKET_ID)).thenReturn(List.of(person(2)));

            notifier.reopened(ticket(), ACTOR, 9L, 2);

            assertThat(queued()).extracting(NewMail::toUserId).containsExactly(9L, 2L);
            assertThat(queued()).extracting(NewMail::subject).containsOnly("Reopened — cycle 2");
            // The link carries the cycle: landing on cycle 1 to read about
            // cycle 2 shows the journey that ended.
            assertThat(raised()).allSatisfy(n ->
                    assertThat(n.linkUrl()).isEqualTo("/tickets/CRM-26-00347?cycle=2"));
        }

        @Test
        @DisplayName("still tells the PMs when the reopen left it unassigned")
        void unassignedReopenStillEscalates() {
            when(recipients.projectManagers(TICKET_ID)).thenReturn(List.of(person(2)));

            notifier.reopened(ticket(), ACTOR, null, 2);

            assertThat(queued()).extracting(NewMail::toUserId).containsExactly(2L);
        }
    }

    @Nested
    @DisplayName("a failing channel never costs the other, or the write")
    class Failure {

        @Test
        @DisplayName("a mail that cannot be queued leaves the bell entry standing")
        void mailFailureKeepsTheBell() {
            when(recipients.user(9L)).thenReturn(Optional.of(person(9)));
            doThrow(new IllegalStateException("smtp queue down")).when(mail).enqueue(any());

            notifier.createdAndAssigned(ticket(), ACTOR, 9L);

            // Nothing propagates: this runs inside the caller's transaction and
            // an unreachable queue must not roll back the ticket.
            assertThat(raised()).hasSize(1);
        }

        @Test
        @DisplayName("a bell entry that cannot be written still sends the mail")
        void bellFailureKeepsTheMail() {
            when(recipients.user(9L)).thenReturn(Optional.of(person(9)));
            doThrow(new IllegalStateException("notification table locked")).when(notifications).write(any());

            notifier.createdAndAssigned(ticket(), ACTOR, 9L);

            // §7.7 calls mail the guaranteed channel. One shared try block would
            // have the in-app failure silence it.
            assertThat(queued()).hasSize(1);
        }

        @Test
        @DisplayName("a recipient with no address keeps their bell entry")
        void missingAddressKeepsTheBell() {
            when(recipients.user(9L)).thenReturn(Optional.of(new Recipient(9L, "User 9", null)));

            notifier.createdAndAssigned(ticket(), ACTOR, 9L);

            assertThat(raised()).hasSize(1);
            verify(mail, never()).enqueue(any());
        }
    }

    @Test
    @DisplayName("an inactive or unknown recipient is simply not written to")
    void unknownRecipient() {
        when(recipients.user(anyLong())).thenReturn(Optional.empty());

        notifier.createdAndAssigned(ticket(), ACTOR, 9L);

        verify(mail, never()).enqueue(any());
        verify(notifications, never()).write(any());
    }
}
