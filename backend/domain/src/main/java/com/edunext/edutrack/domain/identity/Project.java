package com.edunext.edutrack.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A project — blueprint §8.2, maintained by the Project Master (S-08).
 *
 * <p><b>{@code projectCode} is immutable once the first ticket exists</b>
 * (PLAN.md §3.2). It is the ticket-ID prefix, so changing it would strand every
 * historical code that has already been quoted in mail, chat and client
 * conversations. The Project Master rejects the edit; nothing in the schema
 * can.
 *
 * <p>{@code clientName} is the denormalised label from §8.2. A-006 adds the
 * real {@code clients} table alongside it — this column stays as the
 * free-text fallback for projects with no client row.
 */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ticket-ID prefix. Immutable once a ticket exists. */
    @Column(name = "project_code", nullable = false, length = 10)
    private String projectCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** S-10 free text. Added by B-016's {@code V20260813_1420}. */
    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "client_name", length = 150)
    private String clientName;

    /** Actor reference — scalar, per the package-info rule. */
    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "target_end_date")
    private LocalDate targetEndDate;

    /**
     * {@code ACTIVE | ON_HOLD | CLOSED} — blueprint S-10, and the whole
     * vocabulary since B-016's {@code ck_projects_status}.
     *
     * <p>This javadoc previously named two further values, {@code COMPLETED} and
     * {@code ARCHIVED}, which the blueprint does not contain and which nothing
     * has ever written. Two vocabularies for one column with nothing mapping
     * between them is what B-030 found on {@code import_batches.status}; the
     * constraint was added at the first writer rather than after.
     *
     * <p>There is no delete on this table. A project that has issued ticket IDs
     * cannot be removed without orphaning every one of them, so {@code CLOSED}
     * is the retirement path.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** #RRGGBB, from the blueprint §12.1 token set. */
    @Column(name = "colour_tag", length = 7)
    private String colourTag;

    /**
     * {@code ROUND_ROBIN | LEAST_LOADED | MANUAL} — who an unassigned new ticket
     * goes to. <b>B-019's Settings tab edits this</b>; B-016 stores it so the
     * contract's long-standing {@code autoAssignRule} field stops being answered
     * with a constant.
     */
    @Column(name = "auto_assign_rule", nullable = false, length = 20)
    private String autoAssignRule = "MANUAL";

    /**
     * Per-project ticket counter. <b>Mapped for reading only.</b> Allocating a
     * ticket number by loading this, adding one and saving is a lost-update
     * race under concurrency — the allocation is the atomic
     * {@code LAST_INSERT_ID(expr)} statement in PLAN.md §3.2, issued by Stream
     * C's ticket-code generator.
     *
     * <p><b>{@code updatable = false} is what makes the sentence above true, and
     * it must stay.</b> Having no setter does not protect this column: Hibernate
     * writes <i>every</i> updatable column of a dirty entity, not only the ones
     * that were touched. So a transaction that loads a Project, renames it, and
     * allocates a ticket code would flush {@code ticket_seq} back to the value
     * read <i>before</i> the increment — silently undoing the allocation and
     * making the next ticket reuse the ID, which then dies on
     * {@code uq_tickets_code}. Hibernate cannot see the conflict, because the
     * allocation deliberately runs as raw SQL on the same connection.
     *
     * <p>The consequence to know: after an allocation, a managed instance holds
     * a stale value here. Nothing may act on it — read the counter through
     * Stream C's {@code TicketSequenceRepository}, never through this getter.
     *
     * <p>Guarded by {@code ProjectTicketSeqMappingTest} and reproduced end-to-end
     * by {@code TicketIdGenerationIT.jpaFlushCannotRevertTheAllocation}.
     */
    @Column(name = "ticket_seq", nullable = false, updatable = false)
    private Long ticketSeq = 0L;

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

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
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

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getTargetEndDate() {
        return targetEndDate;
    }

    public void setTargetEndDate(LocalDate targetEndDate) {
        this.targetEndDate = targetEndDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getColourTag() {
        return colourTag;
    }

    public void setColourTag(String colourTag) {
        this.colourTag = colourTag;
    }

    public String getAutoAssignRule() {
        return autoAssignRule;
    }

    public void setAutoAssignRule(String autoAssignRule) {
        this.autoAssignRule = autoAssignRule;
    }

    public Long getTicketSeq() {
        return ticketSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
