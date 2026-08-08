package com.edunext.edutrack.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, ChatParticipantId> {

    /** Who is in this thread — the fan-out list for a new message. */
    List<ChatParticipant> findByIdThreadId(Long threadId);

    /** Every thread a user belongs to; served by {@code ix_chat_participants_user}. */
    List<ChatParticipant> findByIdUserId(Long userId);
}
