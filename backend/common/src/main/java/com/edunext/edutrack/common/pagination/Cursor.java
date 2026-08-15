package com.edunext.edutrack.common.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A-053 · the keyset cursor every paged list encodes, in one place.
 *
 * <p>CONVENTIONS.md §6 requires {@code ?cursor=&limit=} and forbids offset:
 * offset paging over a table being written to skips and repeats rows, and on a
 * ticket list a skipped row is a ticket nobody works on. A cursor names the
 * <em>last row you saw</em> instead of how many you have consumed, so an insert
 * elsewhere in the table cannot shift the boundary.
 *
 * <h2>Why this is shared when four hand-rolled versions already exist</h2>
 *
 * <p>{@code ProjectCursor}, {@code ResourceCursor}, chat's bare {@code Long} and
 * the calendar's meta record were each written separately, and they had already
 * drifted into two different envelope shapes — one of them carrying a
 * {@code totalCount} the others do not. Four encoders is four places for the
 * fetch-one-extra boundary to be wrong, and the two that are wrong are the two
 * nobody has paged past yet.
 *
 * <h2>The objection this design had to answer</h2>
 *
 * <p>{@code ProjectCursor} argues, in its own javadoc, <em>against</em> a shared
 * cursor: "a generic cursor parameterised over a column name is a string from a
 * caller reaching a SQL {@code ORDER BY}". That is correct and it is the reason
 * this type carries <b>values only</b>. There is no column name on the wire, in
 * the encoding, or in this class. Each endpoint fixes its own {@code ORDER BY}
 * in its own code and simply says which value it sorted by; the caller can
 * choose <em>where</em> to resume, never <em>by what</em>. A forged cursor moves
 * the reader within rows {@code ScopeResolver} has already narrowed to them.
 *
 * <h2>Malformed input is the first page, not a 400</h2>
 *
 * <p>Carried over from both existing implementations, which reached it
 * independently: a bookmarked URL holding yesterday's cursor, or a hand-edited
 * one, should show the top of the list rather than an error page. Decoding
 * therefore answers {@code null} for anything it did not issue, and {@code null}
 * means "start at the beginning".
 *
 * @param sortKey the value of the column the endpoint ordered by, as text. The
 *                caller converts it back to whatever type it needs — this class
 *                deliberately does not know whether it is a name, a date or a
 *                number, because knowing would mean carrying the column.
 * @param id      the row id, breaking ties. A keyset over a non-unique column
 *                skips or repeats rows where values collide, and names collide
 *                more than people expect — two projects called "Phase 2", two
 *                tickets reported in the same second.
 */
public record Cursor(String sortKey, long id) {

    /** {@code id|sortKey} — id first, so the split point is unambiguous even when the key contains a separator. */
    private static final char SEPARATOR = '|';

    public Cursor {
        if (sortKey == null) {
            throw new IllegalArgumentException("cursor sortKey must not be null");
        }
    }

    /** URL-safe and unpadded, so it survives a query string without escaping. */
    public String encode() {
        String raw = id + String.valueOf(SEPARATOR) + sortKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return the cursor, or {@code null} for absent, malformed, truncated or
     *         forged input — all of which mean "the first page"
     */
    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }

        int split = raw.indexOf(SEPARATOR);
        if (split <= 0) {
            return null;
        }
        try {
            // Only the first separator counts: the sort key may contain one.
            return new Cursor(raw.substring(split + 1), Long.parseLong(raw.substring(0, split)));
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
