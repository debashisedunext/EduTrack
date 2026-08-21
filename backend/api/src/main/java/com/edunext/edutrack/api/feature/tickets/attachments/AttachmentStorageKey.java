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
 *
 * <h2>C-026's thumbnail is the same key with a suffix</h2>
 *
 * <p>A thumbnail is a second object, so it needs a second key, and it is
 * <em>derived</em> from the original's rather than minted independently — one
 * random component per attachment, not two. That is what makes
 * {@link #thumbnail()} total: every attachment has a thumbnail key whether or not
 * an object has been written under it, so nothing has to store a second key to
 * find the first, and C-028's delete can remove both from the one column it
 * already reads.
 *
 * <p>It does mean that holding the original's key hands you the thumbnail's. That
 * costs nothing: a caller in possession of the original key can already reach the
 * larger, unreduced file, and neither key is an address on its own — the bucket is
 * private and a signature is still required.
 */
record AttachmentStorageKey(long ticketId, UUID objectId, Variant variant) implements StorageKey {

    /**
     * Which of the two objects an attachment can have.
     *
     * <p>An enum rather than a boolean because the suffix belongs with the name
     * of the thing it marks, and because a third variant — C-060 has been
     * mentioned wanting a larger preview — should be a constant here rather than
     * a second flag threaded through every signature.
     */
    enum Variant {
        ORIGINAL(""),
        THUMBNAIL("-thumb");

        private final String suffix;

        Variant(String suffix) {
            this.suffix = suffix;
        }
    }

    /**
     * The one true shape. Anchored, and lower-case hexadecimal only — the same
     * form {@link UUID#toString()} emits, so a key this pattern rejects is a key
     * this class did not mint.
     *
     * <p>The optional {@code -thumb} group is the <em>only</em> thing that may
     * follow the UUID. Anything else appended is still refused, which is the
     * property {@code anythingOtherThanTheExactShapeIsRefused} pins.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^tickets/(\\d+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(-thumb)?$");

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
        return new AttachmentStorageKey(ticketId, UUID.randomUUID(), Variant.ORIGINAL);
    }

    /**
     * This attachment's thumbnail key — C-026.
     *
     * <p>Idempotent: the thumbnail of a thumbnail is itself, so a caller that has
     * already narrowed to the reduced object cannot accidentally build
     * {@code …-thumb-thumb} and store a third one.
     */
    AttachmentStorageKey thumbnail() {
        return variant == Variant.THUMBNAIL ? this : new AttachmentStorageKey(ticketId, objectId, Variant.THUMBNAIL);
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
        return new AttachmentStorageKey(
                Long.parseLong(matcher.group(1)),
                UUID.fromString(matcher.group(2)),
                matcher.group(3) == null ? Variant.ORIGINAL : Variant.THUMBNAIL);
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
    public String value() {
        return toString();
    }

    @Override
    public String toString() {
        return "tickets/" + ticketId + "/" + objectId + variant.suffix;
    }
}
