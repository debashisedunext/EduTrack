package com.edunext.edutrack.api.feature.notifications;

import com.edunext.edutrack.api.realtime.RealtimeDestinations;
import com.edunext.edutrack.api.realtime.RealtimePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * D-044 · the badge event, and when it is allowed to leave.
 */
class NotificationBroadcasterTest {

    private static final long RAVI = 7L;

    private final RealtimePublisher realtime = mock(RealtimePublisher.class);
    private final NotificationBroadcaster broadcaster = new NotificationBroadcaster(realtime);

    @AfterEach
    void clearAnyTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a read goes to that user's queue and nobody else's")
    void aReadIsAddressedToTheUser() {
        broadcaster.read(RAVI, 91L);

        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(realtime).publish(destination.capture(), payload.capture());

        assertThat(destination.getValue()).isEqualTo(RealtimeDestinations.user(RAVI));
        assertThat(payload.getValue())
                .isEqualTo(Map.of("event", "notification.read", "id", 91L));
    }

    @Test
    @DisplayName("mark-all-read is one event, not one per row")
    void markAllReadIsASingleEvent() {
        broadcaster.allRead(RAVI, 12);

        verify(realtime).publish(
                RealtimeDestinations.user(RAVI),
                Map.of("event", "notification.all-read", "count", 12));
    }

    @Test
    @DisplayName("clearing nothing announces nothing")
    void anEmptyMarkAllReadIsSilent() {
        broadcaster.allRead(RAVI, 0);

        // Otherwise every tab refetches to be told the badge is still zero.
        verifyNoInteractions(realtime);
    }

    /**
     * The reason this class exists rather than a call to {@code publish} inside
     * the service.
     */
    @Nested
    class InsideATransaction {

        @Test
        @DisplayName("nothing is published until the transaction commits")
        void theEventWaitsForTheCommit() {
            TransactionSynchronizationManager.initSynchronization();

            broadcaster.read(RAVI, 91L);

            // A rolled-back mark-read that had already told four tabs to
            // decrement leaves every one of them wrong until reload.
            verify(realtime, never()).publish(anyString(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());

            verify(realtime).publish(
                    RealtimeDestinations.user(RAVI),
                    Map.of("event", "notification.read", "id", 91L));
        }

        @Test
        @DisplayName("outside a transaction it still broadcasts rather than doing nothing")
        void withoutATransactionItPublishesInline() {
            broadcaster.read(RAVI, 91L);

            verify(realtime).publish(anyString(), any());
        }
    }
}
