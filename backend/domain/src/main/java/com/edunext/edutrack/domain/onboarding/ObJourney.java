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
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * C-103 · one purchased product's journey — {@code ob_journeys} (A-104,
 * {@code V20260903_1600}). One row per {@code (obClientId, productId)}
 * among non-archived; the migration's own header has the full reasoning
 * this class only summarises.
 *
 * <p>{@link #templateId} PINS THE VERSION at instantiation and never
 * changes afterwards — provenance, never the display source. Every other
 * field a journey needs to render (step names, TATs, sign-off flags) is
 * snapshotted onto {@link ObJourneyStep}, not read back through this id.
 *
 * <p>Two independent holds, deliberately two fields: {@link #gateStatus} is
 * the client's prerequisites (C-118, flips for every journey of the client
 * at once); {@link #heldByJourneyId} is this journey's own service-level
 * dependency (C-123, cleared one journey at a time). C-103 sets the first
 * at instantiation and leaves the second {@code null} — resolving it against
 * the template's service-dependency graph is C-123's own task.
 */
@Entity
@Table(name = "ob_journeys")
public class ObJourney {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_status", nullable = false, length = 10)
    private ObGateStatus gateStatus = ObGateStatus.LOCKED;

    @Column(name = "gate_opened_at")
    private Instant gateOpenedAt;

    @Column(name = "gate_opened_by")
    private Long gateOpenedBy;

    /** C-123's service-level dependency. Always {@code null} out of C-103. */
    @Column(name = "held_by_journey_id")
    private Long heldByJourneyId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

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

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public ObGateStatus getGateStatus() {
        return gateStatus;
    }

    public void setGateStatus(ObGateStatus gateStatus) {
        this.gateStatus = gateStatus;
    }

    public Instant getGateOpenedAt() {
        return gateOpenedAt;
    }

    public void setGateOpenedAt(Instant gateOpenedAt) {
        this.gateOpenedAt = gateOpenedAt;
    }

    public Long getGateOpenedBy() {
        return gateOpenedBy;
    }

    public void setGateOpenedBy(Long gateOpenedBy) {
        this.gateOpenedBy = gateOpenedBy;
    }

    public Long getHeldByJourneyId() {
        return heldByJourneyId;
    }

    public void setHeldByJourneyId(Long heldByJourneyId) {
        this.heldByJourneyId = heldByJourneyId;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
