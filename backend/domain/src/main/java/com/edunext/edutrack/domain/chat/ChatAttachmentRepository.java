package com.edunext.edutrack.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** D-053 · {@link ChatAttachment} by message, by thread, and by id for the download path. */
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    /**
     * Everything carried by these messages, in id order.
     *
     * <p>One query for a page of messages rather than one per message —
     * {@code ChatService} renders a page at a time and the N+1 would be a
     * query per line on screen. Deleted rows are included and withheld by the
     * caller, the same way §7.6 keeps a deleted body in the row.
     */
    List<ChatAttachment> findByMessageIdInOrderByIdAsc(List<Long> messageIds);

    /** One attachment, for the download and the attach-to-message step. */
    Optional<ChatAttachment> findByIdAndThreadId(long id, long threadId);
}
