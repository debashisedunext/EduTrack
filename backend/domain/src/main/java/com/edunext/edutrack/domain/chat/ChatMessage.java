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

    /**
     * TEXT | STATUS_REQUEST | SYSTEM (D-050).
     *
     * <p>This supersedes {@link #isSystem}, which predates it and answers the
     * same question for SYSTEM rows only — D-050 backfilled them and left the
     * older column in place rather than dropping a column from Stream A's
     * baseline. Write both until that is resolved, or the two disagree.
     *
     * <p>"Ask Status" sets {@code STATUS_REQUEST} here. The baseline models
     * that flow as a {@code thread_type} instead; blueprint §7.6 puts the
     * structured message into the ticket's own thread, so the behaviour follows
     * this column and {@code thread_type = 'ASK_STATUS'} stays unwritten.
     */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind = "TEXT";

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Null means never edited. The five-minute window is a service concern. */
    @Column(name = "edited_at")
    private Instant editedAt;

    /**
     * The tombstone. The row stays and the body is withheld on read — a message
     * that vanished entirely would leave a conversation reading as though it
     * never happened, which is what keeps chat admissible as project evidence
     * (§7.6).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }
}
