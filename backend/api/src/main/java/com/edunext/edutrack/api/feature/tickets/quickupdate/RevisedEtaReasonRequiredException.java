package com.edunext.edutrack.api.feature.tickets.quickupdate;

/**
 * C-036 · a revised ETA with no reason — S-21's wireframe marks the reason
 * mandatory the moment the date field is touched: {@code "Revised ETA [ 14 Aug
 * 2026 ] (reason*)"}.
 *
 * <p>Enforced here as well as in {@code quickUpdateForm.ts}'s {@code
 * superRefine}, on {@code LevelReasonRequiredException}'s own argument: a
 * client-side rule with no server-side twin is a rule anyone with a REST client
 * can skip, and the row this writes is exactly the "who moved this date and
 * why" record a manager reads a fortnight later.
 */
class RevisedEtaReasonRequiredException extends RuntimeException {

    RevisedEtaReasonRequiredException() {
        super("A revised ETA needs a reason — it is the whole content of the history row it writes.");
    }
}
