package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-045 · {@link HandoffNotifier} — the bell entry and mandatory mail a
 * handoff raises for its receiving owner, proved against mocks on
 * {@code HandoffServiceTest}'s own precedent for the sibling write.
 */
class HandoffNotifierTest {

    private static final long TICKET_ID = 347L;
    private static final long FROM_USER = 12L;
    private static final long TO_USER = 99L;

    private final NotificationWriter notifications = mock(NotificationWriter.class);
    private final OutboxEnqueuer mail = mock(OutboxEnqueuer.class);
    private final UserRepository users = mock(UserRepository.class);

    private final HandoffNotifier notifier = new HandoffNotifier(notifications, mail, users);

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketCode("CRM-26-00347");
        ticket.setTitle("Payment webhook retries exhaust before the gateway settles");

        when(users.findById(FROM_USER)).thenReturn(Optional.of(user(FROM_USER, "Priya Nair", "priya", "priya@example.com")));
        when(users.findById(TO_USER)).thenReturn(Optional.of(user(TO_USER, "Rahul Dev", "rahul", "rahul@example.com")));
    }

    @Test
    @DisplayName("writes a bell entry naming who handed off and where the ticket landed")
    void writesBellEntry() {
        notifier.received(ticket, TO_USER, FROM_USER, "QA");

        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications).write(captor.capture());
        NewNotification written = captor.getValue();
        assertThat(written.userId()).isEqualTo(TO_USER);
        assertThat(written.ticketId()).isEqualTo(TICKET_ID);
        assertThat(written.event()).isEqualTo(HandoffNotifier.EVENT);
        assertThat(written.title()).isEqualTo("Priya Nair handed off CRM-26-00347 to you");
        assertThat(written.body()).contains("now in QA");
        assertThat(written.linkUrl()).isEqualTo("/tickets/347?tab=ribbon");
    }

    @Test
    @DisplayName("enqueues mandatory mail to the receiving owner's address")
    void enqueuesMail() {
        notifier.received(ticket, TO_USER, FROM_USER, "QA");

        ArgumentCaptor<NewMail> captor = ArgumentCaptor.forClass(NewMail.class);
        verify(mail).enqueue(captor.capture());
        NewMail queued = captor.getValue();
        assertThat(queued.ticketId()).isEqualTo(TICKET_ID);
        assertThat(queued.eventCode()).isEqualTo(HandoffNotifier.EVENT.name());
        assertThat(queued.toUserId()).isEqualTo(TO_USER);
        assertThat(queued.toEmail()).isEqualTo("rahul@example.com");
    }

    @Test
    @DisplayName("a caller handing off to themselves raises nothing")
    void selfHandoffIsSilent() {
        notifier.received(ticket, FROM_USER, FROM_USER, "QA");

        verify(notifications, never()).write(any());
        verify(mail, never()).enqueue(any());
    }

    @Test
    @DisplayName("a null caller (SYSTEM) renders as \"Someone\" rather than failing")
    void nullCallerRendersAsSomeone() {
        notifier.received(ticket, TO_USER, null, "QA");

        ArgumentCaptor<NewNotification> captor = ArgumentCaptor.forClass(NewNotification.class);
        verify(notifications).write(captor.capture());
        assertThat(captor.getValue().title()).startsWith("Someone handed off");
    }

    @Test
    @DisplayName("a receiver with no resolvable email still gets the bell entry, no mail")
    void noEmailSkipsMailOnly() {
        when(users.findById(TO_USER)).thenReturn(Optional.empty());

        notifier.received(ticket, TO_USER, FROM_USER, "QA");

        verify(notifications).write(any());
        verify(mail, never()).enqueue(any());
    }

    @Test
    @DisplayName("a bell-write failure does not stop the mail from being queued")
    void bellFailureDoesNotBlockMail() {
        when(notifications.write(any())).thenThrow(new RuntimeException("db is briefly unreachable"));

        notifier.received(ticket, TO_USER, FROM_USER, "QA");

        verify(mail).enqueue(any());
    }

    @Test
    @DisplayName("a mail failure never propagates out of the handoff")
    void mailFailureDoesNotPropagate() {
        when(mail.enqueue(any())).thenThrow(new RuntimeException("smtp relay is briefly unreachable"));

        notifier.received(ticket, TO_USER, FROM_USER, "QA");

        verify(notifications).write(any());
    }

    private static User user(long id, String fullName, String username, String email) {
        User u = new User();
        u.setId(id);
        u.setFullName(fullName);
        u.setUsername(username);
        u.setEmail(email);
        return u;
    }
}
