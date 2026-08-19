package com.edunext.edutrack.api.feature.transitions;

/**
 * C-044 · 400 — {@code effortHours} was omitted on a handoff.
 *
 * <p>Governance decision G-1 (blueprint §4A, PLAN.md's own citation of it):
 * effort confirmation at handoff is mandatory unless the project allows
 * warn-only. No per-project flag for the warn-only exception exists anywhere
 * in the schema today — grepped for {@code effortConfirmation},
 * {@code requiresEffort} and {@code warnOnly} across {@code backend/},
 * nothing outside docs — so this defaults to <b>blocking</b>, G-1's own
 * recommended default, for every project until Stream A adds the column.
 * Raised rather than guessed at; see {@code HandoffService}'s class javadoc.
 */
class EffortHoursRequiredException extends RuntimeException {

    EffortHoursRequiredException() {
        super("effortHours is required at handoff — G-1's default is blocking, and no project "
                + "carries a warn-only override yet.");
    }
}
