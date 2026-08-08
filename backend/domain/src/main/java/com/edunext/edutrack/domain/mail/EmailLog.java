package com.edunext.edutrack.domain.mail;

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
 * One outbound mail — <b>and the queue entry for it</b>. PLAN.md §2.2 replaces
 * BullMQ with this table, so the row is both the outbox item and its delivery
 * record. {@code nextAttemptAt} is what makes it a queue rather than a log: it
 * carries the exponential backoff schedule.
 *
 * <p><b>Two access paths, on purpose.</b> {@code OutboxEnqueuer} inserts over
 * JDBC so enqueue is atomic with the business transaction — a handoff that
 * rolls back cannot leave a phantom mail queued — and so the write side cannot
 * collide with this entity (B-005). D-010's worker claims with
 * {@code FOR UPDATE SKIP LOCKED} and stamps the result. This entity is the read
 * model: the delivery-proof view on a ticket (D-033) and admin screens.
 *
 * <p>The claim query is deliberately <em>not</em> on
 * {@link EmailLogRepository} — a derived finder cannot express
 * {@code SKIP LOCKED}, and offering a near-miss would invite someone to claim
 * without the lock and send the same mail twice.
 *
 * <p>{@code toUserId} is null when the recipient is a client contact rather
 * than a platform user; {@code ticketId} is null for non-ticket mail.
 */
@Entity
@Table(name = "email_log")
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "event_code", nullable = false, length = 40)
    private String eventCode;

    /** The {@code notification_templates} row this was rendered from. */
    @Column(name = "template_id")
    private Long templateId;

    /** Null when the recipient is a client contact, not a user. */
    @Column(name = "to_user_id")
    private Long toUserId;

    @Column(name = "to_email", nullable = false, length = 150)
    private String toEmail;

    @Column(name = "subject", length = 300)
    private String subject;

    /** QUEUED | SENT | BOUNCED | FAILED. */
    @Column(name = "status", nullable = false, length = 15)
    private String status = "QUEUED";

    @Column(name = "provider_msg_id", length = 200)
    private String providerMsgId;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    /**
     * When the worker may next claim this row. Unlike the other defaulted
     * timestamps here it is readable <em>and</em> writable: backoff pushes it
     * forward on each failure, so the worker must be able to set it. A JPA
     * insert has to supply a value — the SQL default only applies to statements
     * that omit the column.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "error_text", columnDefinition = "text")
    private String errorText;

    @Generated(event = EventType.INSERT)
    @Column(name = "queued_at", insertable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

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

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getToUserId() {
        return toUserId;
    }

    public void setToUserId(Long toUserId) {
        this.toUserId = toUserId;
    }

    public String getToEmail() {
        return toEmail;
    }

    public void setToEmail(String toEmail) {
        this.toEmail = toEmail;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProviderMsgId() {
        return providerMsgId;
    }

    public void setProviderMsgId(String providerMsgId) {
        this.providerMsgId = providerMsgId;
    }

    public short getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(short retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getErrorText() {
        return errorText;
    }

    public void setErrorText(String errorText) {
        this.errorText = errorText;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
