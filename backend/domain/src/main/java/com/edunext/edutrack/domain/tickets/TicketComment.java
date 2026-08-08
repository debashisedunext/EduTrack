package com.edunext.edutrack.domain.tickets;

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
 * The discussion thread (blueprint §4B.5).
 *
 * <p><b>The journey coordinates are copied, never joined.</b>
 * {@code cycleNo}, {@code stageCode} and {@code iterationNo} are stamped at the
 * time of writing: the ribbon moves on, and a comment written during QA
 * iteration 2 must still read as QA iteration 2 forever. Joining to the live
 * ticket would silently rewrite the past every time the ticket advanced. This
 * is also what lets a ribbon segment click filter the thread beneath it. They
 * are nullable because a comment can precede the first stage transition on a
 * freshly created ticket.
 *
 * <p><b>{@code isInternal} defaults to true.</b> Blueprint §4B.5 and Stream C's
 * decision log both settle on internal-always: an accidental leak to a client
 * costs far more than an extra click.
 *
 * <p>Unlike the three protected tables this one is genuinely mutable — the
 * five-minute edit window needs it. The guarantee is kept in the service layer
 * instead: {@code originalBody} is written once on first edit and never again,
 * and {@code isDeleted} is a tombstone, so the row stays and the History
 * timeline can still place it.
 */
@Entity
@Table(name = "ticket_comments")
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "cycle_no")
    private Short cycleNo;

    @Column(name = "stage_code", length = 20)
    private String stageCode;

    @Column(name = "iteration_no")
    private Short iterationNo;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    /** Stored alongside the HTML for search and for reply-by-email's plain part. */
    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    private String bodyText;

    /** False means client-visible. */
    @Column(name = "is_internal", nullable = false)
    private boolean isInternal = true;

    /**
     * PostgreSQL {@code BIGINT[]} became MySQL {@code JSON} (PLAN.md §3.1).
     * Fanned out to notifications at write time; it is not queried as "every
     * comment mentioning me", and §3.4 names the child table to reach for if it
     * ever needs to be.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mentioned_user_ids")
    private List<Long> mentionedUserIds;

    /** WEB | EMAIL | API. */
    @Column(name = "source", nullable = false, length = 15)
    private String source = "WEB";

    @Column(name = "edited_at")
    private Instant editedAt;

    /** Written once, on first edit. Never overwritten by a later one. */
    @Column(name = "original_body", columnDefinition = "text")
    private String originalBody;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Short getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(Short cycleNo) {
        this.cycleNo = cycleNo;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public Short getIterationNo() {
        return iterationNo;
    }

    public void setIterationNo(Short iterationNo) {
        this.iterationNo = iterationNo;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public void setBodyHtml(String bodyHtml) {
        this.bodyHtml = bodyHtml;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public boolean isInternal() {
        return isInternal;
    }

    public void setInternal(boolean internal) {
        this.isInternal = internal;
    }

    public List<Long> getMentionedUserIds() {
        return mentionedUserIds;
    }

    public void setMentionedUserIds(List<Long> mentionedUserIds) {
        this.mentionedUserIds = mentionedUserIds;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public String getOriginalBody() {
        return originalBody;
    }

    public void setOriginalBody(String originalBody) {
        this.originalBody = originalBody;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setDeleted(boolean deleted) {
        this.isDeleted = deleted;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
