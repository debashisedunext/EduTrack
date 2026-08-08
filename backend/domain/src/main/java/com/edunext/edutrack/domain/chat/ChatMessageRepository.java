package com.edunext.edutrack.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * The transcript. Ordered by id rather than {@code createdAt} — ids are the
     * cursor the read marker compares against, and they are total where a
     * microsecond timestamp is not.
     */
    List<ChatMessage> findByThreadIdOrderByIdAsc(Long threadId);

    /**
     * Unread count for one participant: pass their
     * {@code lastReadMessageId}. A null cursor means nothing has been read, so
     * the caller must count the whole thread instead of passing null here.
     * Served by {@code ix_chat_messages_thread (thread_id, id)}.
     */
    long countByThreadIdAndIdGreaterThan(Long threadId, Long lastReadMessageId);
}
