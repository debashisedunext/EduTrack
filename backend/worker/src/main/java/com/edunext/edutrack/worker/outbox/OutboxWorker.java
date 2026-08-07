package com.edunext.edutrack.worker.outbox;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * D-010 · the mail outbox worker. Claim, send, stamp.
 *
 * <p><strong>No ShedLock here, deliberately.</strong> D-011 adds ShedLock so
 * the SLA scanners do not double-fire across instances, and it is tempting to
 * wrap this too. It would be wrong: {@code SKIP LOCKED} already makes
 * concurrent claims disjoint, and that is what lets the outbox scale
 * horizontally under a mail burst (PLAN.md §7). A scheduler lock would pin
 * draining the queue to one instance — the opposite of what is wanted.
 */
@Component
@ConditionalOnProperty(name = "edutrack.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository repository;
    private final MailTransport transport;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxWorker(OutboxRepository repository,
                        MailTransport transport,
                        OutboxProperties properties,
                        Clock clock) {
        this.repository = repository;
        this.transport = transport;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: measuring from the end of the
     * previous run stops polls from stacking up when a batch runs long.
     */
    @Scheduled(fixedDelayString = "${edutrack.outbox.poll-interval:PT5S}")
    public void drain() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // Never let a failed poll kill the schedule — an exception
            // escaping a @Scheduled method cancels all future executions.
            log.error("outbox: poll failed, will retry next tick", e);
        }
    }

    /** One claim-and-send cycle. Returns how many messages were processed. */
    public int pollOnce() {
        List<OutboxMessage> batch = repository.claimBatch(
                properties.batchSize(), properties.lease(), clock.instant());

        if (batch.isEmpty()) {
            return 0;
        }

        // One virtual thread per message: the work is SMTP wait, not CPU.
        try (ExecutorService dispatch = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> inFlight = new ArrayList<>(batch.size());
            for (OutboxMessage message : batch) {
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

    private void process(OutboxMessage message) {
        SendOutcome outcome;
        try {
            outcome = transport.send(message);
        } catch (RuntimeException e) {
            // A transport should report failures, not throw. Treat a bug as
            // transient so the message is not lost while the bug is fixed.
            log.warn("outbox: transport threw for id={}", message.id(), e);
            outcome = new SendOutcome.TransientFailure(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // retry_count counts failures, not attempts: a message that succeeds
        // first time was retried zero times. `attempts` below is the count
        // *including* the one just made, which is what the retry limit is on.
        int attempts = message.retryCount() + 1;

        switch (outcome) {
            case SendOutcome.Sent sent -> repository.markSent(
                    message.id(), sent.providerMessageId(), message.retryCount(), clock.instant());

            case SendOutcome.PermanentFailure failure -> {
                log.warn("outbox: permanent failure for id={} to={}: {}",
                        message.id(), message.toEmail(), failure.reason());
                repository.markFailed(message.id(), attempts, failure.reason());
            }

            case SendOutcome.TransientFailure failure -> {
                if (attempts >= properties.maxAttempts()) {
                    log.warn("outbox: giving up on id={} after {} attempts: {}",
                            message.id(), attempts, failure.reason());
                    repository.markFailed(message.id(), attempts, failure.reason());
                } else {
                    Duration backoff = properties.backoffFor(attempts);
                    Instant nextAttempt = clock.instant().plus(backoff);
                    log.info("outbox: retrying id={} attempt {} of {} in {}",
                            message.id(), attempts, properties.maxAttempts(), backoff);
                    repository.markForRetry(message.id(), attempts, nextAttempt, failure.reason());
                }
            }
        }
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // process() already stamped an outcome; this only catches a
            // failure to stamp, which the next lease expiry will retry.
            log.error("outbox: dispatch task failed", e);
        }
    }
}
