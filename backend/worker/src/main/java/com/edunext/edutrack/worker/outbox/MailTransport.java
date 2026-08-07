package com.edunext.edutrack.worker.outbox;

/**
 * How a claimed message actually leaves the building.
 *
 * <p>This seam is the reason PLAN.md §2.2 can say RabbitMQ "remains an option
 * if volume outgrows" the outbox: the worker owns claiming, backoff and
 * stamping, and knows nothing about SMTP. Swapping transport does not touch
 * the retry semantics.
 *
 * <p>Implementations must not throw for delivery failures — return a
 * {@link SendOutcome} instead. The worker treats an escaping exception as a
 * transient failure, but that path exists for genuine bugs, not for flow
 * control.
 */
public interface MailTransport {

    SendOutcome send(OutboxMessage message);
}
