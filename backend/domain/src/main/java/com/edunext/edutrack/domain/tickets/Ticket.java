package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The ticket. Blueprint §8.2, plus the §4A.5 ribbon pointers and the §4B.7
 * client attribution.
 *
 * <h2>Why every foreign key here is a scalar id</h2>
 *
 * Ticket has nine foreign keys. Mapped as {@code @ManyToOne} they would attach
 * nine lazy proxies to every row, and the ticket list — the hottest query in
 * the system, run by every user on every page — would become an N+1 generator
 * that only shows up under load. A-034's {@code ScopeResolver} also composes
 * its predicates on {@code project_id} and {@code assigned_to} directly.
 * Screens that need names join explicitly through a projection, which is one
 * query with the columns it actually wants.
 *
 * <h2>level vs originalLevel</h2>
 *
 * {@code level} is current and may be raised by the escalation engine.
 * {@code originalLevel} is what the ticket was raised at and never changes —
 * A-070's "born critical vs became critical" report is the entire reason both
 * exist.
 *
 * <h2>The two independent counters (§4A.2)</h2>
 *
 * {@code currentIteration} increments when a ticket moves <b>backwards</b>
 * within a cycle; {@code currentCycleNo} increments when a <b>closed</b> ticket
 * is <b>reopened</b>. Neither derives from the other. See
 * {@link com.edunext.edutrack.domain.workflow.TicketStageTransition}, which is
 * where the per-hop record of both lives.
 *
 * <p>{@code ticketCode} is generated with the {@code LAST_INSERT_ID(expr)}
 * idiom against {@code projects.ticket_seq} (PLAN.md §3.2) — <b>never</b> from
 * a {@code COUNT(*)}, which breaks silently under concurrency.
 *
 * <p>{@code commentCount} and {@code attachmentCount} are materialised counters
 * maintained by the service layer on insert and tombstone, so the list can
 * render badges without a correlated subquery per row.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. {@code CRM-26-00347}. Unique, and the dominant search by volume. */
    @Column(name = "ticket_code", nullable = false, length = 30)
    private String ticketCode;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "task_type_id")
    private Integer taskTypeId;

    /** LOW | MEDIUM | HIGH | CRITICAL. The code, not an FK — see Priority. */
    @Column(name = "level", nullable = false, length = 10)
    private String level;

    /** Never mutated after creation. */
    @Column(name = "original_level", nullable = false, length = 10)
    private String originalLevel;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "NEW";

    @Column(name = "environment", length = 20)
    private String environment;

    /**
     * Business data, not an audit stamp — a ticket raised from an email that
     * arrived yesterday is reported yesterday. The SQL default only covers the
     * common case, so this stays writable.
     */
    @Column(name = "date_reported", nullable = false)
    private Instant dateReported;

    @Column(name = "reported_by")
    private Long reportedBy;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "estimated_effort_hrs", precision = 6, scale = 2)
    private BigDecimal estimatedEffortHrs;

    /** Σ across all cycles. Per-cycle effort lives on {@link TicketCycle}. */
    @Column(name = "total_effort_hrs", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalEffortHrs = BigDecimal.ZERO;

    /** 0-100, resource-reported — S-21 Quick Update's slider. */
    @Column(name = "pct_complete", nullable = false)
    private short pctComplete;

    @Column(name = "planned_close_date")
    private Instant plannedCloseDate;

    @Column(name = "actual_close_date")
    private Instant actualCloseDate;

    @Column(name = "is_reopened", nullable = false)
    private boolean isReopened;

    @Column(name = "reopen_count", nullable = false)
    private short reopenCount;

    @Column(name = "current_cycle_no", nullable = false)
    private short currentCycleNo = 1;

    @Column(name = "is_delayed", nullable = false)
    private boolean isDelayed;

    @Column(name = "delayed_since")
    private Instant delayedSince;

    @Column(name = "workflow_template_id")
    private Long workflowTemplateId;

    @Column(name = "current_stage", length = 20)
    private String currentStage = "INTAKE";

    @Column(name = "current_iteration", nullable = false)
    private short currentIteration = 1;

    @Column(name = "rework_count", nullable = false)
    private short reworkCount;

    @Column(name = "stage_entered_at")
    private Instant stageEnteredAt;

    // ── §4B.7 client attribution ────────────────────────────────────────

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "client_contact_id")
    private Long clientContactId;

    /**
     * A stored fact rather than {@code clientId != null}: an internally-raised
     * ticket can still belong to a client, and client-wise reporting, CSAT and
     * the default client-visibility of comments all turn on which it was.
     */
    @Column(name = "is_client_raised", nullable = false)
    private boolean isClientRaised;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "attachment_count", nullable = false)
    private int attachmentCount;

    /**
     * A-009's stored generated column, {@code IF(actual_close_date IS NULL,
     * planned_close_date, NULL)} — MySQL's stand-in for the partial index
     * PostgreSQL would have used (PLAN.md §3.3). It is what makes Stream D's
     * 15-minute SLA scan O(breaches) rather than O(all tickets): closed tickets
     * evaluate to NULL and never fall in range. Read-only; the database
     * maintains it.
     */
    @Column(name = "pcd_open", insertable = false, updatable = false)
    private Instant pcdOpen;

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

    public String getTicketCode() {
        return ticketCode;
    }

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
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

    public Integer getTaskTypeId() {
        return taskTypeId;
    }

    public void setTaskTypeId(Integer taskTypeId) {
        this.taskTypeId = taskTypeId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getOriginalLevel() {
        return originalLevel;
    }

    public void setOriginalLevel(String originalLevel) {
        this.originalLevel = originalLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Instant getDateReported() {
        return dateReported;
    }

    public void setDateReported(Instant dateReported) {
        this.dateReported = dateReported;
    }

    public Long getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(Long reportedBy) {
        this.reportedBy = reportedBy;
    }

    public Long getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Long assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Long getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(Long assignedBy) {
        this.assignedBy = assignedBy;
    }

    public BigDecimal getEstimatedEffortHrs() {
        return estimatedEffortHrs;
    }

    public void setEstimatedEffortHrs(BigDecimal estimatedEffortHrs) {
        this.estimatedEffortHrs = estimatedEffortHrs;
    }

    public BigDecimal getTotalEffortHrs() {
        return totalEffortHrs;
    }

    public void setTotalEffortHrs(BigDecimal totalEffortHrs) {
        this.totalEffortHrs = totalEffortHrs;
    }

    public short getPctComplete() {
        return pctComplete;
    }

    public void setPctComplete(short pctComplete) {
        this.pctComplete = pctComplete;
    }

    public Instant getPlannedCloseDate() {
        return plannedCloseDate;
    }

    public void setPlannedCloseDate(Instant plannedCloseDate) {
        this.plannedCloseDate = plannedCloseDate;
    }

    public Instant getActualCloseDate() {
        return actualCloseDate;
    }

    public void setActualCloseDate(Instant actualCloseDate) {
        this.actualCloseDate = actualCloseDate;
    }

    public boolean isReopened() {
        return isReopened;
    }

    public void setReopened(boolean reopened) {
        this.isReopened = reopened;
    }

    public short getReopenCount() {
        return reopenCount;
    }

    public void setReopenCount(short reopenCount) {
        this.reopenCount = reopenCount;
    }

    public short getCurrentCycleNo() {
        return currentCycleNo;
    }

    public void setCurrentCycleNo(short currentCycleNo) {
        this.currentCycleNo = currentCycleNo;
    }

    public boolean isDelayed() {
        return isDelayed;
    }

    public void setDelayed(boolean delayed) {
        this.isDelayed = delayed;
    }

    public Instant getDelayedSince() {
        return delayedSince;
    }

    public void setDelayedSince(Instant delayedSince) {
        this.delayedSince = delayedSince;
    }

    public Long getWorkflowTemplateId() {
        return workflowTemplateId;
    }

    public void setWorkflowTemplateId(Long workflowTemplateId) {
        this.workflowTemplateId = workflowTemplateId;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public short getCurrentIteration() {
        return currentIteration;
    }

    public void setCurrentIteration(short currentIteration) {
        this.currentIteration = currentIteration;
    }

    public short getReworkCount() {
        return reworkCount;
    }

    public void setReworkCount(short reworkCount) {
        this.reworkCount = reworkCount;
    }

    public Instant getStageEnteredAt() {
        return stageEnteredAt;
    }

    public void setStageEnteredAt(Instant stageEnteredAt) {
        this.stageEnteredAt = stageEnteredAt;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public Long getClientContactId() {
        return clientContactId;
    }

    public void setClientContactId(Long clientContactId) {
        this.clientContactId = clientContactId;
    }

    public boolean isClientRaised() {
        return isClientRaised;
    }

    public void setClientRaised(boolean clientRaised) {
        this.isClientRaised = clientRaised;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public int getAttachmentCount() {
        return attachmentCount;
    }

    public void setAttachmentCount(int attachmentCount) {
        this.attachmentCount = attachmentCount;
    }

    public Instant getPcdOpen() {
        return pcdOpen;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
