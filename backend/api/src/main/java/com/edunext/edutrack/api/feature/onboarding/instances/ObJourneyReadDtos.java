package com.edunext.edutrack.api.feature.onboarding.instances;

import java.time.Instant;
import java.util.List;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObRag;

/**
 * C-110 · {@code GET /onboarding/journeys/{journeyId}} — the expanded ribbon.
 *
 * <p>The contract composes {@code ObJourneyDetail} as {@code allOf} over
 * {@code ObJourneySummary}; Java records do not compose, so this restates the
 * summary's fields inline, exactly as {@code ObClientDtos.ObClientDetail}
 * restates {@code ObClient}'s. The names match the schema property for
 * property, which is what {@code ObContractTest} checks.
 *
 * <p>{@code steps} carries {@link ObJourneyStepLifecycleDtos.ObJourneyStepDetail}
 * rather than a second step shape. That record already <em>is</em> the
 * contract's {@code ObJourneyStepView} — C-107 wrote it for the five
 * transition routes and the contract's own note says why one shape serves
 * both: "a second, fuller step object would mean two shapes for one row and a
 * client having to know which route gave it which". Its {@code items} and
 * {@code docs} arrive empty here, which is the shape the ribbon reads — the
 * checklist belongs to {@code getObJourneyStep}, one step at a time, and
 * fetching it for every service of every expanded journey would be a query per
 * step for fields no tile draws.
 */
final class ObJourneyReadDtos {

    private ObJourneyReadDtos() {
    }

    record ObProductRef(long id, String code, String name) {
    }

    /**
     * {@code ObStepDot} — the collapsed strip's per-service dot, restated here
     * because {@code currentStep} on the summary is one of them.
     */
    record ObStepDot(long id, int sequence, String name, String status, ObRag rag,
                     Long dependsOnStepId) {
    }

    record UserRef(long id, String displayName) {

        /** Null in, null out — a journey with nothing running has no owner to name. */
        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * <p>{@code serviceName} names the Module Service this ribbon belongs to
     * — "Standard SaaS Onboarding", not "EduTrack ERP". A product publishes
     * several at once, each its own journey, so the product alone no longer
     * identifies which ribbon a reader is looking at.
     */
    record ObJourneyDetail(
            long id, long obClientId, String clientName, ObProductRef product, String serviceName,
            ObGateStatus gateStatus, ObRag rag, int percentComplete,
            ObStepDot currentStep, UserRef owner, Long heldByJourneyId,
            int totalTatDays, double elapsedTatDays,
            Instant startedAt, Instant completedAt, Instant archivedAt,
            long templateId, int templateVersion,
            List<ObJourneyStepLifecycleDtos.ObJourneyStepDetail> steps,
            List<List<Long>> parallelGroups) {
    }

    record ObJourneyDetailResponse(ObJourneyDetail data) {
    }
}
