package com.edunext.edutrack.domain.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * One message in a {@link ChatThread}.
 *
 * <p>Ordering is by {@code id}, not {@code createdAt} — the read cursor on
 * {@link ChatParticipant} compares ids, and two messages can share a
 * microsecond.
 *
 * <p>{@code senderId} null means SYSTEM ({@code isSystem} set): "Ravi moved
 * this to QA" posted by the platform rather than a person.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    /** Null = SYSTEM. */
    @Column(name = "sender_id")
    private Long senderId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /**
     * {@code [42, 17]} — PostgreSQL {@code BIGINT[]} became MySQL {@code JSON}
     * in PLAN.md §3.1. The database asserts only that it is an array
     * ({@code ck_chat_mentions_is_array}). It is not queried ("all messages
     * mentioning me" would want a child table instead); {@literal @}mention
     * only fans out a notification at write time.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mentioned_user_ids")
    private List<Long> mentionedUserIds;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<Long> getMentionedUserIds() {
        return mentionedUserIds;
    }

    public void setMentionedUserIds(List<Long> mentionedUserIds) {
        this.mentionedUserIds = mentionedUserIds;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean system) {
        this.isSystem = system;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
