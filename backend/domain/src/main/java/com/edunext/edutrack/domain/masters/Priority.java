package com.edunext.edutrack.domain.masters;

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
 * A priority level — S-12, blueprint §4B.1. Low, Medium, High and Critical are
 * seeded by B-002.
 *
 * <p><b>A table rather than an enum because §4B.1 lets Admin add levels.</b>
 * {@code tickets.level} then stores the <em>code</em> and not a foreign key, so
 * retiring a level from this master leaves every historical ticket raised at it
 * intact and still readable. That is the trade being made: no referential
 * integrity on {@code level}, in exchange for a master that can change without
 * rewriting history.
 *
 * <p>{@code isEscalationTrigger} marks the level the SLA engine escalates
 * <em>to</em> on breach — Critical, per §6.
 */
@Entity
@Table(name = "priorities")
public class Priority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** LOW | MEDIUM | HIGH | CRITICAL. This is the value tickets carry. */
    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    @Column(name = "colour", length = 7)
    private String colour;

    /** Display order — severity rank is {@code seq}, never the id. */
    @Column(name = "seq", nullable = false)
    private short seq;

    /** Working hours; a matching {@code sla_policies} row overrides it. */
    @Column(name = "default_sla_hours", precision = 6, scale = 2)
    private BigDecimal defaultSlaHours;

    @Column(name = "is_escalation_trigger", nullable = false)
    private boolean isEscalationTrigger;

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

    public BigDecimal getDefaultSlaHours() {
        return defaultSlaHours;
    }

    public void setDefaultSlaHours(BigDecimal defaultSlaHours) {
        this.defaultSlaHours = defaultSlaHours;
    }

    public boolean isEscalationTrigger() {
        return isEscalationTrigger;
    }

    public void setEscalationTrigger(boolean escalationTrigger) {
        this.isEscalationTrigger = escalationTrigger;
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
