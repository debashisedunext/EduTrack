package com.edunext.edutrack.domain.tickets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, Long> {

    List<TicketAttachment> findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(Long ticketId);

    List<TicketAttachment> findByCommentId(Long commentId);

    /** The Attachments tab, grouped by cycle and stage. */
    List<TicketAttachment> findByTicketIdAndCycleNoAndStageCodeAndIsDeletedFalse(
            Long ticketId, Short cycleNo, String stageCode);

    /** Backed by {@code uq_attachments_storage_key} — one row per stored object. */
    Optional<TicketAttachment> findByStorageKey(String storageKey);

    /**
     * The AV worker's work queue. PENDING is the default precisely so a scanner
     * outage leaves files unserved rather than unscanned-and-served.
     */
    List<TicketAttachment> findByScanStatus(String scanStatus);
}
