package com.edunext.edutrack.worker.onboarding.outbox;

/**
 * B-111 · the onboarding mail that is actually sent, once the template has been
 * rendered.
 *
 * <p>Separate from {@link ObOutboxMessage} for the reason A-107's migration
 * gives: the queue row holds "the rendered message's variables, not the rendered
 * message", so that a wording correction reaches everything still queued.
 * Folding a body into the row would mean rendering inside the enqueuing
 * transaction, and a template fixed at nine o'clock would never reach the
 * reminder queued at eight.
 *
 * @param subject the line the recipient sees
 * @param html    the body, values escaped and wrapped in the onboarding layout
 */
record ObMailContent(String subject, String html) {
}
