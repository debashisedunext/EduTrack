package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * B-125 · one message on a prerequisite task's thread — append-only, and
 * written by either principal.
 *
 * <p>Two author columns, exactly one set, the shape
 * {@code ob_step_communications} already uses: a staff user and a client
 * contact are rows in different tables.
 *
 * <p><b>Append-only but not hash-chained</b>, where {@link ObPrereqHistory}
 * beside it is both. The same distinction {@code ob_step_communications} and
 * {@code ob_step_history} already draw: the <em>state changes</em> are the
 * record that has to be provably untampered, and the conversation is
 * append-only because it is a conversation. A return's mandatory comment is
 * written to both — the chain gets the reason, this gets the message — so the
 * fact a dispute turns on is chained either way.
 *
 * <p>{@link #isSystem} marks a comment a transition wrote rather than a
 * person typed. CP-04 renders those as events, and it is also what stops a
 * client appearing to have been answered by a string the server composed.
 */
@Entity
@Immutable
@Table(name = "ob_prereq_comments")
public class ObPrereqComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prereq_task_id", nullable = false)
    private Long prereqTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 10)
    private ObPrereqActorType authorType;

    @Column(name = "author_user_id")
    private Long authorUserId;

    @Column(name = "author_contact_id")
    private Long authorContactId;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPrereqTaskId() {
        return prereqTaskId;
    }

    public void setPrereqTaskId(Long prereqTaskId) {
        this.prereqTaskId = prereqTaskId;
    }

    public ObPrereqActorType getAuthorType() {
        return authorType;
    }

    public void setAuthorType(ObPrereqActorType authorType) {
        this.authorType = authorType;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(Long authorUserId) {
        this.authorUserId = authorUserId;
    }

    public Long getAuthorContactId() {
        return authorContactId;
    }

    public void setAuthorContactId(Long authorContactId) {
        this.authorContactId = authorContactId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean system) {
        isSystem = system;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
