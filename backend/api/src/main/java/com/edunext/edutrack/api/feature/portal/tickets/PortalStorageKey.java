package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.tickets.attachments.StorageKey;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A-127 · a ticket attachment's object key, parsed on the portal's side.
 *
 * <h2>⚠️ This duplicates {@code AttachmentStorageKey.parse}, and that is a
 * decision on the record rather than an oversight</h2>
 *
 * <p>{@code AttachmentStorage.signedDownloadUrl} is public and takes a
 * {@link StorageKey}, which is public — D-053 made it an interface precisely so
 * a second surface could address the same bucket. What is <em>not</em> public is
 * the only thing that can build one for a ticket attachment:
 * {@code AttachmentStorageKey} and its {@code parse} are package-private in
 * Stream C's {@code feature/tickets/attachments/}.
 *
 * <p>Widening that method is a one-word change in Stream C's file and needs
 * Divyansh's sign-off (CLAUDE.md, code ownership). It was raised and the call
 * was to ship the portal now and duplicate here. <b>So this class is temporary
 * and should be deleted the day {@code AttachmentStorageKey.parse} goes
 * public</b>, with {@link PortalTicketService} calling that instead.
 *
 * <p>{@link StorageKey}'s own javadoc warns that "two answers to is this file
 * safe" is the outcome to avoid, and it is right. Three things keep the risk
 * bounded until the duplicate goes:
 *
 * <ul>
 *   <li>{@link #SHAPE} is character-for-character the pattern
 *       {@code AttachmentStorageKey.SHAPE} uses, including the anchors, the
 *       lower-case-hex-only UUID and the single optional {@code -thumb}. A
 *       pattern that drifts is the whole hazard, so it is copied rather than
 *       re-derived.</li>
 *   <li>{@link #belongsTo} is checked by the caller on every row. Even a key
 *       this parser wrongly accepted could only ever address an object under
 *       the ticket the caller is already entitled to read.</li>
 *   <li>{@code PortalStorageKeyTest} feeds it the traversal and absolute-URL
 *       cases by name, so "it validates" is asserted rather than asserted-in-a
 *       -comment.</li>
 * </ul>
 */
record PortalStorageKey(long ticketId, UUID objectId, boolean thumbnail) implements StorageKey {

    /**
     * Copied verbatim from {@code AttachmentStorageKey.SHAPE}.
     *
     * <p>Anchored, lower-case hexadecimal only — the form {@link UUID#toString()}
     * emits — with {@code -thumb} the only thing that may follow. A key this
     * rejects is a key that class did not mint.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^tickets/(\\d+)/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(-thumb)?$");

    /**
     * Parse a key read back out of {@code ticket_attachments}.
     *
     * <p>Validated rather than trusted, though this application wrote it: the
     * value reaches a presigner, so a row that somehow holds {@code ../} or an
     * absolute URL must fail here rather than at a storage client that may or
     * may not normalise it the way we assume.
     *
     * @throws IllegalArgumentException on anything but the exact shape
     */
    static PortalStorageKey parse(String key) {
        var matcher = SHAPE.matcher(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a ticket attachment storage key");
        }
        return new PortalStorageKey(
                Long.parseLong(matcher.group(1)),
                UUID.fromString(matcher.group(2)),
                matcher.group(3) != null);
    }

    /**
     * Whether {@code key} is a well-formed attachment key for {@code ticketId}.
     *
     * <p>The portal's second line, and the one that holds even if the parser
     * above is wrong: a signed URL is only ever minted for an object under the
     * ticket row the caller has already been scoped to. Answers false rather
     * than throwing, because a malformed key is a row to skip and not a request
     * to fail — one bad row must not blank a customer's whole file list.
     */
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
        return "tickets/" + ticketId + "/" + objectId + (thumbnail ? "-thumb" : "");
    }
}
