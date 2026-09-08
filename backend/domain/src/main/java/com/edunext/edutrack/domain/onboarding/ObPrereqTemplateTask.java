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
 * B-124 · one task on one version of the prerequisites master (OB-14) —
 * something the <b>client</b> owes, not something the organisation does.
 *
 * <h2>{@code sequence} is presentation only</h2>
 *
 * <p>Unlike {@link ObJourneyTemplateStep}, which carries
 * {@code dependsOnStepId} and whose order drives activation, prerequisites
 * have <b>no dependency model</b>: every task is available from the moment
 * the instance exists, because the client works their own checklist in
 * whatever order suits them and nothing on it activates anything else. The
 * number decides what OB-14 and CP-03 draw first, and nothing else reads
 * it.
 *
 * <h2>{@code isMandatory} is authored here and never on an instance</h2>
 *
 * <p>This flag is what the gate reads (C-118): it cannot open while a
 * mandatory task is outstanding, and a mandatory task cannot be skipped —
 * B-125's skip answers 422 rather than checking a role. Plan §14's stated
 * mitigation for "the gate stalls every journey on a slow client" is
 * keeping the mandatory list <i>short in the master</i>, which is a
 * mitigation only while the flag lives at authoring time. Editable per
 * client under delivery pressure, it would be a default rather than a
 * guarantee.
 *
 * <h2>{@code tatDays} — working days, and the plan text says otherwise</h2>
 *
 * <p>Plan §4's own line names {@code tat_hours}. The v1.2 rename changed
 * the unit for journey steps and the contract carries days here too, so
 * days is what this module uses everywhere; the migration records the
 * divergence rather than resolving it silently. An instance's {@code dueAt}
 * is this many <i>working</i> days from instantiation against the org
 * calendar (B-125), never a naive date addition.
 */
@Entity
@Table(name = "ob_prereq_template_tasks")
public class ObPrereqTemplateTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    /** Display order within the version. Presentation only — see the class javadoc. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description")
    private String description;

    /** Working days, not hours. See the class javadoc. */
    @Column(name = "tat_days", nullable = false)
    private int tatDays;

    @Column(name = "is_mandatory", nullable = false)
    private boolean isMandatory;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getTatDays() {
        return tatDays;
    }

    public void setTatDays(int tatDays) {
        this.tatDays = tatDays;
    }

    public boolean isMandatory() {
        return isMandatory;
    }

    public void setMandatory(boolean mandatory) {
        isMandatory = mandatory;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
