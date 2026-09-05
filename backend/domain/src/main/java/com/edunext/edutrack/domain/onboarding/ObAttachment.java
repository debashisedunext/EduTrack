package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A-102 · {@code ob_attachments} — polymorphic within the module only (see
 * the migration header for why this is not {@code ticket_attachments}).
 * Exactly one of {@link #obClientId}/{@link #stepId}/{@link #signoffId} is
 * set, and exactly one of {@link #uploadedByUser}/{@link #uploadedByContact}.
 *
 * <p>First read by C-106's completion gate — {@code step_id +
 * scan_status = CLEAN + deleted_at IS NULL}, counted against the template's
 * required-document checklist. Every other column is mapped for the tasks
 * already waiting on this table (B-107, B-116, C-121) rather than left for
 * each to remap.
 */
@Entity
@Table(name = "ob_attachments")
public class ObAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id")
    private Long obClientId;

    @Column(name = "step_id")
    private Long stepId;

    @Column(name = "signoff_id")
    private Long signoffId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 12)
    private ObAttachmentKind kind = ObAttachmentKind.SUBMISSION;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploaded_by_type", nullable = false, length = 10)
    private ObAttachmentUploaderType uploadedByType;

    @Column(name = "uploaded_by_user")
    private Long uploadedByUser;

    @Column(name = "uploaded_by_contact")
    private Long uploadedByContact;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 400)
    private String storageKey;

    /** Hex SHA-256, {@code CHAR(64)} — the {@link SqlTypes#CHAR} code is what makes
     * {@code ddl-auto=validate} agree; without it Hibernate expects {@code VARCHAR}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 10)
    private ObAttachmentScanStatus scanStatus = ObAttachmentScanStatus.PENDING;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getObClientId() {
        return obClientId;
    }

    public void setObClientId(Long obClientId) {
        this.obClientId = obClientId;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public Long getSignoffId() {
        return signoffId;
    }

    public void setSignoffId(Long signoffId) {
        this.signoffId = signoffId;
    }

    public ObAttachmentKind getKind() {
        return kind;
    }

    public void setKind(ObAttachmentKind kind) {
        this.kind = kind;
    }

    public ObAttachmentUploaderType getUploadedByType() {
        return uploadedByType;
    }

    public void setUploadedByType(ObAttachmentUploaderType uploadedByType) {
        this.uploadedByType = uploadedByType;
    }

    public Long getUploadedByUser() {
        return uploadedByUser;
    }

    public void setUploadedByUser(Long uploadedByUser) {
        this.uploadedByUser = uploadedByUser;
    }

    public Long getUploadedByContact() {
        return uploadedByContact;
    }

    public void setUploadedByContact(Long uploadedByContact) {
        this.uploadedByContact = uploadedByContact;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public ObAttachmentScanStatus getScanStatus() {
        return scanStatus;
    }

    public void setScanStatus(ObAttachmentScanStatus scanStatus) {
        this.scanStatus = scanStatus;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(Instant scannedAt) {
        this.scannedAt = scannedAt;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
