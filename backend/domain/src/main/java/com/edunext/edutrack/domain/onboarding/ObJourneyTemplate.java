package com.edunext.edutrack.domain.onboarding;

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
 * C-101 · a Module Service (product vocabulary) — and one <b>version</b> of it.
 * Plan §5.1: editing a published template never mutates it; it publishes a
 * new version, and a journey already instantiated keeps pointing at the
 * version it was born from. Migration {@code V20260903_1420} carries the
 * full reasoning for the shape below; this is where a reader confirms it.
 *
 * <h2>One row is one version, not one product</h2>
 *
 * <p>{@code productId} is shared by every version a product has ever had.
 * {@link #isActive} is true for at most one of them at a time — enforced by
 * {@code uq_ob_journey_templates_active} — and {@link #publishedAt} is set
 * exactly once, the moment a version is published, and never touched again
 * afterwards, including when a later version supersedes it.
 *
 * <h2>Draft vs published vs retired</h2>
 *
 * <ul>
 *   <li><b>Draft</b> — {@code publishedAt == null}. Never published. The only
 *       state {@code ObJourneyTemplateService} will mutate.</li>
 *   <li><b>Published (active)</b> — {@code publishedAt != null && isActive}.
 *       The version every new journey for this product instantiates from.</li>
 *   <li><b>Retired</b> — {@code publishedAt != null && !isActive}. Superseded
 *       by a later publish. <b>Still frozen</b> — a journey instantiated while
 *       it was active still pins this exact row, so it must never become
 *       editable again just because {@link #isActive} went false.</li>
 * </ul>
 *
 * <p>So the mutability test the service applies is {@code publishedAt == null},
 * never {@code !isActive} — the second would reopen a retired version to
 * editing, which is precisely the in-flight-journey corruption this table
 * exists to prevent.
 */
@Entity
@Table(name = "ob_journey_templates")
public class ObJourneyTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    /** Cross-product, cycle-freedom enforced by the service layer (C-123), not here. */
    @Column(name = "depends_on_template_id")
    private Long dependsOnTemplateId;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by")
    private Long createdBy;

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

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public Long getDependsOnTemplateId() {
        return dependsOnTemplateId;
    }

    public void setDependsOnTemplateId(Long dependsOnTemplateId) {
        this.dependsOnTemplateId = dependsOnTemplateId;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
