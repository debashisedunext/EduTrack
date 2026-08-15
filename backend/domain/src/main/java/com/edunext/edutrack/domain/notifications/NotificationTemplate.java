package com.edunext.edutrack.domain.notifications;

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
 * The wording of one notification, for one channel — blueprint §11, 23 events
 * across POPUP, BELL and EMAIL.
 *
 * <p>Templates rather than hardcoded strings so changing what a handoff mail
 * says is a data edit, not a deploy. Stream D renders them (Thymeleaf, D-010)
 * and owns the mail engine that consumes them; this entity is the master-data
 * side — the row that B's admin screen edits.
 *
 * <p>{@code subjectTemplate} is null for the non-email channels: a bell entry
 * has a title, not a subject.
 *
 * <p><b>B-022 mounted the screen and added {@code recipients}.</b> Until then
 * this entity had no caller anywhere outside its own package and the table held
 * no rows — {@code email_log.template_id} has pointed at it since
 * {@code V20260805_1530} and has never been non-null. {@code V20260815_1100}
 * seeds one row per (event, channel) pair blueprint §11 ticks.
 *
 * <p><b>{@code channel} carries {@link NotificationChannel}'s three values, not
 * the {@code POPUP|BELL|EMAIL} A-007's column comment predicted.</b> That
 * comment was written before D-042 existed; everything that runs today —
 * notification preferences, the mandatory-mail check, push subscriptions — keys
 * on {@code IN_APP}, {@code EMAIL} and {@code PUSH}, so a template stored under
 * {@code POPUP} would never be found by the renderer that looks it up. The
 * migration's section 2 carries the full argument, including why the bell is not
 * a channel of its own.
 */
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TICKET_ASSIGNED | HANDOFF_RECEIVED | SLA_BREACHED | … */
    @Column(name = "event_code", nullable = false, length = 60)
    private String eventCode;

    /** IN_APP | EMAIL | PUSH. Unique with {@code eventCode}. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    /**
     * B-022 · §11's "To" column, comma-delimited — {@code ASSIGNEE,PROJECT_MANAGER}.
     *
     * <p>A {@link NotificationRecipient} vocabulary rather than a join onto
     * {@code roles}, because eight of the ten things §11 names are positions
     * relative to a ticket rather than roles; that enum's javadoc carries the
     * argument. Read it with {@link NotificationRecipient#parse}, which skips a
     * token this build does not know rather than failing the whole send.
     *
     * <p>Never empty in practice: the write side refuses an empty list, since a
     * template with no recipients is a row that looks configured and sends
     * nothing.
     */
    @Column(name = "recipients", nullable = false, length = 255)
    private String recipients = "";

    @Column(name = "subject_template", length = 255)
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "text")
    private String bodyTemplate;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

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

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getRecipients() {
        return recipients;
    }

    public void setRecipients(String recipients) {
        this.recipients = recipients;
    }

    public String getSubjectTemplate() {
        return subjectTemplate;
    }

    public void setSubjectTemplate(String subjectTemplate) {
        this.subjectTemplate = subjectTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
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
