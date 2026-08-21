package com.edunext.edutrack.api.feature.tickets.attachments;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * C-025 · the object store, as this feature needs it — blueprint §4B.4.
 *
 * <p>An interface rather than an {@code S3Client} injected directly, for one
 * reason that is not "testability": <b>there must be no method here that can
 * produce a public address.</b> {@link #signedDownloadUrl} takes a TTL it cannot
 * be called without, and nothing on this interface returns a bucket URL, sets an
 * ACL or makes an object readable. A caller that wanted to publish an attachment
 * would have to add a method to this file, in a diff, where somebody sees it —
 * which is a materially better position than "the S3 client can do anything and
 * we agreed not to".
 *
 * <p>The test double is then a consequence rather than the purpose: no
 * Testcontainers MinIO is needed to prove that an INFECTED file is never
 * presigned.
 */
public interface AttachmentStorage {

    /**
     * Store the cleaned bytes under {@code key}.
     *
     * <p>The content type is the <em>sniffed</em> one, never the client's
     * declaration — see {@link AttachmentTypePolicy}. It is stored on the object
     * because a presigned GET serves it back, and serving back a caller-supplied
     * {@code text/html} is stored XSS against whoever opens the link.
     */
    void put(StorageKey key, byte[] content, String contentType);

    /**
     * A short-lived, signed GET URL — the <em>only</em> way an attachment is
     * ever read (§4B.4: "served only through short-lived signed URLs, never a
     * public bucket").
     *
     * @param fileName the name to put in {@code Content-Disposition}, so the
     *                 browser saves the user's filename rather than a UUID
     * @param ttl      how long the URL is good for. Minutes, not hours: the URL
     *                 ends up in a browser history, a chat paste and a proxy log,
     *                 and every one of those is a copy of a credential
     */
    URI signedDownloadUrl(StorageKey key, String fileName, String contentType, Duration ttl);

    /**
     * Remove the object.
     *
     * <p>Used by the scanner when a file comes back INFECTED, and by C-028's
     * 15-minute delete window. The database row survives in both cases — the
     * tombstone is the record that something was attached and removed.
     *
     * <p>Idempotent, and returns nothing: an object that was already gone is the
     * outcome both callers wanted, and reporting "there was nothing to delete"
     * would only invite a branch that treats it as a failure.
     */
    void delete(StorageKey key);

    /** The stored bytes, for the AV scanner. Empty when the object is gone. */
    Optional<byte[]> read(StorageKey key);
}
