package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * B-110 · the onboarding outbox dispatcher. Reclaim, claim, deliver, stamp.
 *
 * <p>Drains {@code ob_notification_outbox} (A-107) through whichever
 * {@link ObChannelAdapter}s this deployment has. The dispatcher decides
 * <em>whether</em> and <em>when</em> a row is tried — leases, backoff, the
 * retry ceiling, the failure notice — and an adapter decides <em>how</em> it
 * leaves. Nothing here names SMTP, a WhatsApp provider or the bell, which is
 * what lets D-101 add a channel by adding a class.
 *
 * <p><strong>Rows for a channel with no adapter are never claimed.</strong>
 * Phase 2 ships email only (PHASE-2-BUILD-PLAN.md §6.1); an event queued for
 * WHATSAPP waits in PENDING until an adapter exists, and is then delivered.
 * Failing such rows would have made the deferral a one-way door — every
 * consent-bearing SPOC message queued before D-101 lands would be gone.
 * {@link #reportUnroutable()} says so in the log rather than letting the
 * queue fill silently.
 *
 * <p><strong>No ShedLock here, deliberately</strong>, for D-010's reason:
 * {@code SKIP LOCKED} already makes concurrent claims disjoint, and a
 * scheduler lock would pin draining to one instance — the opposite of what a
 * burst of sign-off requests wants.
 */
@Component
@ConditionalOnProperty(name = "edutrack.ob-outbox.enabled", havingValue = "true", matchIfMissing = true)
public class ObOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ObOutboxDispatcher.class);

    private final ObOutboxRepository repository;
    private final ChannelAdapterRegistry adapters;
    private final ObOutboxProperties properties;
    private final ObDeliveryFailureNotifier failureNotifier;
    private final Clock clock;

    public ObOutboxDispatcher(ObOutboxRepository repository,
                              ChannelAdapterRegistry adapters,
                              ObOutboxProperties properties,
                              ObDeliveryFailureNotifier failureNotifier,
                              Clock clock) {
        this.repository = repository;
        this.adapters = adapters;
        this.properties = properties;
        this.failureNotifier = failureNotifier;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: measuring from the end of the
     * previous run stops polls stacking up when a batch runs long.
     */
    @Scheduled(fixedDelayString = "${edutrack.ob-outbox.poll-interval:PT5S}")
    public void drain() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // An exception escaping a @Scheduled method cancels every future
            // execution. Log and let the next tick try again.
            log.error("ob-outbox: poll failed, will retry next tick", e);
        }
    }

    /** Quarter-hourly: a queue filling with rows nobody can send should be visible. */
    @Scheduled(fixedDelayString = "${edutrack.ob-outbox.unroutable-report-interval:PT15M}",
               initialDelayString = "${edutrack.ob-outbox.unroutable-report-interval:PT15M}")
    public void reportUnroutable() {
        try {
            Map<String, Integer> waiting = repository.unroutableByChannel(adapters.supported());
            if (!waiting.isEmpty()) {
                log.warn("ob-outbox: {} pending row(s) on channels with no adapter — {}",
                        waiting.values().stream().mapToInt(Integer::intValue).sum(), waiting);
            }
        } catch (RuntimeException e) {
            log.error("ob-outbox: unroutable report failed", e);
        }
    }

    /** One reclaim-claim-deliver cycle. Returns how many messages were processed. */
    public int pollOnce() {
        Instant now = clock.instant();

        int reclaimed = repository.reclaimExpiredLeases(now);
        if (reclaimed > 0) {
            log.warn("ob-outbox: reclaimed {} row(s) whose {} lease had lapsed — a worker died "
                    + "mid-send or a send outran the lease", reclaimed, properties.lease());
        }

        Set<ObChannel> channels = adapters.supported();
        if (channels.isEmpty()) {
            return 0;
        }

        List<ObOutboxMessage> batch = repository.claimBatch(
                channels, properties.batchSize(), properties.lease(), now);
        if (batch.isEmpty()) {
            return 0;
        }

        // One virtual thread per message: the work is I/O wait, not CPU.
        try (ExecutorService dispatch = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> inFlight = new ArrayList<>(batch.size());
            for (ObOutboxMessage message : batch) {
                inFlight.add(dispatch.submit(() -> process(message)));
            }
            // Block until the batch is done so the next poll cannot start
            // while these rows are still in flight on their lease.
            for (Future<?> f : inFlight) {
                awaitQuietly(f);
            }
        }
        return batch.size();
    }

    /**
     * Deliver one claimed message and stamp the outcome. Package-private so a
     * unit test can drive the outcome handling without a database.
     */
    void process(ObOutboxMessage message) {
        // attempts counts what has been made; this one is about to be.
        int attempts = message.attempts() + 1;

        DeliveryOutcome outcome = deliver(message);

        switch (outcome) {
            case DeliveryOutcome.Sent sent -> repository.markSent(
                    message.id(), sent.providerMessageId(), attempts, clock.instant());

            case DeliveryOutcome.PermanentFailure failure -> {
                log.warn("ob-outbox: permanent failure for id={} {} via {}: {}",
                        message.id(), message.eventKey(), message.channel(), failure.reason());
                fail(message, attempts, failure.reason());
            }

            case DeliveryOutcome.TransientFailure failure -> {
                if (attempts >= properties.maxAttempts()) {
                    log.warn("ob-outbox: giving up on id={} after {} attempts: {}",
                            message.id(), attempts, failure.reason());
                    fail(message, attempts, failure.reason());
                } else {
                    Duration backoff = properties.backoffFor(attempts);
                    Instant nextAttempt = clock.instant().plus(backoff);
                    log.info("ob-outbox: retrying id={} attempt {} of {} in {}",
                            message.id(), attempts, properties.maxAttempts(), backoff);
                    repository.markForRetry(message.id(), attempts, nextAttempt, failure.reason());
                }
            }
        }
    }

    private DeliveryOutcome deliver(ObOutboxMessage message) {
        // Channel-agnostic, so checked here rather than in every adapter: a
        // deactivated user or contact gets nothing on any channel. Permanent,
        // because reactivation is rare and a retry ladder would only delay
        // telling somebody the message never went.
        if (!message.details().active()) {
            return new DeliveryOutcome.PermanentFailure(
                    "Recipient " + describe(message) + " is inactive");
        }

        Optional<ObChannelAdapter> adapter = adapters.adapterFor(message.channel());
        if (adapter.isEmpty()) {
            // Cannot happen through claimBatch, which filters on supported
            // channels; reachable only if process() is called directly.
            // Transient, so the row goes back to the queue and waits.
            return new DeliveryOutcome.TransientFailure(
                    "No adapter registered for " + message.channel());
        }

        try {
            DeliveryOutcome outcome = adapter.get().deliver(message);
            if (outcome == null) {
                return new DeliveryOutcome.TransientFailure(
                        adapter.get().getClass().getSimpleName() + " returned no outcome");
            }
            return outcome;
        } catch (RuntimeException e) {
            // An adapter should report failures, not throw. Treat a bug as
            // transient so the message is not lost while the bug is fixed.
            log.warn("ob-outbox: adapter threw for id={}", message.id(), e);
            return new DeliveryOutcome.TransientFailure(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void fail(ObOutboxMessage message, int attempts, String reason) {
        repository.markFailed(message.id(), attempts, reason, clock.instant());
        // Stamping FAILED is not enough — somebody has to be told, or the
        // recipient goes on assuming the message arrived (§17: provable, not
        // deniable).
        failureNotifier.notifyDeliveryFailed(message, attempts, reason);
    }

    private static String describe(ObOutboxMessage message) {
        return message.recipient().type().toLowerCase(java.util.Locale.ROOT)
                + (message.details().email() == null ? "" : " " + message.details().email());
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // process() already stamped an outcome; this only catches a
            // failure to stamp, which the next lease expiry will retry.
            log.error("ob-outbox: dispatch task failed", e);
        }
    }
}
