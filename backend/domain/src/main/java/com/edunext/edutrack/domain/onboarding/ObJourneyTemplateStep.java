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
 * A Service within a Module Service (product vocabulary) — one row of
 * {@code ob_journey_template_steps}. See the migration for the full
 * dependency-graph reasoning; the summary that matters here:
 *
 * <p>{@link #dependsOnStepId} {@code == null} means the step runs in
 * <b>parallel</b> from journey start, not "first". The database enforces
 * only that a dependency stays inside the same template — that an earlier
 * step is named is C-119's job, evaluated by the designer and the service
 * on every reorder and delete.
 */
@Entity
@Table(name = "ob_journey_template_steps")
public class ObJourneyTemplateStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Working days (v1.2) — never hours. See CLAUDE.md's working-calendar rule. */
    @Column(name = "tat_days", nullable = false)
    private int tatDays;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "owner_role", length = 40)
    private String ownerRole;

    @Column(name = "backup_owner_user_id")
    private Long backupOwnerUserId;

    @Column(name = "requires_signoff", nullable = false)
    private boolean requiresSignoff;

    /** {@code null} = parallel. See the class javadoc. */
    @Column(name = "depends_on_step_id")
    private Long dependsOnStepId;

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

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public void setOwnerRole(String ownerRole) {
        this.ownerRole = ownerRole;
    }

    public Long getBackupOwnerUserId() {
        return backupOwnerUserId;
    }

    public void setBackupOwnerUserId(Long backupOwnerUserId) {
        this.backupOwnerUserId = backupOwnerUserId;
    }

    public boolean isRequiresSignoff() {
        return requiresSignoff;
    }

    public void setRequiresSignoff(boolean requiresSignoff) {
        this.requiresSignoff = requiresSignoff;
    }

    public Long getDependsOnStepId() {
        return dependsOnStepId;
    }

    public void setDependsOnStepId(Long dependsOnStepId) {
        this.dependsOnStepId = dependsOnStepId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
