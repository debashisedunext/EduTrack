package com.edunext.edutrack.api.upload;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * B-107 · the upload pipeline as a port — one answer to "is this file safe",
 * reachable from a module that may not name the package it lives in.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Two rules in this repository point in opposite directions and both are
 * right:
 *
 * <ul>
 *   <li><b>One answer to "is this file safe."</b> D-053 wrote it down when chat
 *       gained file share, and C-025's own classes are built to be reused —
 *       {@code AttachmentTypePolicy}, {@code ImageMetadataStripper} and
 *       {@code AttachmentScanner} are separate, injectable beans precisely so
 *       nobody writes a second pipeline. A second allow-list is a vulnerability
 *       the day one of them gains a type the other has not.</li>
 *   <li><b>A-115: onboarding and ticketing stay separable.</b>
 *       {@code ObModuleSeparationTest} refuses any dependency from
 *       {@code api.feature.onboarding} on {@code api.feature.tickets}, because
 *       "a type shared across the boundary is how a route ends up shared" — and
 *       it says in its own text that anything genuinely common belongs outside
 *       both feature packages.</li>
 * </ul>
 *
 * <p>Chat could take the shortcut because it is not on either side of that
 * boundary. Onboarding cannot. So the <em>contract</em> of the pipeline moves
 * to a neutral package and the onboarding module depends on this, while the
 * implementation stays exactly where Stream C owns it —
 * {@link UploadPipelineConfig} is a dozen lines of delegation and changes no
 * behaviour, no bean and no file in {@code feature/tickets/attachments}.
 *
 * <p><b>This is the smaller half of the right fix, and the larger half is named
 * rather than left implied.</b> The durable arrangement is the one B-036
 * already performed on the bucket: {@code AttachmentStorageConfig}'s own
 * javadoc records the S3 clients moving to {@code api/storage/} "when the
 * second one arrives", to avoid an import across a feature boundary. The safety
 * beans have now reached their third caller and belong in a shared home for the
 * same reason. That is a move of a dozen security-relevant classes through two
 * other streams' code, which is Stream C's call to make and not B-107's to take
 * — and once it happens, this package is deleted and the imports point at the
 * new home directly.
 *
 * <h2>One port, not four</h2>
 *
 * <p>The alternative was a separate interface per bean, which reads tidier and
 * is worse here: a caller needs all of them for a single upload, and four ports
 * would let a future feature take the store without the scanner. What makes a
 * file safe is the <em>sequence</em>, and a port that hands out its steps
 * individually invites somebody to skip one.
 */
public interface UploadPipeline {

    /**
     * What survived vetting.
     *
     * @param mediaType the <b>sniffed</b> type, never the caller's declaration.
     *                  It is stored on the object and served back on a presigned
     *                  GET, and serving back a caller-supplied {@code text/html}
     *                  is stored XSS against whoever opens the link
     * @param content   the bytes as they will be stored — metadata stripped, so
     *                  a photograph's GPS coordinates do not leave with it
     */
    record Vetted(String mediaType, byte[] content) {
    }

    /** The AV outcome. {@link #UNKNOWN} is never widened into {@link #CLEAN}. */
    enum Verdict {
        CLEAN,
        INFECTED,
        /**
         * No verdict was obtained — every outage, timeout and misconfiguration
         * arrives here. The file stays unreadable.
         */
        UNKNOWN
    }

    /**
     * Reconcile the declared name against the bytes, then strip metadata.
     *
     * <p>The two steps are one operation on purpose: stripping is driven by the
     * family the sniffer agreed on, so a caller that could do one without the
     * other could store an unstripped file it had not identified.
     *
     * @throws UnsupportedUploadTypeException the extension is off the allow-list,
     *         the bytes are unrecognisable, or the two disagree — 415 in every case
     */
    Vetted vet(String fileName, byte[] content);

    /** Store the vetted bytes under {@code key}, with the sniffed type on the object. */
    void put(UploadKey key, byte[] content, String mediaType);

    /**
     * A short-lived, signed GET URL — the only way a stored file is ever read.
     * Never a public bucket path.
     */
    URI signedDownloadUrl(UploadKey key, String fileName, String mediaType, Duration ttl);

    /** Remove the object. Idempotent: an object already gone is the outcome the caller wanted. */
    void delete(UploadKey key);

    /** The stored bytes, for the scanner. Empty when the object is gone. */
    Optional<byte[]> read(UploadKey key);

    /** The AV verdict. The same scanner, and therefore the same answer, as every other surface. */
    Verdict scan(String fileName, byte[] content);

    /** The per-file cap in force, shared with every other upload surface. */
    long maxFileBytes();

    /**
     * How long a signed URL is good for. Minutes, not hours: the URL ends up in
     * a browser history, a chat paste and a proxy log, and every one of those is
     * a copy of a credential.
     */
    Duration signedUrlTtl();

    /**
     * The window in which somebody removing their own file leaves no visible
     * trace. §4B.4's fifteen minutes.
     */
    Duration removalWindow();

    /**
     * Whether an inconclusive scan may be treated as clean. <b>False everywhere
     * but local development</b>, and the configuration refuses to start
     * otherwise: the opposite default would serve every upload unscanned for the
     * whole duration of a scanner outage with nothing about the product looking
     * different while it happened.
     */
    boolean scanFailOpen();
}
