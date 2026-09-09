package com.edunext.edutrack.api.feature.onboarding.dashboard;

/**
 * B-127 · {@code ?cursor=} was present and non-blank but did not decode as a
 * {@link com.edunext.edutrack.common.pagination.Cursor} this route issued.
 *
 * <p>Deliberately <b>not</b> {@code ObClientReadRepository}'s "malformed
 * means the first page" rule. This route's own contract text names the
 * stricter answer explicitly — "a cursor that did not come from
 * {@code meta.nextCursor}" is refused rather than silently treated as page
 * one, the same way an unrecognised {@code cardKey} is refused rather than
 * shown as a default card: both are the client mistyping something that
 * looked correct, and both would rather say so than answer with a page that
 * quietly is not the one asked for.
 */
class InvalidCursorException extends RuntimeException {

    InvalidCursorException(String cursor) {
        super("cursor did not come from a previous meta.nextCursor: " + cursor);
    }
}
