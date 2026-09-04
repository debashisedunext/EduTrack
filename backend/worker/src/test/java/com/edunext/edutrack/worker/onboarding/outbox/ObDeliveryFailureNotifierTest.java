package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.notifications.NewNotification;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationWriter;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObDeliveryFailureNotifierTest {

    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final ObDeliveryFailureNotifier notifier = new ObDeliveryFailureNotifier(writer);

    @Test
    void aStaffRecipientIsToldDirectlyWithALinkToTheClient() {
        notifier.notifyDeliveryFailed(message(new ObRecipient.Staff(7), 3L, "a@x.test"), 4, "SMTP 421");

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer).write(written.capture());
        NewNotification n = written.getValue();
        assertThat(n.userId()).isEqualTo(7);
        assertThat(n.ticketId()).isNull();
        assertThat(n.event()).isEqualTo(NotificationEvent.MAIL_DELIVERY_FAILED);
        assertThat(n.title()).isEqualTo(ObDeliveryFailureNotifier.TITLE);
        assertThat(n.linkUrl()).isEqualTo("/onboarding/clients/3");
        assertThat(n.body()).isEqualTo(
                "The SIGNOFF_REQUESTED email message to a@x.test could not be delivered after 3 retries. "
                        + "Reason: SMTP 421");
        verify(writer, never()).activeUsersInRole(any());
    }

    @Test
    void aClientContactHasNoBellSoTheAdminsAreTold() {
        when(writer.activeUsersInRole("ADMIN")).thenReturn(List.of(1L, 2L));

        notifier.notifyDeliveryFailed(message(new ObRecipient.Client(9), null, "spoc@client.test"), 1,
                "No such mailbox");

        ArgumentCaptor<NewNotification> written = ArgumentCaptor.forClass(NewNotification.class);
        verify(writer, times(2)).write(written.capture());
        assertThat(written.getAllValues()).extracting(NewNotification::userId).containsExactly(1L, 2L);
        assertThat(written.getAllValues().get(0).linkUrl()).isNull();
        // A first-attempt failure is not "after 0 retries".
        assertThat(written.getAllValues().get(0).body())
                .isEqualTo("The SIGNOFF_REQUESTED email message to spoc@client.test could not be delivered. "
                        + "Reason: No such mailbox");
    }

    @Test
    void nobodyToTellIsLoggedNotThrown() {
        when(writer.activeUsersInRole("ADMIN")).thenReturn(List.of());

        notifier.notifyDeliveryFailed(message(new ObRecipient.Client(9), 3L, "spoc@client.test"), 4, "x");

        verify(writer, never()).write(any());
    }

    @Test
    void aWriterFailureNeverPropagates() {
        doThrow(new IllegalStateException("db down")).when(writer).write(any());

        notifier.notifyDeliveryFailed(message(new ObRecipient.Staff(7), 3L, "a@x.test"), 4, "x");
        // reached: no exception
    }

    @Test
    void aRecipientWithNoAddressIsNamedInstead() {
        ObOutboxMessage m = new ObOutboxMessage(41, "GATE_OPENED", ObChannel.IN_APP, new ObRecipient.Staff(7),
                new ObOutboxMessage.RecipientDetails("Asha Rao", null, null, false, true),
                null, null, null, Map.of(), 1);

        assertThat(ObDeliveryFailureNotifier.body(m, 1, null))
                .isEqualTo("The GATE_OPENED in_app message to Asha Rao could not be delivered.");
    }

    private static ObOutboxMessage message(ObRecipient recipient, Long clientId, String email) {
        return new ObOutboxMessage(41, "SIGNOFF_REQUESTED", ObChannel.EMAIL, recipient,
                new ObOutboxMessage.RecipientDetails("Asha", email, null, false, true),
                clientId, null, null, Map.of(), 0);
    }
}
