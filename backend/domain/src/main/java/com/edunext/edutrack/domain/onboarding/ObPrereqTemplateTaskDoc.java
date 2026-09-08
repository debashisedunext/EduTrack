package com.edunext.edutrack.domain.onboarding;

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
 * B-124 · an admin-attached reference document on a master task (OB-14),
 * shown <b>to the client</b> on CP-04 — a sample filled form, a specimen
 * letter, the format a data extract has to arrive in.
 *
 * <p>Plan §4 calls these "admin-attached reference documents" and they are
 * the {@link ObAttachmentKind#REFERENCE} half of that table's vocabulary.
 * What the client sends back is a {@code SUBMISSION} and hangs off the
 * instance task (B-125), never off this.
 *
 * <h2>Why this row exists beside the attachment</h2>
 *
 * <p>{@code ObAttachment} already names its owning task, so the ownership
 * is expressible there alone. What it cannot carry is {@link #label} — the
 * admin's caption for the document ("Specimen board resolution"), which is
 * not the uploaded {@code fileName}. A caption belongs to the listing
 * rather than to the bytes: replacing the file should not silently rename
 * the entry the client reads, and the same file legitimately appears under
 * two tasks with two captions.
 *
 * <p><b>{@link #templateTaskId} and the attachment's own
 * {@code prereqTemplateTaskId} are not the same fact.</b> The attachment's
 * owner records where a file was first uploaded; this row records where it
 * is listed. {@code ObPrereqTemplateService#addTaskDoc} requires them to
 * agree, because a fresh upload is always uploaded against the task it is
 * for — and {@code beginRevision} deliberately breaks the tie, cloning the
 * caption onto the new draft's task while the file stays owned by the
 * version it was uploaded against rather than being duplicated in storage.
 */
@Entity
@Table(name = "ob_prereq_template_task_docs")
public class ObPrereqTemplateTaskDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_task_id", nullable = false)
    private Long templateTaskId;

    /** An {@code ob_attachments} row with {@code kind = REFERENCE}. */
    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    /** The admin's caption, not the file name. See the class javadoc. */
    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "sequence", nullable = false)
    private int sequence;

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

    public Long getTemplateTaskId() {
        return templateTaskId;
    }

    public void setTemplateTaskId(Long templateTaskId) {
        this.templateTaskId = templateTaskId;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(Long attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
