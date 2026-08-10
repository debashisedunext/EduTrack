package com.edunext.edutrack.worker.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * D-032 · the identifiers that make a ticket's mail one conversation.
 *
 * <p>Outlook and Gmail both thread on RFC 5322 {@code Message-ID},
 * {@code In-Reply-To} and {@code References} — not on the subject. Without
 * these, a ticket with fourteen updates arrives as fourteen unrelated mails and
 * the assignee's inbox is the worst view of the ticket anybody has. With them,
 * the whole ticket collapses into one thread that opens where the reader left
 * off.
 *
 * <p><strong>Every mail references a root that was never sent.</strong>
 * {@link #rootOf(long)} is synthesised from the ticket id, so the first mail for
 * a ticket and the fortieth both point at the same ancestor without anyone
 * having to remember which one came first. The alternative — making the first
 * mail's own {@code Message-ID} the thread root — means a query to find out
 * whether this is the first, and a mail sent out of order or a first mail that
 * bounced leaves the rest of the thread orphaned. Clients thread on a
 * referenced id whether or not they ever saw a message carrying it.
 *
 * <p>Ids are opaque and contain nothing but our own primary keys, which are
 * already visible in every deep link. They are deliberately <em>not</em>
 * random: the whole point is that they can be recomputed.
 */
@Component
public class MailThread {

    /**
     * The right-hand side of every id we mint.
     *
     * <p>Must be stable for the life of a deployment — changing it re-roots
     * every future mail and silently splits every existing thread in two.
     */
    private final String domain;

    public MailThread(@Value("${edutrack.mail.message-id-domain:edutrack.local}") String domain) {
        this.domain = domain;
    }

    /**
     * The thread root for a ticket. Referenced by every mail about it, and
     * never used as a {@code Message-ID}.
     */
    public String rootOf(long ticketId) {
        return "<ticket." + ticketId + "@" + domain + ">";
    }

    /**
     * This mail's own identity, unique per {@code email_log} row.
     *
     * <p>Keyed on the row rather than the ticket because a duplicate
     * {@code Message-ID} lets a client treat the second mail as a copy of the
     * first and drop it — which would look exactly like the mail never
     * arriving, the failure §17 wants provable rather than deniable.
     */
    public String messageIdOf(Long ticketId, long emailLogId) {
        return ticketId == null
                ? "<mail." + emailLogId + "@" + domain + ">"
                : "<ticket." + ticketId + ".mail." + emailLogId + "@" + domain + ">";
    }
}
