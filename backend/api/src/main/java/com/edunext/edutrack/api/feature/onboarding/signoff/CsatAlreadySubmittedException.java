package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * B-119 · 422 — "this go-live has already been surveyed. One answer per
 * client" (the contract's own wording).
 *
 * <p>Scoped by client, not by the one signoff row a session addresses —
 * see {@code ObSignoffRepository#existsByObClientIdAndKindAndCsatSubmittedAtIsNotNull}.
 * A client with several {@code GO_LIVE} journeys can reach this from a
 * session that has never itself been surveyed, because a sibling journey's
 * session already was.
 */
class CsatAlreadySubmittedException extends RuntimeException {

    CsatAlreadySubmittedException() {
        super("This client has already been surveyed");
    }
}
