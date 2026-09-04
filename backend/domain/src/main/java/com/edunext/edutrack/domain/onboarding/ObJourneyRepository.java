package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ObJourneyRepository extends JpaRepository<ObJourney, Long> {

    /**
     * "One per client per product" among live (non-archived) journeys —
     * {@code uq_ob_journeys_client_product}'s own condition, checked here
     * before the insert rather than left to surface as a raw constraint
     * violation.
     */
    boolean existsByObClientIdAndProductIdAndArchivedAtIsNull(Long obClientId, Long productId);

    /**
     * C-103's own instantiation-time question: has this client's
     * prerequisite gate already cleared? Plan §5.3 — "products bought after
     * gate-open instantiate directly OPEN." A client's gate opens for every
     * journey at once (C-118) and never re-locks, so one match, archived or
     * not, answers it.
     */
    boolean existsByObClientIdAndGateStatus(Long obClientId, ObGateStatus gateStatus);
}
