package com.edunext.edutrack.domain.chat;

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
 * One file shared into a chat thread — D-053, blueprint §7.6.
 *
 * <p>Deliberately <b>not</b> {@code TicketAttachment} with a nullable ticket:
 * the migration that creates this table sets out why at length. In short, a
 * chat file has no cycle, no stage, no client-visibility flag and no
 * fifteen-minute delete window, and {@code ticket_attachments.ticket_id} being
 * NOT NULL is load-bearing for four features that a chat file shares none of.
 *
 * <p>What <em>is</em> shared is the part that matters: the same
 * {@code AttachmentTypePolicy}, {@code ImageMetadataStripper} and
 * {@code AttachmentScanner} decide whether the bytes are acceptable, so there
 * is one answer to "is this file safe", called from two places.
 *
 * <h2>{@code messageId} is null until the message is posted</h2>
 *
 * <p>Upload and send are two requests on purpose: the file is sniffed,
 * stripped, stored and queued for scanning while the author is still typing,
 * so the send is instant and a rejected file is rejected before they have
 * written anything. A row that never acquires a message is a file nobody sent.
 */
@Entity
@Table(name = "chat_attachments")
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    /** Null between upload and the message that carries it — see the class javadoc. */
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** {@code chat/{threadId}/{uuid}} — minted by {@code ChatAttachmentStorageKey}. */
    @Column(name = "storage_key", nullable = false, length = 400)
    private String storageKey;

    /** Sniffed from the bytes, never taken from the client's declared type. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "thumbnail_key", length = 400)
    private String thumbnailKey;

    /** PENDING | CLEAN | INFECTED — the same vocabulary {@code ticket_attachments} uses. */
    @Column(name = "scan_status", nullable = false, length = 15)
    private String scanStatus;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    /**
     * Mirrors the carrying message's tombstone rather than a window of its own
     * — D-057 already governs chat immutability, and two differently-sized
     * windows over the same evidence is the version worth avoiding.
     */
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

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
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
