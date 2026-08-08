package com.edunext.edutrack.domain.masters;

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
 * A resource's absence — the other half of the working calendar alongside
 * {@link Holiday}.
 *
 * <p>A stage SLA must not tick while the stage owner is away, or a queue handed
 * out on the Friday before a week's leave breaches in full on the Monday of the
 * return. B-024's working-hours service subtracts these ranges, and every
 * duration in the system goes through it.
 *
 * <p>{@code isHalfDay} is a flag rather than a stored hour count: the calendar
 * service resolves it against that user's {@code daily_capacity_hrs}, so a
 * change to capacity does not have to rewrite historical leave rows.
 */
@Entity
@Table(name = "resource_leaves")
public class ResourceLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Scalar id, per the note on {@link SlaPolicy#getProjectId()}. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Inclusive, and never before {@code startDate} — {@code ck_resource_leaves_range}. */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** PLANNED | SICK | COMP_OFF | OTHER. */
    @Column(name = "leave_type", length = 30)
    private String leaveType;

    @Column(name = "is_half_day", nullable = false)
    private boolean isHalfDay;

    /** Only APPROVED leave stops the SLA clock. Defaults to APPROVED in the DDL. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "APPROVED";

    @Column(name = "reason", length = 255)
    private String reason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
    }

    public boolean isHalfDay() {
        return isHalfDay;
    }

    public void setHalfDay(boolean halfDay) {
        this.isHalfDay = halfDay;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
