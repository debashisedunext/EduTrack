package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * ⚠ <b>A-112 · do not call this from feature code.</b> Every journey read goes
 * through {@code ScopedJourneys}, which is the only class permitted to compose
 * {@code OnboardingScopeResolver}'s specification in — exactly the arrangement
 * {@code TicketRepository} and {@code ScopedTickets} have for §10.2, and for
 * the same reason: a guard that feature code must remember to apply is a guard
 * that is applied almost everywhere. {@code ScopeGuardRulesTest} fails the
 * build if any class in {@code api} outside {@code api.security.scope} touches
 * this interface.
 */
public interface ObJourneyRepository extends JpaRepository<ObJourney, Long>,
        JpaSpecificationExecutor<ObJourney> {

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
