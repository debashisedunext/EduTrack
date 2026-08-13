package com.edunext.edutrack.api.feature.masters.projects;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * B-016 · a keyset cursor over {@code (name, id)}.
 *
 * <p>A real cursor, not a base64-wrapped offset — CONVENTIONS.md §6, and the
 * same decision {@code ResourceCursor} documents at length for the resource
 * grid. Deliberately a second small record rather than a shared one: the two
 * differ in their sort key, and a generic cursor parameterised over a column
 * name is a string from a caller reaching a SQL {@code ORDER BY}.
 *
 * <p>The tiebreaker matters less here than it does for people — project names
 * are usually distinct — but it is not free of duplicates either ("Migration",
 * "Phase 2"), and a keyset over a non-unique column skips or repeats them.
 *
 * <p>Malformed, stale or truncated input means "the first page", not a 400: a
 * bookmarked URL carrying yesterday's cursor should show the top of the list.
 */
record ProjectCursor(String name, long id) {

    /** {@code id|name} — id first, so the split point is unambiguous. */
    private static final char SEPARATOR = '|';

    String encode() {
        String raw = id + String.valueOf(SEPARATOR) + name;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static ProjectCursor decode(String encoded) {
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
            return new ProjectCursor(raw.substring(split + 1), Long.parseLong(raw.substring(0, split)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
