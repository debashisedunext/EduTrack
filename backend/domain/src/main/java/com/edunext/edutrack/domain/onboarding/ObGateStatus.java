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

    /** Steps visible — dots, owners, TATs — but no step activates and no clock runs. */
    LOCKED,

    /** The prerequisite gate has cleared for this client. */
    OPEN
}
