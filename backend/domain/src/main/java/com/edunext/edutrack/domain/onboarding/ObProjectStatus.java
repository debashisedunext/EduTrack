package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_projects.status} — the four values {@code ck_ob_projects_status}
 * admits.
 *
 * <p>Deliberately close to {@link ObClientStatus} without being it. The two
 * describe different things and the difference is the whole point of the
 * project table: a client is {@code LIVE} when <em>every</em> engagement it has
 * is finished, while a project is {@link #COMPLETED} when its own journeys are
 * — so a client running two products can have one completed project and one
 * still in configuration, which the old single-status client could not express.
 *
 * <p>{@link #COMPLETED} is <b>earned, never set</b>, on {@code ObClientStatus.LIVE}'s
 * precedent: it is stamped when the last of the project's journeys completes,
 * and a request asking for it is refused. The other three are judgements a
 * person records, and two of them carry a mandatory reason for the reason
 * {@code ObClientStatus} gives — a project that stopped moving is a fact
 * somebody has to explain months later, and the explanation is worth nothing if
 * it was optional at the moment it was known.
 */
public enum ObProjectStatus {

    /** The ordinary state: journeys instantiated, work in progress or not yet begun. */
    RUNNING,

    /** Every one of the project's journeys has completed. Derived, never accepted from a request. */
    COMPLETED,

    /** Paused by agreement. Reason mandatory. */
    ON_HOLD,

    /** Abandoned. Reason mandatory, and the journeys are kept rather than deleted. */
    DROPPED;

    /** True for the three a person may record. {@link #COMPLETED} is the one that is not. */
    public boolean settableByHand() {
        return this != COMPLETED;
    }

    /** {@link #ON_HOLD} and {@link #DROPPED} — the two that cannot be recorded silently. */
    public boolean requiresReason() {
        return this == ON_HOLD || this == DROPPED;
    }

    /**
     * True while the project is one the Projects grid reports delay against.
     *
     * <p>A completed project has no clock left to be late against, and a
     * dropped or held one has a clock somebody stopped on purpose — reporting
     * a growing delay on any of the three would be counting days nobody is
     * working.
     */
    public boolean accruesDelay() {
        return this == RUNNING;
    }
}
