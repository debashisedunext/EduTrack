package com.edunext.edutrack.worker.outbox;

/**
 * One claimed {@code email_log} row.
 *
 * <p>There is no body: {@code email_log} stores the subject and the identifiers
 * needed to render — the body is produced from the notification template master
 * at send time (D-029/D-030). Storing a rendered body would freeze a copy of
 * every template at enqueue time and defeat the point of the master.
 *
 * @param retryCount attempts already made — 0 on a message that has never been
 *                   tried, so the count of attempts *including* the one about
 *                   to happen is {@code retryCount + 1}
 */
public record OutboxMessage(
        long id,
        Long ticketId,
        String eventCode,
        Long templateId,
        Long toUserId,
        String toEmail,
        String subject,
        int retryCount) {
}
