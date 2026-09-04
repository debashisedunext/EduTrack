package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;

import java.util.Objects;

/**
 * C-104 · the onboarding module's row-scope rule for a step, in one place —
 * plan §3: "Step Owner — update only their own steps." See {@link
 * NotStepOwnerException}'s own javadoc for why the backup owner is admitted
 * and a Manager/Admin override is not, yet.
 *
 * <p>Kept as a plain predicate rather than a {@code @PreAuthorize}
 * expression, on {@code StageOwnership}'s own precedent one module over:
 * this is a row-scope question, not a capability the caller does or does
 * not hold.
 */
final class ObStepOwnership {

    private ObStepOwnership() {
    }

    static boolean mayAct(long callerId, ObJourneyStep step) {
        return Objects.equals(step.getOwnerUserId(), callerId)
                || Objects.equals(step.getBackupOwnerUserId(), callerId);
    }
}
