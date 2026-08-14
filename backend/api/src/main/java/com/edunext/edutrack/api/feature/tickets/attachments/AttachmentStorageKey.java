package com.edunext.edutrack.api.feature.tickets.attachments;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * C-025 · {@code tickets/{ticket_id}/{uuid}} — blueprint §4B.4's object key,
 * and nothing else.
 *
 * <h2>The name the user gave the file is not in the key</h2>
 *
 * <p>That is the whole design and it is worth stating, because "keep the
 * filename in the key so the bucket is browsable" is the request that will be
 * made. Four things go wrong with it:
 *
 * <ul>
 *   <li>The name is attacker-controlled. {@code ../../config/secrets} is a path
 *       traversal against any storage layer that treats keys as paths, and MinIO
 *       running on a filesystem backend is one.</li>
 *   <li>Names collide. Two people attach {@code screenshot.png} to the same
 *       ticket within a minute — which is exactly what C-024's clipboard paste
 *       makes routine — and one silently overwrites the other.</li>
 *   <li>Names are personal data. {@code Q3-redundancy-list-FINAL.xlsx} in an
 *       object key is a disclosure in every log line and metric label that ever
 *       carries the key.</li>
 *   <li>It would make the key guessable, which matters more than it looks:
 *       signed URLs are the only way in, and an unguessable key means a
 *       misconfigured bucket policy is a second failure rather than the first.</li>
 * </ul>
 *
 * <p>The display name lives in {@code ticket_attachments.file_name}, where it is
 * data rather than an address, and is re-attached on download through the signed
 * URL's {@code Content-Disposition}.
 *
 * <h2>Random, not derived</h2>
 *
 * <p>A content hash would deduplicate, and is rejected: identical bytes attached
 * to two tickets would share one object, so C-028's delete on one ticket would
 * silently empty the attachment on the other, and the storage key would become a
 * cross-ticket oracle — "does this exact file already exist somewhere I cannot
 * see" answerable by anyone who can upload. A type 4 UUID answers no questions.
 */
record AttachmentStorageKey(long ticketId, UUID objectId) {

    /**
     * The one true shape. Anchored, and lower-case hexadecimal only — the same
     * form {@link UUID#toString()} emits, so a key this pattern rejects is a key
     * this class did not mint.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^tickets/(\\d+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    /**
     * Mint a key for a new object.
     *
     * <p>{@link UUID#randomUUID()} rather than a sequence: the key is the only
     * thing between a leaked or over-permissive bucket and every attachment in
     * the system, and a sequence makes enumeration free.
     */
    static AttachmentStorageKey mint(long ticketId) {
        if (ticketId <= 0) {
            throw new IllegalArgumentException("ticketId must be positive, was " + ticketId);
        }
        return new AttachmentStorageKey(ticketId, UUID.randomUUID());
    }

    /**
     * Parse a key read back out of the database.
     *
     * <p>Validated rather than trusted, even though this application wrote it.
     * The value reaches an S3 {@code GetObject} and a presigner, so a row that
     * somehow holds {@code ../} or an absolute URL must fail here — at a
     * {@code Pattern} — rather than at a storage client that may or may not
     * normalise it the way we assume.
     */
    static AttachmentStorageKey parse(String key) {
        var matcher = SHAPE.matcher(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a ticket attachment storage key");
        }
        return new AttachmentStorageKey(Long.parseLong(matcher.group(1)), UUID.fromString(matcher.group(2)));
    }

    /** Whether {@code key} is a well-formed attachment key for {@code ticketId}. */
    static boolean belongsTo(String key, long ticketId) {
        try {
            return parse(key).ticketId() == ticketId;
        } catch (IllegalArgumentException notOurs) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "tickets/" + ticketId + "/" + objectId;
    }
}
