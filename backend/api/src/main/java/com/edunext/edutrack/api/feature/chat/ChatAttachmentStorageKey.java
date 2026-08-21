package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.feature.tickets.attachments.StorageKey;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * D-053 · {@code chat/{threadId}/{uuid}} — where a shared file lives.
 *
 * <p>A sibling of {@code AttachmentStorageKey} rather than a widening of it.
 * That record's shape, its {@code parse}, its {@code belongsTo} and its error
 * message are all pinned by C-025's own tests, and reshaping a
 * security-relevant class in another stream's package to add a namespace is a
 * larger change than writing the second namespace out. The <em>safety</em>
 * decisions — what a file may be, what is stripped from it, whether it is
 * scanned — are shared beans and are not duplicated here; only the naming is.
 *
 * <h2>The rules it inherits, and why each matters</h2>
 *
 * <ul>
 *   <li><b>{@link UUID#randomUUID()} rather than a sequence.</b> The key is
 *       the only thing between a leaked or over-permissive bucket and every
 *       file in the system, and a sequence makes enumeration free.</li>
 *   <li><b>Parsed, not trusted, even though this application wrote it.</b> The
 *       value reaches an S3 {@code GetObject} and a presigner, so a row that
 *       somehow holds {@code ../} or an absolute URL has to fail at an
 *       anchored {@link Pattern} here rather than at a storage client that may
 *       or may not normalise it the way we assume.</li>
 *   <li><b>Namespaced under {@code chat/}, never {@code tickets/}.</b> The two
 *       namespaces are disjoint by construction, so a chat key can never
 *       address a ticket's object and neither {@code parse} accepts the
 *       other's keys.</li>
 * </ul>
 */
record ChatAttachmentStorageKey(long threadId, UUID objectId, Variant variant) implements StorageKey {

    /** The two objects a shared file can have — the original and its thumbnail. */
    enum Variant {
        ORIGINAL(""),
        THUMBNAIL("-thumb");

        private final String suffix;

        Variant(String suffix) {
            this.suffix = suffix;
        }
    }

    /** Anchored, lower-case hexadecimal only, and {@code -thumb} is the only permitted tail. */
    private static final Pattern SHAPE = Pattern.compile(
            "^chat/(\\d+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(-thumb)?$");

    static ChatAttachmentStorageKey mint(long threadId) {
        if (threadId <= 0) {
            throw new IllegalArgumentException("threadId must be positive, was " + threadId);
        }
        return new ChatAttachmentStorageKey(threadId, UUID.randomUUID(), Variant.ORIGINAL);
    }

    /** Idempotent, so a caller already holding the reduced object cannot build {@code -thumb-thumb}. */
    ChatAttachmentStorageKey thumbnail() {
        return variant == Variant.THUMBNAIL ? this
                : new ChatAttachmentStorageKey(threadId, objectId, Variant.THUMBNAIL);
    }

    static ChatAttachmentStorageKey parse(String key) {
        var matcher = SHAPE.matcher(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a chat attachment storage key");
        }
        return new ChatAttachmentStorageKey(
                Long.parseLong(matcher.group(1)),
                UUID.fromString(matcher.group(2)),
                matcher.group(3) == null ? Variant.ORIGINAL : Variant.THUMBNAIL);
    }

    /** Whether {@code key} is a well-formed chat key for {@code threadId}. */
    static boolean belongsTo(String key, long threadId) {
        try {
            return parse(key).threadId() == threadId;
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
        return "chat/" + threadId + "/" + objectId + variant.suffix;
    }
}
