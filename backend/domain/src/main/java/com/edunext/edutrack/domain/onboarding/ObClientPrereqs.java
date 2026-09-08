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
 * B-125 · one client's prerequisite checklist header — the row that pins
 * which version of the master they were snapshotted from.
 *
 * <h2>The pin is the point</h2>
 *
 * <p>Plan §1.1 #2: publishing a newer master leaves every boarded client on
 * the checklist they were actually given. Without {@link #templateVersionId},
 * "what was this client asked for" becomes unanswerable the moment OB-14 is
 * edited — which is precisely the question a waiver dispute turns on.
 *
 * <p>{@link #templateVersion} is denormalised beside the id deliberately: the
 * id is the referential truth, the number is what OB-05 and CP-03 display and
 * what a report groups by. It cannot drift, because a published version's
 * number is immutable — B-124 refuses every write to a published row.
 *
 * <h2>{@code status} is not a second opinion about the gate</h2>
 *
 * <p>{@code CLEARED} is this row's own record that the gate condition was
 * met, and it moves in the same transaction as {@code ob_journeys.gate_status}
 * (C-118). It is kept here rather than read from a journey because it
 * survives a client whose journeys have all been archived, and because the
 * OB-05 strip renders before any journey is read.
 */
@Entity
@Table(name = "ob_client_prereqs")
public class ObClientPrereqs {

    /** {@code ob_client_prereqs.status}. Two values; the gate decides which. */
    public enum Status {
        IN_PROGRESS,
        CLEARED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "template_version_id", nullable = false)
    private Long templateVersionId;

    /** Display copy of the pinned version's number. See the class javadoc. */
    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "cleared_at")
    private Instant clearedAt;

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

    public Long getTemplateVersionId() {
        return templateVersionId;
    }

    public void setTemplateVersionId(Long templateVersionId) {
        this.templateVersionId = templateVersionId;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(int templateVersion) {
        this.templateVersion = templateVersion;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(Instant clearedAt) {
        this.clearedAt = clearedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
