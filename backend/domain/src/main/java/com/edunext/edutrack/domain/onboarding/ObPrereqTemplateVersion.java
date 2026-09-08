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
 * B-124 · one <b>version</b> of the org-wide prerequisites master (OB-14).
 * Migration {@code V20260908_1100} carries the full reasoning for the shape
 * below; this is where a reader confirms it.
 *
 * <h2>One master, not one per product</h2>
 *
 * <p>{@link ObJourneyTemplate} is keyed by product — a journey delivers a
 * thing that was bought. Prerequisites are the client's own
 * responsibilities, which plan §4 makes the same set regardless of what
 * they bought, so there is exactly one active version for the whole
 * organisation and this entity carries no owner column at all.
 *
 * <h2>Draft vs published vs retired — the same three states, one difference</h2>
 *
 * <ul>
 *   <li><b>Draft</b> — {@code publishedAt == null}. The only state
 *       {@code ObPrereqTemplateService} will mutate. <b>At most one exists
 *       at a time</b>, and unlike the journey side that is enforced by the
 *       database: {@code uq_ob_prereq_template_versions_draft} over a
 *       generated column. Two drafts would each be "the next version" and
 *       publishing either would silently discard the other.</li>
 *   <li><b>Published (active)</b> — {@code publishedAt != null &&
 *       isActive}. The version every new client is snapshotted from
 *       (B-125). Exactly one, by
 *       {@code uq_ob_prereq_template_versions_active}.</li>
 *   <li><b>Retired</b> — {@code publishedAt != null && !isActive}.
 *       Superseded by a later publish and <b>still frozen</b>: a client
 *       boarded while it was active pins this exact version, and the
 *       checklist they agreed to has to keep reading back as it was.</li>
 * </ul>
 *
 * <p>So the mutability test is {@code publishedAt == null}, never
 * {@code !isActive} — {@link ObJourneyTemplate}'s javadoc makes the same
 * point at more length, and the failure it prevents is identical: reopening
 * a retired version to editing rewrites what somebody was already asked
 * for.
 *
 * <h2>{@code isDraft} is not a column</h2>
 *
 * <p>The contract's {@code ObPrereqTemplate.isDraft} is {@link #isDraft()}
 * here — derived from {@link #publishedAt}, exactly as the database derives
 * {@code draft_key} from the same column. One fact, one home. A stored
 * boolean beside the timestamp would be free to disagree with it, and the
 * disagreement would decide whether a published version could be edited.
 */
@Entity
@Table(name = "ob_prereq_template_versions")
public class ObPrereqTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "created_by")
    private Long createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * Editable. Derived rather than stored — see the class javadoc, and
     * {@code draft_key} in the migration, which derives the database's own
     * copy of this from the same column.
     */
    public boolean isDraft() {
        return publishedAt == null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
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
