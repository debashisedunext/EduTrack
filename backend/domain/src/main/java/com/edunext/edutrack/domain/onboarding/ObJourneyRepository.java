package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

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
     * "One per client per <b>service</b>" among live (non-archived)
     * journeys — {@code uq_ob_journeys_client_service}'s own condition,
     * checked here before the insert rather than left to surface as a raw
     * constraint violation.
     *
     * <p>The product used to be the whole key. It stopped being one when a
     * product gained more than one <em>active</em> Module Service
     * ({@code V20260910_0030}): a client buying EduTrack ERP holds one live
     * journey per service of it, and a product-wide check would refuse
     * every service after the first.
     */
    boolean existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
            Long obClientId, Long productId, String serviceName);

    /**
     * C-103's own instantiation-time question: has this client's
     * prerequisite gate already cleared? Plan §5.3 — "products bought after
     * gate-open instantiate directly OPEN." A client's gate opens for every
     * journey at once (C-118) and never re-locks, so one match, archived or
     * not, answers it.
     */
    boolean existsByObClientIdAndGateStatus(Long obClientId, ObGateStatus gateStatus);

    /**
     * C-123 · the client's live journey for one <b>service</b> — what a
     * newly instantiated journey is held behind when its template declares a
     * service-level dependency (plan §5.5). Same "live" condition as the
     * uniqueness guard above, so at most one row can match.
     *
     * <p>Resolved by (product, service name) rather than by the dependency's
     * template id, so the hold survives the dependency publishing a new
     * version: the client's journey pins v1 while the declaration names v2,
     * and an id-keyed lookup would find nothing and start every dependent
     * journey unheld.
     */
    Optional<ObJourney> findFirstByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNullOrderByIdDesc(
            Long obClientId, Long productId, String serviceName);

    /**
     * C-107 · the per-journey lock {@code ob_step_history}'s chain needs
     * before an append — {@code TicketRepository#findByIdForUpdate}'s own
     * precedent, one module over. {@code SELECT ... FOR UPDATE} on the parent
     * journey row before reading the chain tail, so two concurrent skips (or,
     * later, any other event this journey's history records) cannot both read
     * the same tail and fork it.
     *
     * <p>A plain JPQL lock, not a native query: unlike {@link ObStepHistory},
     * {@link ObJourney} is an ordinary mutable entity, so Hibernate's
     * lock-mode upgrade on the loaded instance has nothing to conflict with.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ObJourney j where j.id = :id")
    Optional<ObJourney> findByIdForUpdate(@Param("id") Long id);
}
