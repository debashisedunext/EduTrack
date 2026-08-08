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
}
