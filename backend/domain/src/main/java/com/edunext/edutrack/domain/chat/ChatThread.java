package com.edunext.edutrack.domain.chat;

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
 * A conversation — M7. It hangs off a ticket, or stands alone as a direct
 * message ({@code ticketId} null).
 *
 * <p>{@code threadType} {@code ASK_STATUS} is the blueprint §6 "Ask Status"
 * flow: a manager clicks the button, a thread opens, and the assignee gets both
 * a bell entry and a chat message. It is a distinct type rather than a plain
 * {@code TICKET} thread because the flow is reported on.
 */
@Entity
@Table(name = "chat_threads")
public class ChatThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a standalone DM. */
    @Column(name = "ticket_id")
    private Long ticketId;

    /**
     * The project channel anchor (D-050). Null unless
     * {@code threadType = 'PROJECT'}.
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * TICKET | DIRECT | ASK_STATUS | PROJECT.
     *
     * <p><b>{@code ck_chat_threads_one_anchor} makes this a discriminator, not
     * a label.</b> A thread hangs off exactly one thing: PROJECT needs
     * {@code projectId} and no {@code ticketId}, TICKET and ASK_STATUS the
     * reverse, DIRECT neither. Setting the pair inconsistently is rejected by
     * the database, and deliberately so — a project channel carrying a ticket
     * id would be delivered to the wrong STOMP destination, which is a
     * disclosure rather than a display bug.
     */
    @Column(name = "thread_type", nullable = false, length = 20)
    private String threadType = "TICKET";

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "created_by")
    private Long createdBy;

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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getThreadType() {
        return threadType;
    }

    public void setThreadType(String threadType) {
        this.threadType = threadType;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
