package com.edunext.edutrack.domain.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of {@link ChatParticipant} — {@code (thread_id, user_id)}.
 *
 * <p>Membership has no surrogate id: the pair <em>is</em> the fact, and a
 * generated id would let the same user join a thread twice.
 */
@Embeddable
public class ChatParticipantId implements Serializable {

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatParticipantId that)) {
            return false;
        }
        return Objects.equals(threadId, that.threadId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(threadId, userId);
    }
}
