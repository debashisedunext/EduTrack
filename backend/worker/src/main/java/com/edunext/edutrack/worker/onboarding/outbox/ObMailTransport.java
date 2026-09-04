package com.edunext.edutrack.worker.onboarding.outbox;

/**
 * B-110 · how an onboarding email actually leaves, once
 * {@link EmailChannelAdapter} has decided it may.
 *
 * <p>The seam B-111 filled. {@link SmtpObMailTransport} renders the message
 * from {@link ObMailTemplate}'s wording through the existing mail engine — "no
 * new transport" — and is where the rendered subject and body meet
 * {@code JavaMailSender}. {@link LoggingObMailTransport} renders the same mail
 * and records what would have been sent instead of sending it, and is still the
 * default: see {@code edutrack.ob-outbox.email.transport}.
 *
 * <p>Split from the adapter rather than folded into it so the adapter's
 * channel-level checks — suppression, a missing address — are tested once and
 * do not have to be repeated by every transport.
 */
public interface ObMailTransport {

    DeliveryOutcome send(ObOutboxMessage message);
}
