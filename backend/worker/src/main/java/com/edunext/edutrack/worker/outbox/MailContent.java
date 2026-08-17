package com.edunext.edutrack.worker.outbox;

/**
 * D-029 · what actually gets sent, once the template has been rendered.
 *
 * <p>Separate from {@link OutboxMessage} because that record is the queue row —
 * what was stored at enqueue — and this is the product of rendering it, which
 * depends on a template an Admin can change between the enqueue and the send.
 * Folding the body into the queue row would mean rendering at enqueue, inside
 * the business transaction, and a template edit would then never reach mail
 * already queued.
 *
 * @param subject the line the recipient sees, already carrying D-031's prefix
 * @param html    the body, escaped and wrapped in D-030's layout
 */
record MailContent(String subject, String html) {
}
