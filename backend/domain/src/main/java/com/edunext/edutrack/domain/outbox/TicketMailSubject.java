package com.edunext.edutrack.domain.outbox;

/**
 * D-031 · the subject line, with the ticket ID first.
 *
 * <p>{@code [CRM-26-00347] Handed to you at QA by Ravi Kumar}. The order is the
 * requirement, not a preference: a recipient scanning a full inbox sorts and
 * searches on what the subject <em>starts</em> with, and mail clients truncate
 * from the right in list view. A subject reading "Handed to you at QA by Ravi
 * Kumar for tick…" identifies the sender's mood and not the ticket.
 *
 * <p><strong>Applied by {@link OutboxEnqueuer}, not by callers.</strong> Fifteen
 * §4B.6 events each formatting their own subject is fifteen chances to forget
 * the prefix, and the one that forgets is invisible until somebody cannot find
 * a mail. Callers pass what happened; the ticket code is added here.
 */
public final class TicketMailSubject {

    /** {@code email_log.subject} is VARCHAR(300). */
    static final int MAX_LENGTH = 300;

    private static final String ELLIPSIS = "…";

    private TicketMailSubject() {
    }

    /**
     * Prefix a subject with its ticket code.
     *
     * @param ticketCode the ticket's code, or null for non-ticket mail
     * @param subject    what happened, or null
     * @return the composed subject, never longer than the column
     */
    public static String compose(String ticketCode, String subject) {
        String summary = subject == null ? "" : subject.strip();

        if (ticketCode == null || ticketCode.isBlank()) {
            return truncate(summary);
        }

        String prefix = "[" + ticketCode.strip() + "]";
        if (summary.isEmpty()) {
            return truncate(prefix);
        }
        // Enqueueing an already-composed subject must not produce
        // "[CRM-26-00347] [CRM-26-00347] …". Callers that build their own
        // subject for a one-off are wrong but should not be disfigured.
        if (summary.startsWith(prefix)) {
            return truncate(summary);
        }
        return truncate(prefix + " " + summary);
    }

    /**
     * Cut to fit the column, keeping the front.
     *
     * <p>The alternative is an insert that throws, which would roll back the
     * business transaction the mail was enqueued in — a handoff lost because
     * somebody wrote a long ticket title. Losing the tail of a subject is the
     * cheaper failure, and the prefix, which is the part that has to survive,
     * is at the front.
     */
    private static String truncate(String subject) {
        if (subject.length() <= MAX_LENGTH) {
            return subject;
        }
        return subject.substring(0, MAX_LENGTH - ELLIPSIS.length()).stripTrailing() + ELLIPSIS;
    }
}
