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
 * A non-working day, org-wide or for one project. One half of the working
 * calendar; {@link ResourceLeave} is the other.
 *
 * <p>Every duration and SLA figure in the system routes through B-024's
 * working-hours service, which reads this table. Without it a Friday-18:00
 * ticket with a four-hour target breaches on Saturday morning.
 *
 * <p>{@code isRecurring} marks a fixed-date annual holiday stored <b>once</b>.
 * The calendar service expands it per year rather than the master carrying a row
 * per year — which means a plain date-range query does not see next year's
 * occurrence. {@link HolidayRepository} says so where it matters.
 */
@Entity
@Table(name = "holidays")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A date, not an instant — giving a holiday a timezone would move it. */
    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Null = org-wide. Scalar id, per the note on {@link SlaPolicy#getProjectId()}. */
    @Column(name = "project_id")
    private Long projectId;

    /** Same calendar date every year; stored once and expanded by B-024. */
    @Column(name = "is_recurring", nullable = false)
    private boolean isRecurring;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public boolean isRecurring() {
        return isRecurring;
    }

    public void setRecurring(boolean recurring) {
        this.isRecurring = recurring;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
