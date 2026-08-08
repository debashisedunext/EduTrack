package com.edunext.edutrack.domain.chat;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * Thread membership, and the read cursor.
 *
 * <p><b>{@code lastReadMessageId} is a cursor, not a per-message flag.</b>
 * Unread is "messages in this thread with a higher id" — one indexed
 * comparison against {@code ix_chat_messages_thread (thread_id, id)}, instead
 * of a read-receipt row per user per message. See
 * {@code ChatMessageRepository#countByThreadIdAndIdGreaterThan}.
 *
 * <p>Null cursor means the participant has read nothing, so every message in
 * the thread is unread — callers must handle that rather than defaulting to 0.
 */
@Entity
@Table(name = "chat_participants")
public class ChatParticipant {

    @EmbeddedId
    private ChatParticipantId id;

    @Generated(event = EventType.INSERT)
    @Column(name = "joined_at", insertable = false, updatable = false)
    private Instant joinedAt;

    /** Highest message id this participant has seen; null = has read nothing. */
    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    /** Muted participants stay in the thread but raise no notification. */
    @Column(name = "is_muted", nullable = false)
    private boolean isMuted;

    public ChatParticipantId getId() {
        return id;
    }

    public void setId(ChatParticipantId id) {
        this.id = id;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Long getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(Long lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }
}
