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
 * One delivered in-app notification — the bell.
 *
 * <p><b>Two access paths, on purpose.</b> {@link NotificationWriter} inserts
 * these over JDBC because both {@code api} (request handling) and
 * {@code worker} (the scanners) raise notifications and neither module depends
 * on the other. This entity is the read side — the bell list and badge (D-032)
 * and anything that needs a mapped row. Writers should keep going through
 * {@code NotificationWriter}; adding a second insert path is how the two drift.
 *
 * <p>{@code ix_notifications_unread (user_id, is_read, created_at)} is the hot
 * path: the badge count runs on every page load for every user, so
 * {@code countByUserIdAndIsReadFalse} must stay on that index.
 *
 * <p>{@code ticketId} is nullable — a daily digest or a system announcement
 * belongs to no single ticket.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Scalar rather than {@code @ManyToOne}: recipient and ticket are scoping
     * keys, not ownership (package-info). Mapping them as associations would
     * hang a lazy proxy off every row the bell list renders.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "event_code", nullable = false, length = 60)
    private String eventCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    /** Deep link to the ticket or screen the notification is about. */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "read_at")
    private Instant readAt;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public void setLinkUrl(String linkUrl) {
        this.linkUrl = linkUrl;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        this.isRead = read;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
