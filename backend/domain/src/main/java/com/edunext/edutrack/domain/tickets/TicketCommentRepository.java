package com.edunext.edutrack.domain.tickets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {

    /** The thread. Tombstoned rows are excluded here, not deleted in the table. */
    List<TicketComment> findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(Long ticketId);

    /**
     * Ribbon segment click → filter the thread to that stage and iteration.
     * Reads the coordinates stamped at write time, so it stays correct after
     * the ticket has moved on.
     */
    List<TicketComment> findByTicketIdAndCycleNoAndStageCodeAndIterationNoOrderByCreatedAtAsc(
            Long ticketId, Short cycleNo, String stageCode, Short iterationNo);

    /** Client-visible subset — the portal and the client-facing mail digest. */
    List<TicketComment> findByTicketIdAndIsInternalFalseAndIsDeletedFalseOrderByCreatedAtAsc(Long ticketId);
}
