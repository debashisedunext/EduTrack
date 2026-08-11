package com.edunext.edutrack.api.feature.masters.resources;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * B-010 · a keyset cursor over {@code (full_name, id)}.
 *
 * <p><b>A real cursor, not a base64-wrapped offset.</b> CONVENTIONS.md §6 forbids
 * offset paging because a row inserted while somebody is on page 2 shifts one
 * row to page 3 unseen — on a resource directory that is a person who silently
 * does not appear. This encodes the last row of the page instead, so the next
 * page resumes from a position in the sort order rather than from a count.
 *
 * <p>The sort key is {@code (full_name, id)} and not {@code full_name} alone.
 * Names are not unique — two people called Priya Sharma are a normal thing for
 * an organisation to contain — and a keyset over a non-unique column either
 * skips or repeats the duplicates. {@code id} breaks the tie.
 *
 * <p>The encoding is opaque to the client by contract, which is the whole point
 * of {@code meta.nextCursor}: it may change without a version bump. A malformed
 * or stale one is treated as "start from the beginning" rather than as an error,
 * because a bookmarked URL carrying yesterday's cursor should show the first
 * page, not a 400.
 */
record ResourceCursor(String fullName, long id) {

    /** {@code id|fullName} — id first, so the split point is unambiguous. */
    private static final char SEPARATOR = '|';

    String encode() {
        String raw = id + String.valueOf(SEPARATOR) + fullName;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return the decoded cursor, or {@code null} for absent, malformed or
     *         truncated input — all of which mean "the first page".
     */
    static ResourceCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }

        int split = raw.indexOf(SEPARATOR);
        if (split <= 0) {
            return null;
        }
        try {
            // The name may itself contain a separator; only the first one counts.
            return new ResourceCursor(raw.substring(split + 1), Long.parseLong(raw.substring(0, split)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
