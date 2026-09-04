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
 * A-107 · {@code ob_signoffs} — client acceptance, per flagged service and
 * at go-live (§8). {@link #stepId} is {@code null} exactly when
 * {@link #kind} is {@link ObSignoffKind#GO_LIVE}; see the migration header
 * for why the token and OTP columns exist and are stored hashed.
 *
 * <p>First read by C-106's completion gate — a {@code STEP} row in
 * {@link ObSignoffStatus#SIGNED} against a step that {@code requires_signoff}
 * is what the gate looks for. Every column is mapped for the tasks already
 * waiting on this table (A-120, A-121, B-116, B-117, B-118) rather than
 * left for each to remap; nothing here writes a row yet — that is A-120's.
 */
@Entity
@Table(name = "ob_signoffs")
public class ObSignoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    /** {@code null} for a {@code GO_LIVE} sign-off. */
    @Column(name = "step_id")
    private Long stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private ObSignoffKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ObSignoffStatus status = ObSignoffStatus.PENDING;

    /** Hex SHA-256, {@code CHAR(64)} — the {@link SqlTypes#CHAR} code is what makes
     * {@code ddl-auto=validate} agree; without it Hibernate expects {@code VARCHAR}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "token_expires_at", nullable = false)
    private Instant tokenExpiresAt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "otp_hash", length = 64)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private Instant otpExpiresAt;

    @Column(name = "otp_attempts", nullable = false)
    private int otpAttempts;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "sent_to_contact_id", nullable = false)
    private Long sentToContactId;

    /** No {@code ON DELETE SET NULL} — this is the legal record. See the migration header. */
    @Column(name = "signed_by_contact_id")
    private Long signedByContactId;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "signed_ip", length = 45)
    private String signedIp;

    @Column(name = "signed_user_agent", length = 500)
    private String signedUserAgent;

    @Column(name = "objected_at")
    private Instant objectedAt;

    @Column(name = "objection_note", length = 2000)
    private String objectionNote;

    /** B-116's archived PDF. Object-storage key, never the bytes. */
    @Column(name = "pdf_storage_key", length = 400)
    private String pdfStorageKey;

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

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public ObSignoffKind getKind() {
        return kind;
    }

    public void setKind(ObSignoffKind kind) {
        this.kind = kind;
    }

    public ObSignoffStatus getStatus() {
        return status;
    }

    public void setStatus(ObSignoffStatus status) {
        this.status = status;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(Instant tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public void setOtpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public Instant getOtpExpiresAt() {
        return otpExpiresAt;
    }

    public void setOtpExpiresAt(Instant otpExpiresAt) {
        this.otpExpiresAt = otpExpiresAt;
    }

    public int getOtpAttempts() {
        return otpAttempts;
    }

    public void setOtpAttempts(int otpAttempts) {
        this.otpAttempts = otpAttempts;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(Long requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Long getSentToContactId() {
        return sentToContactId;
    }

    public void setSentToContactId(Long sentToContactId) {
        this.sentToContactId = sentToContactId;
    }

    public Long getSignedByContactId() {
        return signedByContactId;
    }

    public void setSignedByContactId(Long signedByContactId) {
        this.signedByContactId = signedByContactId;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public String getSignedIp() {
        return signedIp;
    }

    public void setSignedIp(String signedIp) {
        this.signedIp = signedIp;
    }

    public String getSignedUserAgent() {
        return signedUserAgent;
    }

    public void setSignedUserAgent(String signedUserAgent) {
        this.signedUserAgent = signedUserAgent;
    }

    public Instant getObjectedAt() {
        return objectedAt;
    }

    public void setObjectedAt(Instant objectedAt) {
        this.objectedAt = objectedAt;
    }

    public String getObjectionNote() {
        return objectionNote;
    }

    public void setObjectionNote(String objectionNote) {
        this.objectionNote = objectionNote;
    }

    public String getPdfStorageKey() {
        return pdfStorageKey;
    }

    public void setPdfStorageKey(String pdfStorageKey) {
        this.pdfStorageKey = pdfStorageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
