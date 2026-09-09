package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * B-119 · 422 — {@code submitObCsat} on a session that is not a completed
 * {@code GO_LIVE} acceptance.
 *
 * <p>Two facts collapse into this one exception rather than two: a
 * {@code STEP} sign-off's session (the contract's own named case — "422 on a
 * step sign-off's session") and a {@code GO_LIVE} session whose acceptance
 * has not actually gone through yet ({@code status != SIGNED}, reachable if
 * a caller posts to {@code csat} without ever calling {@code accept}). Both
 * answer the identical question a caller cannot act on differently — "is
 * this a go-live I have already accepted" — so distinguishing them would
 * hand back detail nobody can do anything with, on {@code InvalidSignoffTokenException}'s
 * own reasoning for the surface's other refusals.
 *
 * <p>{@code 422} rather than the surface's generic {@code 401}: the session
 * itself is genuine and still live — {@code ObSignoffAcceptService} kept it
 * that way on purpose so CSAT could use it — so this is not "your credential
 * does not work", it is "your credential works and this is not what it is
 * for".
 */
class CsatNotOfferedException extends RuntimeException {

    CsatNotOfferedException() {
        super("This session is not a completed go-live acceptance");
    }
}
