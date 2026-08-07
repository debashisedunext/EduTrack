package com.edunext.edutrack.worker.outbox;

/**
 * What a {@link MailTransport} did with a message.
 *
 * <p>The transient/permanent split is the transport's judgement and drives
 * whether the row is retried or failed immediately. A refused connection is
 * transient; a malformed address is not, and retrying it four times only
 * delays the failure notification.
 *
 * <p>There is deliberately no {@code Bounced} case. A bounce is asynchronous —
 * the provider accepts the message, then reports the bounce later over the
 * webhook in D-034. A transport cannot observe it at send time, and inferring
 * it here would mark rows BOUNCED that were merely rejected at handshake.
 */
public sealed interface SendOutcome {

    /** Accepted by the provider. {@code providerMessageId} may be null if none was returned. */
    record Sent(String providerMessageId) implements SendOutcome {
    }

    /** Failed in a way that may succeed later — retry with backoff. */
    record TransientFailure(String reason) implements SendOutcome {
    }

    /** Failed in a way that will not succeed later — fail now, do not retry. */
    record PermanentFailure(String reason) implements SendOutcome {
    }
}
