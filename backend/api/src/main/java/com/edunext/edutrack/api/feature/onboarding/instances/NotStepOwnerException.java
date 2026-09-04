package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * C-104 · 422 — the onboarding module's own row-scope rule for a step,
 * {@code ObStepOwnership#mayAct} refused. Plan §3: "Step Owner — update
 * only their own steps." The backup owner already snapshotted onto the row
 * ({@link com.edunext.edutrack.domain.onboarding.ObJourneyStep#getBackupOwnerUserId()})
 * gets the same standing as the owner; C-108's own job is *assigning* that
 * column against the working calendar, not the permission to act once it is
 * set — the same split C-103 already drew for {@code ownerUserId}.
 *
 * <p>422, not 403, on {@code NotCurrentStageOwnerException}'s exact
 * reasoning one module over: this refusal depends on <em>this</em> step's
 * own owner columns, not on a capability the caller either holds or does
 * not, and {@code isAuthenticated()} is everything Spring Security decides
 * about this route today (see {@link ObJourneyStepLifecycleController}'s
 * class javadoc). A Manager/Admin override ("override steps with logged
 * reason", plan §3) is deliberately absent — the onboarding module has no
 * role vocabulary wired to an authority yet (A-111's {@code
 * ModuleAccessGuard} exists but nothing calls it), so there is no caller
 * property this class could check that would mean "Onboarding Manager"
 * without guessing. That override is a later task's to add once that
 * vocabulary exists, not this one's to fake with a ticketing role that
 * means something else entirely.
 */
class NotStepOwnerException extends RuntimeException {

    NotStepOwnerException(long stepId, Long ownerUserId, Long backupOwnerUserId) {
        super("journey step " + stepId + " may only be updated by its owner"
                + (ownerUserId != null ? " (user " + ownerUserId + ")" : " (unresolved)")
                + (backupOwnerUserId != null ? ", or its backup owner (user " + backupOwnerUserId + ")" : ""));
    }
}
