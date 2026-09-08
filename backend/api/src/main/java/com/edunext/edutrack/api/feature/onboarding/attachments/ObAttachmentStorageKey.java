package com.edunext.edutrack.api.feature.onboarding.attachments;

import com.edunext.edutrack.api.upload.UploadKey;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * B-107 · {@code onboarding/{owner}/{ownerId}/{uuid}} — where an onboarding
 * file lives in the bucket.
 *
 * <p>A third sibling of {@code AttachmentStorageKey} and
 * {@code ChatAttachmentStorageKey} rather than a widening of either, on the
 * reasoning D-053 wrote down when it made the second: those records' shapes,
 * their {@code parse} and their error messages are pinned by C-025's own tests,
 * and reshaping a security-relevant class in another stream's package to add a
 * namespace is a larger change than writing the namespace out. <b>The safety
 * decisions are not duplicated</b> — what a file may be
 * ({@code AttachmentTypePolicy}), what is stripped from it
 * ({@code ImageMetadataStripper}) and whether it is scanned
 * ({@code AttachmentScanner}) are the same beans tickets and chat call. Only the
 * naming is written here.
 *
 * <h2>The owner is in the key, and that is the point</h2>
 *
 * <p>{@code ob_attachments} is polymorphic within the module (A-102), so a key
 * carrying only an id could not tell client 7's document from step 7's. The
 * owner segment makes the four arms disjoint by construction: {@link #belongsTo}
 * answers false for a well-formed key of the wrong arm, so a row whose
 * {@code storage_key} had been edited to name another owner's object costs its
 * download URL rather than serving it.
 *
 * <h2>The rules it inherits, and why each matters</h2>
 *
 * <ul>
 *   <li><b>{@link UUID#randomUUID()} rather than a sequence.</b> The key is the
 *       only thing between an over-permissive bucket and every file in the
 *       system, and a sequence makes enumeration free.</li>
 *   <li><b>Parsed, not trusted, even though this application wrote it.</b> The
 *       value reaches an S3 {@code GetObject} and a presigner, so a row that
 *       somehow holds {@code ../} or an absolute URL has to fail at an anchored
 *       {@link Pattern} here rather than at a storage client that may or may not
 *       normalise it the way we assume.</li>
 *   <li><b>Namespaced under {@code onboarding/}, never {@code tickets/} or
 *       {@code chat/}.</b> The three namespaces are disjoint by construction and
 *       no {@code parse} accepts another's keys. Plan §2's separability
 *       requirement reaching the bucket: nothing in this module can address a
 *       ticket's object.</li>
 *   <li><b>No {@code -thumb} variant.</b> C-026's reduction is a ticket-gallery
 *       feature and nothing in this module draws a gallery — OB-05 lists
 *       documents by name. Chat made the same call. If one is ever wanted, it is
 *       a variant here and {@code ThumbnailTask} is the bean to call; leaving the
 *       suffix unparseable until then means a stray {@code -thumb} key is a
 *       refusal rather than an object nobody can account for.</li>
 * </ul>
 */
record ObAttachmentStorageKey(ObAttachmentOwner owner, long ownerId, UUID objectId)
        implements UploadKey {

    /** Anchored, lower-case hexadecimal only, and the owner segment is a closed set. */
    private static final Pattern SHAPE = Pattern.compile(
            "^onboarding/([a-z-]+)/(\\d+)/"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    static ObAttachmentStorageKey mint(ObAttachmentOwner owner, long ownerId) {
        if (owner == null) {
            throw new IllegalArgumentException("an attachment key needs an owner arm");
        }
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive, was " + ownerId);
        }
        return new ObAttachmentStorageKey(owner, ownerId, UUID.randomUUID());
    }

    static ObAttachmentStorageKey parse(String key) {
        var matcher = SHAPE.matcher(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not an onboarding attachment storage key");
        }
        ObAttachmentOwner owner = ObAttachmentOwner.bySegment(matcher.group(1));
        if (owner == null) {
            throw new IllegalArgumentException("not an onboarding attachment storage key");
        }
        return new ObAttachmentStorageKey(owner, Long.parseLong(matcher.group(2)),
                UUID.fromString(matcher.group(3)));
    }

    /**
     * Whether {@code key} is a well-formed onboarding key for exactly this owner
     * and id.
     *
     * <p>Answers false rather than throwing, so a row with a corrupted key costs
     * its own download URL and not the whole listing —
     * {@code AttachmentService.thumbnailUrlFor}'s rule, which is the only reason
     * a validation method returns a boolean anywhere in this pipeline.
     */
    static boolean belongsTo(String key, ObAttachmentOwner owner, long ownerId) {
        try {
            ObAttachmentStorageKey parsed = parse(key);
            return parsed.owner() == owner && parsed.ownerId() == ownerId;
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
        return "onboarding/" + owner.segment() + "/" + ownerId + "/" + objectId;
    }
}
