package com.edunext.edutrack.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

    /** Every thread on a ticket — a TICKET thread plus any ASK_STATUS ones. */
    List<ChatThread> findByTicketId(Long ticketId);

    List<ChatThread> findByCreatedBy(Long createdBy);
}
