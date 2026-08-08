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

/**
 * A ticket status — New, In Progress, On Hold, Awaiting Info, Rework, Resolved,
 * Closed, Reopened. Seeded by B-003.
 *
 * <p><b>Status and stage are separate layers (blueprint §3).</b> A ticket can be
 * "In Progress" while sitting in the "QA" stage: this table is status, and
 * {@link com.edunext.edutrack.domain.workflow.WorkflowStage} is the ribbon.
 * Collapsing the two is the modelling mistake §3 exists to prevent — the moment
 * status doubles as position, a ticket handed to QA can no longer be described
 * as blocked.
 *
 * <p>{@code isOpen} drives every open-ticket count on the dashboard;
 * {@code isTerminal} marks the states only a reopen moves a ticket out of.
 */
@Entity
@Table(name = "statuses")
public class Status {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Column(name = "colour", length = 7)
    private String colour;

    @Column(name = "seq", nullable = false)
    private short seq;

    @Column(name = "is_open", nullable = false)
    private boolean isOpen = true;

    @Column(name = "is_terminal", nullable = false)
    private boolean isTerminal;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColour() {
        return colour;
    }

    public void setColour(String colour) {
        this.colour = colour;
    }

    public short getSeq() {
        return seq;
    }

    public void setSeq(short seq) {
        this.seq = seq;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void setOpen(boolean open) {
        this.isOpen = open;
    }

    public boolean isTerminal() {
        return isTerminal;
    }

    public void setTerminal(boolean terminal) {
        this.isTerminal = terminal;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
