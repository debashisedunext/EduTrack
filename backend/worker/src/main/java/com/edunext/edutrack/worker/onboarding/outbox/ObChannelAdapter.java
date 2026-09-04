package com.edunext.edutrack.worker.onboarding.outbox;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;

/**
 * B-110 · how a claimed message leaves on one channel.
 *
 * <p><strong>This interface is the point of the task.</strong>
 * PHASE-2-BUILD-PLAN.md §6.1 defers WhatsApp and names the thing that keeps the
 * deferral reversible: "a channel-agnostic adapter interface in B-110", so that
 * adding the channel later is one implementation class rather than a redesign
 * of the dispatcher. The dispatcher owns claiming, leases, backoff, the retry
 * ceiling and the failure notice, and knows nothing about SMTP, a WhatsApp
 * provider or the bell. An adapter knows one of those and nothing about
 * queues.
 *
 * <p>One bean per channel. {@link ChannelAdapterRegistry} collects them at
 * startup and refuses two for the same channel; the dispatcher claims only
 * rows whose channel has an adapter, so a queue row for a channel this
 * deployment cannot send <em>waits</em> rather than failing — D-101's adapter
 * arriving later drains it.
 *
 * <p>Implementations report delivery failures as a {@link DeliveryOutcome} and
 * do not throw. The dispatcher treats an escaping exception as transient, but
 * that path exists for genuine bugs, not for flow control.
 */
public interface ObChannelAdapter {

    /** The one channel this adapter sends on. */
    ObChannel channel();

    /**
     * Deliver one message. Called on a virtual thread, possibly concurrently
     * with other messages on the same adapter, so implementations must be
     * thread-safe.
     */
    DeliveryOutcome deliver(ObOutboxMessage message);
}
