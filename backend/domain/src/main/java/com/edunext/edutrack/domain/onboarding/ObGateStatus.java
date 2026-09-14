package com.edunext.edutrack.domain.onboarding;

/**
 * C-103 · {@code ob_journeys.gate_status} — plan §5.2/§5.3, the client's
 * PREREQUISITES hold. Distinct from {@link ObJourneyStep}'s per-journey
 * {@code held_by_journey_id} service-level hold (§5.5, C-123): this one
 * flips {@link #OPEN} for every one of a client's journeys at once, the
 * other clears per journey. See the migration header for why modelling
 * both as one field would be the mistake.
 */
public enum ObGateStatus {

    /**
     * Steps visible — dots, owners, TATs — and nothing activating itself: no
     * step moves to {@code IN_PROGRESS} and no clock starts <em>on its own</em>
     * while the gate is here.
     *
     * <p><b>It no longer refuses an owner who starts a step deliberately.</b>
     * Plan §5.2's "clocks dead" was enforced on every path into
     * {@code IN_PROGRESS}, which meant one unverified document stopped all
     * implementation work for the client; the checklist is advisory now and
     * {@code ObJourneyStepLifecycleService#start} does not consult this field.
     * What remains is the automatic half, which is the half that was ever
     * about false breaches: a journey sitting here accrues no TAT that nobody
     * chose to start.
     */
    LOCKED,

    /** The prerequisite gate has cleared for this client. */
    OPEN
}
