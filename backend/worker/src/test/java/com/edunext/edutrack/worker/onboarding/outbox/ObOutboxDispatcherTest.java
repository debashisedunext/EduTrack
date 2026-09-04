package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-110 · what the dispatcher does with each {@link DeliveryOutcome}, without
 * a database. Claiming, leases and {@code SKIP LOCKED} are the database's
 * behaviour and are proven in {@code ObOutboxDispatcherIT}.
 */
class ObOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObOutboxProperties PROPS = new ObOutboxProperties(
            true, Duration.ofSeconds(5), 10, Duration.ofMinutes(2),
            3, Duration.ofMinutes(1), Duration.ofHours(1));

    private ObOutboxRepository repository;
    private ObDeliveryFailureNotifier notifier;
    private ScriptedAdapter adapter;
    private ObOutboxDispatcher dispatcher;

    @BeforeEach
    void wire() {
        repository = mock(ObOutboxRepository.class);
        notifier = mock(ObDeliveryFailureNotifier.class);
        adapter = new ScriptedAdapter(ObChannel.EMAIL);
        dispatcher = new ObOutboxDispatcher(repository,
                new ChannelAdapterRegistry(List.of(adapter)), PROPS, notifier, CLOCK);
    }

    @Test
    void aSuccessfulDeliveryIsStampedSentWithTheProviderId() {
        adapter.outcome = m -> new DeliveryOutcome.Sent("provider-1");

        dispatcher.process(message(41, 0, true));

        verify(repository).markSent(41, "provider-1", 1, NOW);
        verifyNoInteractions(notifier);
    }

    @Test
    void aTransientFailureIsRescheduledWithBackoffFromTheAttemptCount() {
        adapter.outcome = m -> new DeliveryOutcome.TransientFailure("SMTP 421");

        dispatcher.process(message(41, 1, true));

        // Second attempt failed → base × 2.
        verify(repository).markForRetry(41, 2, NOW.plus(Duration.ofMinutes(2)), "SMTP 421");
        verifyNoInteractions(notifier);
    }

    @Test
    void theLastPermittedAttemptFailsTheRowAndTellsSomebody() {
        adapter.outcome = m -> new DeliveryOutcome.TransientFailure("SMTP 421");

        ObOutboxMessage m = message(41, 2, true);
        dispatcher.process(m);

        verify(repository).markFailed(41, 3, "SMTP 421", NOW);
        verify(repository, never()).markForRetry(anyLong(), anyInt(), any(), anyString());
        verify(notifier).notifyDeliveryFailed(m, 3, "SMTP 421");
    }

    @Test
    void aPermanentFailureIsNotRetriedWhateverTheAttemptCount() {
        adapter.outcome = m -> new DeliveryOutcome.PermanentFailure("No such mailbox");

        ObOutboxMessage m = message(41, 0, true);
        dispatcher.process(m);

        verify(repository).markFailed(41, 1, "No such mailbox", NOW);
        verify(notifier).notifyDeliveryFailed(m, 1, "No such mailbox");
    }

    @Test
    void anAdapterThatThrowsIsTreatedAsTransient() {
        adapter.outcome = m -> {
            throw new IllegalStateException("boom");
        };

        dispatcher.process(message(41, 0, true));

        verify(repository).markForRetry(eq(41L), eq(1), eq(NOW.plus(Duration.ofMinutes(1))),
                eq("IllegalStateException: boom"));
    }

    @Test
    void anAdapterThatReturnsNothingIsTreatedAsTransient() {
        adapter.outcome = m -> null;

        dispatcher.process(message(41, 0, true));

        verify(repository).markForRetry(eq(41L), eq(1), any(), eq("ScriptedAdapter returned no outcome"));
    }

    @Test
    void anInactiveRecipientIsFailedWithoutAskingTheAdapter() {
        adapter.outcome = m -> new DeliveryOutcome.Sent(null);

        ObOutboxMessage m = message(41, 0, false);
        dispatcher.process(m);

        assertThat(adapter.calls.get()).isZero();
        verify(repository).markFailed(eq(41L), eq(1), eq("Recipient staff a@x.test is inactive"), eq(NOW));
        verify(notifier).notifyDeliveryFailed(eq(m), eq(1), anyString());
    }

    @Test
    void aChannelWithNoAdapterGoesBackToTheQueueRatherThanFailing() {
        // Not reachable through pollOnce(), which never claims such rows; a
        // direct call must still not lose the message.
        ObOutboxMessage m = new ObOutboxMessage(41, "SIGNOFF_REQUESTED", ObChannel.WHATSAPP,
                new ObRecipient.Client(9), details(true), 1L, 2L, 3L, Map.of(), 0);

        dispatcher.process(m);

        verify(repository).markForRetry(eq(41L), eq(1), any(), eq("No adapter registered for WHATSAPP"));
        verifyNoInteractions(notifier);
    }

    @Test
    void pollOnceClaimsOnlyTheChannelsThatHaveAnAdapter() {
        when(repository.reclaimExpiredLeases(NOW)).thenReturn(0);
        when(repository.claimBatch(any(), anyInt(), any(), any())).thenReturn(List.of());

        assertThat(dispatcher.pollOnce()).isZero();

        verify(repository).claimBatch(eq(java.util.EnumSet.of(ObChannel.EMAIL)), eq(10),
                eq(Duration.ofMinutes(2)), eq(NOW));
    }

    @Test
    void pollOnceWithNoAdaptersAtAllClaimsNothing() {
        ObOutboxDispatcher none = new ObOutboxDispatcher(repository,
                new ChannelAdapterRegistry(List.of()), PROPS, notifier, CLOCK);
        when(repository.reclaimExpiredLeases(NOW)).thenReturn(0);

        assertThat(none.pollOnce()).isZero();

        verify(repository, never()).claimBatch(any(), anyInt(), any(), any());
    }

    @Test
    void pollOnceDeliversEveryClaimedRowAndReportsTheCount() {
        adapter.outcome = m -> new DeliveryOutcome.Sent("p" + m.id());
        when(repository.reclaimExpiredLeases(NOW)).thenReturn(1);
        when(repository.claimBatch(any(), anyInt(), any(), any()))
                .thenReturn(List.of(message(1, 0, true), message(2, 0, true)));

        assertThat(dispatcher.pollOnce()).isEqualTo(2);

        verify(repository).markSent(1, "p1", 1, NOW);
        verify(repository).markSent(2, "p2", 1, NOW);
        assertThat(adapter.calls.get()).isEqualTo(2);
    }

    private static ObOutboxMessage message(long id, int attempts, boolean active) {
        return new ObOutboxMessage(id, "SIGNOFF_REQUESTED", ObChannel.EMAIL,
                new ObRecipient.Staff(7), details(active), 1L, 2L, 3L, Map.of("k", "v"), attempts);
    }

    private static ObOutboxMessage.RecipientDetails details(boolean active) {
        return new ObOutboxMessage.RecipientDetails("Asha", "a@x.test", null, false, active);
    }

    private static final class ScriptedAdapter implements ObChannelAdapter {
        private final ObChannel channel;
        private final AtomicInteger calls = new AtomicInteger();
        private Function<ObOutboxMessage, DeliveryOutcome> outcome = m -> new DeliveryOutcome.Sent(null);

        private ScriptedAdapter(ObChannel channel) {
            this.channel = channel;
        }

        @Override
        public ObChannel channel() {
            return channel;
        }

        @Override
        public DeliveryOutcome deliver(ObOutboxMessage message) {
            calls.incrementAndGet();
            return outcome.apply(message);
        }
    }
}
