package com.edunext.edutrack.api.feature.onboarding.attachments;

/**
 * B-107 · which of {@code ob_attachments}' owner arms a file hangs off.
 *
 * <p>A-102 chose <b>one nullable foreign key per owner kind plus a CHECK that
 * exactly one is set</b> over the usual {@code owner_type}/{@code owner_id}
 * pair, so that every row's owner is a real row of a real table. This enum is
 * that choice reaching Java: it names the arms the migration declares, and it
 * is the only place that knows which column each one writes and which segment
 * each one takes in a storage key.
 *
 * <p><b>It is not a discriminator stored anywhere.</b> Nothing persists this
 * value — the owner of a row is which column is non-null, exactly as the CHECK
 * says, and adding a column for it would be the unconstrained {@code owner_type}
 * the schema deliberately refused. It exists so that a caller naming an owner
 * cannot mix up the arms, and so that a key minted for a client can never
 * address a sign-off's object.
 *
 * <h2>Four arms, one route surface</h2>
 *
 * <p>B-107 builds the pipeline and the <b>client</b> routes only. The other
 * three arms are declared here rather than added one at a time because the
 * storage key and the AV plumbing have to know the whole namespace to keep it
 * disjoint — a key shape that learned {@code steps/} later would have to be
 * reparsed against rows already written under the older shape. The tasks that
 * own the remaining route surfaces are named in the migration header: C-121 the
 * step upload, B-116 the sign-off evidence, B-125 the prerequisite submission.
 */
public enum ObAttachmentOwner {

    /** {@code ob_attachments.ob_client_id} — a document filed against the client record (OB-05). */
    CLIENT("clients"),

    /** {@code ob_attachments.step_id} — a service's deliverables and the step document checklist. */
    STEP("steps"),

    /** {@code ob_attachments.signoff_id} — the evidence behind an acceptance. */
    SIGNOFF("signoffs"),

    /**
     * {@code ob_attachments.prereq_template_task_id} — B-124's admin reference
     * documents on the org-wide prerequisites master (OB-14).
     */
    PREREQ_TEMPLATE_TASK("prereq-template-tasks");

    private final String segment;

    ObAttachmentOwner(String segment) {
        this.segment = segment;
    }

    /**
     * The path segment this owner takes in an {@link ObAttachmentStorageKey}.
     *
     * <p>Spelled out rather than derived from {@link #name()}: the enum constant
     * is Java's vocabulary and the segment is written into object keys that
     * outlive it, so renaming a constant must not silently orphan every object
     * stored under the old spelling.
     */
    String segment() {
        return segment;
    }

    /** The owner whose segment is {@code segment}, or empty — {@code parse}'s only lookup. */
    static ObAttachmentOwner bySegment(String segment) {
        for (ObAttachmentOwner owner : values()) {
            if (owner.segment.equals(segment)) {
                return owner;
            }
        }
        return null;
    }
}
