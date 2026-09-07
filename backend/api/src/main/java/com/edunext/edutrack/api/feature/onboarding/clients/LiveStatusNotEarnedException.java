package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-102 · {@code ob-client-live-not-earned} — 422.
 *
 * <p>{@code LIVE} is the go-live flip that fires when every one of a client's
 * journeys completes with its sign-offs (plan §5.9). It is not one of the four
 * values a person chooses from a dropdown, even though the column holds all
 * four: <em>"a status a person can type is a status that disagrees with the
 * journeys underneath it."</em>
 *
 * <p>422 rather than 400: the request is well formed and the field is a legal
 * member of its enum. What refuses it is the workflow, which is exactly what
 * CONVENTIONS.md §3 reserves 422 for.
 */
class LiveStatusNotEarnedException extends RuntimeException {

    LiveStatusNotEarnedException() {
        super("LIVE is earned when every journey completes with its sign-offs, not set by hand. "
                + "It will be stamped automatically, with the moment it happened.");
    }
}
