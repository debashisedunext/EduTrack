package com.edunext.edutrack.api.upload;

/**
 * B-107 · an object key {@link UploadPipeline} can address.
 *
 * <p>The neutral twin of {@code StorageKey}, and it carries that interface's own
 * rule verbatim: <b>every implementation validates rather than trusts</b>. The
 * value reaches an S3 {@code GetObject} and a presigner, so an implementation
 * that accepts arbitrary strings is a path-traversal hole wearing an interface.
 *
 * <p>{@link #value()} is declared rather than leaning on {@code toString()} for
 * the reason {@code StorageKey} gives: a marker interface would let an
 * implementation inherit {@code ClassName@1b6d3586} and write it into a bucket,
 * which surfaces as an unreadable file weeks later.
 */
public interface UploadKey {

    /** The object key as stored. */
    String value();
}
