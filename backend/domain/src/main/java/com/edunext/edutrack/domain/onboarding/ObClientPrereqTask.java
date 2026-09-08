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
 * B-125 · one task on one client's checklist — either snapshotted from the
 * master (B-124) or added for this client alone.
 *
 * <h2>The snapshot is a copy, not a reference</h2>
 *
 * <p>{@link #title}, {@link #description}, {@link #tatDays} and
 * {@link #isMandatory} are copied from the master task rather than read
 * through {@link #templateTaskId}. Reading through would make an OB-14 edit
 * rewrite what a client is being asked for — the exact corruption B-124's
 * versioning exists to prevent, reintroduced one join later.
 * {@code templateTaskId} is kept anyway so a waiver can be read back against
 * the wording that was in force; it is provenance, not the source of truth.
 *
 * <h2>{@code isAdHoc} rather than inferring it from a null template id</h2>
 *
 * <p>OB-05 marks these, because "why is this client being asked for something
 * the others are not" is the first question about one. A screen inferring it
 * from a null would also mark a snapshotted task whose master row had since
 * been removed, which is a different thing entirely. The two are held
 * consistent by {@code ck_ob_client_prereq_tasks_ad_hoc}.
 *
 * <h2>{@code dueAt} is stored; {@code isOverdue} is not</h2>
 *
 * <p>{@code dueAt} is working-calendar derived from {@code tatDays} at
 * instantiation and then stored, which is what keeps it stable when the
 * calendar is edited later. Whether the task is <em>overdue</em> is
 * {@link #isOverdue(Instant)} — derived on read, never stored, so it cannot
 * disagree with the timestamp beside it.
 */
@Entity
@Table(name = "ob_client_prereq_tasks")
public class ObClientPrereqTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_prereqs_id", nullable = false)
    private Long headerId;

    /**
     * Denormalised from the header. A-112's row-scope rule is expressed in
     * SQL over this column, and routing every scoped read through the header
     * would add a join to the module's most-read table for a value fixed at
     * insert — a task never moves between clients.
     */
    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    /** Provenance. Null on an ad-hoc task. See the class javadoc. */
    @Column(name = "template_task_id")
    private Long templateTaskId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "tat_days", nullable = false)
    private int tatDays;

    @Column(name = "is_mandatory", nullable = false)
    private boolean isMandatory;

    @Column(name = "is_ad_hoc", nullable = false)
    private boolean isAdHoc;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ObPrereqTaskStatus status = ObPrereqTaskStatus.PENDING;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "submitted_via", length = 10)
    private ObPrereqSubmittedVia submittedVia;

    @Column(name = "submitted_by_user")
    private Long submittedByUser;

    @Column(name = "submitted_by_contact")
    private Long submittedByContact;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "skipped_at")
    private Instant skippedAt;

    @Column(name = "skipped_by")
    private Long skippedBy;

    @Column(name = "skip_reason")
    private String skipReason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * Past {@link #dueAt} and not settled. Derived rather than stored, so it
     * cannot disagree with the timestamp it is derived from — the argument
     * {@code ob_implementor_daily_stats} makes for not storing its
     * performance score.
     *
     * <p>A settled task is never overdue, however late it was: the question
     * the screen asks is "what is the client still holding up", and a
     * verified task is holding nothing up.
     */
    public boolean isOverdue(Instant now) {
        return !status.isSettled() && dueAt != null && now.isAfter(dueAt);
    }

    /**
     * Whether this task holds the gate — plan §5.3: every mandatory task
     * {@code VERIFIED}, every non-mandatory one {@code VERIFIED} or
     * {@code SKIPPED}.
     *
     * <p>On the entity rather than in the gate service because both C-118 and
     * this module's own reads need the same predicate, and two spellings of a
     * gate condition is one more than the gate can survive.
     */
    public boolean holdsTheGate() {
        return isMandatory ? status != ObPrereqTaskStatus.VERIFIED : !status.isSettled();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getHeaderId() {
        return headerId;
    }

    public void setHeaderId(Long headerId) {
        this.headerId = headerId;
    }

    public Long getObClientId() {
        return obClientId;
    }

    public void setObClientId(Long obClientId) {
        this.obClientId = obClientId;
    }

    public Long getTemplateTaskId() {
        return templateTaskId;
    }

    public void setTemplateTaskId(Long templateTaskId) {
        this.templateTaskId = templateTaskId;
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

    public boolean isAdHoc() {
        return isAdHoc;
    }

    public void setAdHoc(boolean adHoc) {
        isAdHoc = adHoc;
    }

    public ObPrereqTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ObPrereqTaskStatus status) {
        this.status = status;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public ObPrereqSubmittedVia getSubmittedVia() {
        return submittedVia;
    }

    public void setSubmittedVia(ObPrereqSubmittedVia submittedVia) {
        this.submittedVia = submittedVia;
    }

    public Long getSubmittedByUser() {
        return submittedByUser;
    }

    public void setSubmittedByUser(Long submittedByUser) {
        this.submittedByUser = submittedByUser;
    }

    public Long getSubmittedByContact() {
        return submittedByContact;
    }

    public void setSubmittedByContact(Long submittedByContact) {
        this.submittedByContact = submittedByContact;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Long verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getSkippedAt() {
        return skippedAt;
    }

    public void setSkippedAt(Instant skippedAt) {
        this.skippedAt = skippedAt;
    }

    public Long getSkippedBy() {
        return skippedBy;
    }

    public void setSkippedBy(Long skippedBy) {
        this.skippedBy = skippedBy;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
