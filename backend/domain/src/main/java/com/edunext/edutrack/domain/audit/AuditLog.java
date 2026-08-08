package com.edunext.edutrack.domain.audit;

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
 * One audited action — every login, permission change, master change and ticket
 * action. Feeds S-16, the Audit Log Viewer (A-071), which is export-only.
 *
 * <p><b>This is not one of the hash-chained append-only tables.</b>
 * {@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions} are; A-008 puts no immutability trigger on
 * {@code audit_logs}, and this entity is not {@code @Immutable}. Nothing
 * currently stops an {@code UPDATE} but convention. If the audit log needs the
 * same guarantee, that is a separate decision and a separate migration — stated
 * plainly here because a reader will otherwise assume it is protected.
 *
 * <p>{@code actorId} null means SYSTEM: a scanner escalation has no human
 * behind it.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = SYSTEM. */
    @Column(name = "actor_id")
    private Long actorId;

    /** LOGIN | LOGIN_FAILED | ROLE_CHANGED | … */
    @Column(name = "action", nullable = false, length = 60)
    private String action;

    /** Table name of the subject: {@code tickets}, {@code users}, … */
    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** VARCHAR(45) — wide enough for a full IPv6 address. */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
