package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A file on a ticket (blueprint §4B.4).
 *
 * <p>{@code storageKey} is the object key, {@code tickets/{id}/{uuid}}. The
 * bucket is never public and reads go through short-lived signed URLs, so
 * nothing stored here is a resolvable address on its own.
 *
 * <p><b>{@code scanStatus} gates visibility and defaults to PENDING.</b> A file
 * is not served until the AV scan returns CLEAN, and an INFECTED one never is.
 * The default is PENDING rather than CLEAN specifically so a scanner outage
 * fails closed — the opposite default would serve unscanned uploads for the
 * duration of the outage without anyone noticing.
 *
 * <p>{@code commentId} is nullable: an attachment can hang off the ticket, a
 * comment, the handoff dialog or a quick update. Same tombstone rule as
 * comments — deleting inside the window removes the stored object, but the row
 * stays with {@code isDeleted} set so the History timeline can still place it.
 */
@Entity
@Table(name = "ticket_attachments")
public class TicketAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "cycle_no")
    private Short cycleNo;

    @Column(name = "stage_code", length = 20)
    private String stageCode;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_key", nullable = false, length = 400)
    private String storageKey;

    /** Sniffed server-side, never trusted from the client. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "thumbnail_key", length = 400)
    private String thumbnailKey;

    @Column(name = "is_client_visible", nullable = false)
    private boolean isClientVisible;

    /** PENDING | CLEAN | INFECTED. */
    @Column(name = "scan_status", nullable = false, length = 15)
    private String scanStatus = "PENDING";

    @Column(name = "uploaded_by")
    private Long uploadedBy;

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

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public void setThumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }

    public boolean isClientVisible() {
        return isClientVisible;
    }

    public void setClientVisible(boolean clientVisible) {
        this.isClientVisible = clientVisible;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public void setScanStatus(String scanStatus) {
        this.scanStatus = scanStatus;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
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
