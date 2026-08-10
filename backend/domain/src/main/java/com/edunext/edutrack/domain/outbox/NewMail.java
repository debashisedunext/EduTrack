package com.edunext.edutrack.domain.outbox;

/**
 * A mail to queue. Everything except {@code eventCode} and {@code toEmail} is
 * optional — a system mail with no ticket, or a client contact who is not a
 * user, are both normal.
 *
 * @param ticketId   the ticket this concerns, or null for non-ticket mail
 * @param eventCode  the §4B.6 event, e.g. {@code TICKET_ASSIGNED}
 * @param templateId the notification template (S-15), or null to resolve at render
 * @param toUserId   the recipient user, or null when the recipient is a client contact
 * @param toEmail    the recipient address
 * @param subject    what happened, <em>without</em> the ticket code — D-031's
 *                   {@code [CRM-26-00347]} prefix is added by
 *                   {@link OutboxEnqueuer}, so no event can ship without it
 */
public record NewMail(
        Long ticketId,
        String eventCode,
        Long templateId,
        Long toUserId,
        String toEmail,
        String subject) {

    public NewMail {
        if (eventCode == null || eventCode.isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("toEmail is required");
        }
    }

    /** The common case: a ticket notification to a user. */
    public static NewMail forTicket(long ticketId, String eventCode, long toUserId,
                                    String toEmail, String subject) {
        return new NewMail(ticketId, eventCode, null, toUserId, toEmail, subject);
    }
}
