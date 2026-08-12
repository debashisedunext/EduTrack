package com.edunext.edutrack.domain.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Read access to the mail outbox.
 *
 * <p>There is no claim method here and there should not be: D-010's worker
 * claims with {@code FOR UPDATE SKIP LOCKED}, which no derived finder can
 * express. A "find claimable rows" finder without the lock would look close
 * enough to use and would send every mail twice under two workers.
 */
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    /** "Show me this ticket's mail" — the delivery-proof read (D-033). */
    List<EmailLog> findByTicketIdOrderByQueuedAtDesc(Long ticketId);

    /** Operational views: what is stuck in QUEUED, what BOUNCED. */
    List<EmailLog> findByStatus(String status);

    /**
     * D-039 · did we actually mail this address about this ticket?
     *
     * <p>The authorisation for an inbound reply. A {@code From} header is not
     * authentication — it is a string anybody can set — so "the sender matches
     * a user" would let one forged address post a comment as any colleague on
     * any ticket. This asks the only question the system can answer for itself:
     * whether the address being replied from is one we sent this ticket's mail
     * to. Someone who was never told about a ticket cannot reply to it.
     *
     * <p>Served by {@code ix_email_log_rate}, which already leads with
     * {@code (to_email, ticket_id)} for D-035's rate limit.
     */
    boolean existsByTicketIdAndToEmailIgnoreCase(Long ticketId, String toEmail);
}
