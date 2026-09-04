package com.edunext.edutrack.worker.onboarding.outbox;

/**
 * B-110 · what an {@link ObChannelAdapter} did with a message.
 *
 * <p>The transient/permanent split is the adapter's judgement and drives
 * whether the row is retried or failed now. A refused SMTP connection is
 * transient; a contact with no address, or a WhatsApp number that has never
 * opted in, is not — retrying those only delays the failure notice.
 *
 * <p>No {@code Delivered} case, on purpose. The migration keeps {@code sent_at}
 * and {@code delivered_at} apart because delivery is reported later, by
 * webhook, after the send has already succeeded. An adapter cannot observe it
 * at send time; the webhook (D-101, and the mail provider's bounce feed) fills
 * that column in, keyed on {@code providerMessageId}.
 */
public sealed interface DeliveryOutcome {

    /** Accepted by the channel. {@code providerMessageId} may be null if none was returned. */
    record Sent(String providerMessageId) implements DeliveryOutcome {
    }

    /** Failed in a way that may succeed later — retry with backoff. */
    record TransientFailure(String reason) implements DeliveryOutcome {
    }

    /** Failed in a way that will not succeed later — fail now, do not retry. */
    record PermanentFailure(String reason) implements DeliveryOutcome {
    }
}
