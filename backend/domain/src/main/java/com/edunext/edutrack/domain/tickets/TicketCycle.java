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
 * One life of a ticket (blueprint §4). Cycle 1 is created with the ticket;
 * each reopen seals the previous cycle and opens the next.
 *
 * <p>Effort is attributed per cycle, which is what lets the §4A.4 grid report
 * "24.5 h this cycle, 38.0 h across all cycles" — the pair of numbers M4 has to
 * reconcile.
 *
 * <p><b>Mutable by design.</b> A cycle accumulates effort and is sealed on
 * close, so it is deliberately not one of the three append-only tables.
 * {@code reopenReason} records why <em>this</em> cycle was opened;
 * {@code resolutionSummary} how <em>this</em> one was closed — both are
 * per-cycle facts, which is exactly why they do not live on the ticket.
 */
@Entity
@Table(name = "ticket_cycles")
public class TicketCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "cycle_no", nullable = false)
    private short cycleNo;

    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "assigned_by")
    private Long assignedBy;

    /** The level this cycle ran at — a reopen can start at a different one. */
    @Column(name = "level", length = 10)
    private String level;

    @Column(name = "planned_close_date")
    private Instant plannedCloseDate;

    @Column(name = "actual_close_date")
    private Instant actualCloseDate;

    @Column(name = "effort_hrs", nullable = false, precision = 8, scale = 2)
    private BigDecimal effortHrs = BigDecimal.ZERO;

    @Column(name = "reopen_reason", columnDefinition = "text")
    private String reopenReason;

    @Column(name = "resolution_summary", columnDefinition = "text")
    private String resolutionSummary;

    /** C-040 · S-23's "root cause category" — a free label, no master behind it yet. */
    @Column(name = "root_cause_category", length = 100)
    private String rootCauseCategory;

    /** C-040 · S-23's "optional client verification request" checkbox. */
    @Column(name = "client_verification_requested", nullable = false)
    private boolean clientVerificationRequested;

    @Column(name = "is_sealed", nullable = false)
    private boolean isSealed;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public short getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(short cycleNo) {
        this.cycleNo = cycleNo;
    }

    public Instant getStartDate() {
        return startDate;
    }

    public void setStartDate(Instant startDate) {
        this.startDate = startDate;
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

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
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

    public BigDecimal getEffortHrs() {
        return effortHrs;
    }

    public void setEffortHrs(BigDecimal effortHrs) {
        this.effortHrs = effortHrs;
    }

    public String getReopenReason() {
        return reopenReason;
    }

    public void setReopenReason(String reopenReason) {
        this.reopenReason = reopenReason;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public void setResolutionSummary(String resolutionSummary) {
        this.resolutionSummary = resolutionSummary;
    }

    public String getRootCauseCategory() {
        return rootCauseCategory;
    }

    public void setRootCauseCategory(String rootCauseCategory) {
        this.rootCauseCategory = rootCauseCategory;
    }

    public boolean isClientVerificationRequested() {
        return clientVerificationRequested;
    }

    public void setClientVerificationRequested(boolean clientVerificationRequested) {
        this.clientVerificationRequested = clientVerificationRequested;
    }

    public boolean isSealed() {
        return isSealed;
    }

    public void setSealed(boolean sealed) {
        this.isSealed = sealed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
