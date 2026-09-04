package com.edunext.edutrack.worker.onboarding.outbox;

/**
 * B-110 · how an onboarding email actually leaves, once
 * {@link EmailChannelAdapter} has decided it may.
 *
 * <p>The seam B-111 fills. That task renders the message from an email
 * template through the existing mail engine — "no new transport" — and its
 * implementation of this interface is where the rendered subject and body
 * meet {@code JavaMailSender}. Until it lands, {@link LoggingObMailTransport}
 * records what would have been sent, exactly as D-010 ran ahead of D-029.
 *
 * <p>Split from the adapter rather than folded into it so the adapter's
 * channel-level checks — suppression, a missing address — are tested once and
 * do not have to be repeated by every transport.
 */
public interface ObMailTransport {

    DeliveryOutcome send(ObOutboxMessage message);
}
