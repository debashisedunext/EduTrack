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
 * The kind of work a ticket represents — S-11. Eleven rows are seeded by B-002
 * (Change Request, Production Bug, Client Request, …).
 *
 * <p>{@code defaultLevel} and {@code defaultSlaHours} are what blueprint §4B.1
 * means by "pre-filled from the task type master": a Production Bug arrives at
 * High, a Future Release at Low. They pre-fill the create form and nothing more
 * — the user may override either, and {@code sla_policies} outranks both once
 * the ticket exists.
 */
@Entity
@Table(name = "task_types")
public class TaskType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "icon", length = 30)
    private String icon;

    /** {@code #RRGGBB}, and only a blueprint §12.1 token — no ad-hoc colours. */
    @Column(name = "colour", length = 7)
    private String colour;

    /** LOW | MEDIUM | HIGH | CRITICAL — the {@code priorities} code, not an FK. */
    @Column(name = "default_level", length = 10)
    private String defaultLevel;

    /** Working hours, resolved through the calendar service (B-024). */
    @Column(name = "default_sla_hours", precision = 6, scale = 2)
    private BigDecimal defaultSlaHours;

    /** Display order in the type picker. */
    @Column(name = "seq", nullable = false)
    private short seq;

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

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getColour() {
        return colour;
    }

    public void setColour(String colour) {
        this.colour = colour;
    }

    public String getDefaultLevel() {
        return defaultLevel;
    }

    public void setDefaultLevel(String defaultLevel) {
        this.defaultLevel = defaultLevel;
    }

    public BigDecimal getDefaultSlaHours() {
        return defaultSlaHours;
    }

    public void setDefaultSlaHours(BigDecimal defaultSlaHours) {
        this.defaultSlaHours = defaultSlaHours;
    }

    public short getSeq() {
        return seq;
    }

    public void setSeq(short seq) {
        this.seq = seq;
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
