package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * C-111 · 404 — no {@code ob_journey_step_items} row with that id.
 *
 * <p>A sibling of {@link JourneyStepNotFoundException} and answered the same
 * way, for the reason CONVENTIONS.md §7 gives: an id the caller cannot reach
 * is indistinguishable from one that does not exist, so absence is the only
 * safe answer. There is nothing row-scoped to leak here beyond the fact that
 * some step somewhere has an item with this id, and 404 does not concede even
 * that.
 *
 * <p>Deliberately <em>not</em> a 422 like {@link NotStepOwnerException}. That
 * one depends on a row the caller can see and whose owner columns refused
 * them; this one depends on no row at all.
 */
class JourneyStepItemNotFoundException extends RuntimeException {

    JourneyStepItemNotFoundException(long itemId) {
        super("journey step item " + itemId + " does not exist");
    }
}
