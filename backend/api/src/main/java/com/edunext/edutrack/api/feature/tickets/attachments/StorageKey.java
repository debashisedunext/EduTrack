package com.edunext.edutrack.api.feature.tickets.attachments;

/**
 * An object key {@link AttachmentStorage} can address.
 *
 * <h2>Why this exists — D-053</h2>
 *
 * <p>Chat gained file and image share, and §7.6 keeps chat as project
 * evidence, so a file posted into a thread has to pass the <em>same</em>
 * safety checks a ticket attachment does: MIME sniffing against the declared
 * name ({@link AttachmentTypePolicy}), EXIF stripping
 * ({@link ImageMetadataStripper}) and an AV scan ({@link AttachmentScanner}).
 *
 * <p><b>Two answers to "is this file safe" is the outcome worth avoiding</b>,
 * and D-053's own entry has said so since it was written. Those three
 * components are already separate, injectable beans, so reusing them is a
 * constructor argument rather than a second pipeline — the only thing that was
 * ticket-shaped and in the way was the storage key.
 *
 * <p>So the key becomes an interface and the storage takes it. The
 * implementations are unchanged: {@code S3AttachmentStorage} has only ever
 * used {@code key.toString()}, and its four methods behave identically. What
 * changes is that {@code chat/{threadId}/{uuid}} is now expressible alongside
 * {@code tickets/{ticketId}/{uuid}}.
 *
 * <h2>Every implementation validates rather than trusts</h2>
 *
 * <p>{@link AttachmentStorageKey#parse} explains the rule and it holds for any
 * implementation: the value reaches an S3 {@code GetObject} and a presigner, so
 * a row that somehow holds {@code ../} or an absolute URL must fail at an
 * anchored {@link java.util.regex.Pattern} here rather than at a storage client
 * that may or may not normalise it the way we assume. An implementation that
 * accepts arbitrary strings is a path-traversal hole wearing an interface.
 */
public interface StorageKey {

    /**
     * The object key as stored, which is what {@code toString()} has always
     * returned for {@link AttachmentStorageKey}.
     *
     * <p>Declared explicitly rather than leaning on {@code Object.toString()}:
     * a marker interface would let an implementation inherit the default
     * {@code ClassName@1b6d3586} and write it into a bucket, which is exactly
     * the kind of failure that surfaces as an unreadable file weeks later.
     */
    String value();
}
