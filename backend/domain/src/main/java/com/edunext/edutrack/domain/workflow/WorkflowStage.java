package com.edunext.edutrack.domain.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One segment of a ribbon — blueprint §4A.5.
 *
 * <p>{@code ownerRole} is what makes the golden rule of §2 enforceable: only
 * the current stage's owning role, plus PM and Admin, may move a ticket on. A
 * Developer cannot push a ticket into Deployment while it sits with QA.
 *
 * <p><b>{@code slaHours} is working hours, not wall-clock.</b> It is resolved
 * through the working-calendar service (B-024) — weekends, org holidays and
 * resource leave — so a Friday-18:00 handoff with a 4-hour stage SLA does not
 * breach on Saturday morning.
 *
 * <p><b>Stages in use are deprecated, never deleted</b> — blueprint §7.4, and
 * {@code isDeprecated} is B-042's column for saying so. The rule lives here rather
 * than in a constraint because the database cannot state it: A-005 made
 * {@code ticket_stage_transitions.to_stage} and {@code tickets.current_stage}
 * plain {@code VARCHAR} holding the code with no foreign key onto this table, so
 * deleting a row cascades nothing and fails nowhere while leaving every
 * historical ribbon segment pointing at a definition that is gone.
 */
@Entity
@Table(name = "workflow_stages")
public class WorkflowStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * One of the few genuine ownership associations in this model — a stage has
     * no meaning apart from its template, and the designer screen (B-041) walks
     * exactly this edge. {@code LAZY} because the ticket list renders stage
     * codes without ever needing the template row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private WorkflowTemplate template;

    /** Ribbon order, left to right. */
    @Column(name = "seq", nullable = false)
    private short seq;

    /** INTAKE | TRIAGE | DEV | QA | DEPLOY | VERIFY | SIGNOFF | CLOSED. */
    @Column(name = "stage_code", nullable = false, length = 20)
    private String stageCode;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "owner_role", nullable = false, length = 20)
    private String ownerRole;

    /** Working hours, resolved through the calendar service — never wall-clock. */
    @Column(name = "sla_hours", precision = 6, scale = 2)
    private BigDecimal slaHours;

    /** Skippable, with a reason. */
    @Column(name = "is_optional", nullable = false)
    private boolean isOptional;

    /**
     * Stage codes this stage may send a ticket back to, e.g.
     * {@code ["DEV","TRIAGE"]}. PostgreSQL {@code VARCHAR(20)[]} became MySQL
     * {@code JSON} in PLAN.md §3.1; the database can only assert that it is an
     * array ({@code ck_can_return_to_is_array}), so the codes themselves are
     * validated in the service layer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "can_return_to")
    private List<String> canReturnTo;

    @Column(name = "icon", length = 30)
    private String icon;

    /**
     * Retired — B-042. A deprecated stage keeps rendering on every ribbon that has
     * already been through it and accepts no new entry.
     *
     * <p>Not {@code isActive}, which is what every other master calls this, and the
     * difference is not cosmetic. An inactive task type or level is one nobody may
     * <em>choose</em> any more; a deprecated stage is one that tickets are still
     * standing in and whose code is still written into rows nothing can rewrite.
     * §7.4 uses the word deliberately and so does this column.
     */
    @Column(name = "is_deprecated", nullable = false)
    private boolean isDeprecated;

    /**
     * When it was retired, and it is not derivable.
     *
     * <p>The last hop into a stage says when it was last <em>used</em>, which is a
     * different date from when it was <em>retired</em> — the gap between them is
     * the interval that makes "when did we stop using this?" worth asking at all.
     * {@code ck_workflow_stages_deprecation} keeps this and the flag in step.
     */
    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public WorkflowTemplate getTemplate() {
        return template;
    }

    public void setTemplate(WorkflowTemplate template) {
        this.template = template;
    }

    public short getSeq() {
        return seq;
    }

    public void setSeq(short seq) {
        this.seq = seq;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public void setOwnerRole(String ownerRole) {
        this.ownerRole = ownerRole;
    }

    public BigDecimal getSlaHours() {
        return slaHours;
    }

    public void setSlaHours(BigDecimal slaHours) {
        this.slaHours = slaHours;
    }

    public boolean isOptional() {
        return isOptional;
    }

    public void setOptional(boolean optional) {
        this.isOptional = optional;
    }

    public List<String> getCanReturnTo() {
        return canReturnTo;
    }

    public void setCanReturnTo(List<String> canReturnTo) {
        this.canReturnTo = canReturnTo;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public boolean isDeprecated() {
        return isDeprecated;
    }

    public void setDeprecated(boolean deprecated) {
        this.isDeprecated = deprecated;
    }

    public Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
