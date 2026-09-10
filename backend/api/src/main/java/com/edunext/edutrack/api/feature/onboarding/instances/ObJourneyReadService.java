package com.edunext.edutrack.api.feature.onboarding.instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObRag;
import com.edunext.edutrack.domain.onboarding.ObStepTatBudget;

/**
 * C-110 · assembles {@code GET /onboarding/journeys/{journeyId}} — the ribbon
 * OB-05 draws when an accordion expands.
 *
 * <p>The arithmetic is {@code ObClientService#journeys}', deliberately: this
 * route and the strip inside {@code getObClient} describe the same journey and
 * a reader comparing the collapsed strip to the expanded ribbon must not find
 * two different percentages. Where the two differ is only in reach — the strip
 * carries {@code ObStepDot}s for a whole client, this carries the full step
 * view for one journey.
 */
@Service
public class ObJourneyReadService {

    private final ObJourneyReadRepository reads;
    private final ObJourneyStepRepository steps;
    private final ObJourneyStepRagService rag;
    private final ObBackupOwnerResolver backupOwners;
    private final WorkingCalendarRepository workingCalendars;

    ObJourneyReadService(ObJourneyReadRepository reads, ObJourneyStepRepository steps,
                         ObJourneyStepRagService rag, ObBackupOwnerResolver backupOwners,
                         WorkingCalendarRepository workingCalendars) {
        this.reads = reads;
        this.steps = steps;
        this.rag = rag;
        this.backupOwners = backupOwners;
        this.workingCalendars = workingCalendars;
    }

    @Transactional(readOnly = true)
    Optional<ObJourneyReadDtos.ObJourneyDetail> find(ObClientScope scope, long journeyId) {
        return reads.find(scope, journeyId).map(this::detail);
    }

    private ObJourneyReadDtos.ObJourneyDetail detail(ObJourneyReadRepository.Row row) {
        List<ObJourneyStep> rows = steps.findByJourneyIdOrderBySequenceAsc(row.id());

        List<ObJourneyStepLifecycleDtos.ObJourneyStepDetail> views = rows.stream()
                .map(step -> ObJourneyStepLifecycleDtos.ObJourneyStepDetail.of(
                        step, rag.ragFor(step), backupOwners.effectiveOwnerUserId(step)))
                .toList();

        ObJourneyStep current = currentStep(rows);

        return new ObJourneyReadDtos.ObJourneyDetail(
                row.id(),
                row.obClientId(),
                row.clientName(),
                new ObJourneyReadDtos.ObProductRef(row.productId(), row.productCode(), row.productName()),
                row.serviceName(),
                ObGateStatus.valueOf(row.gateStatus()),
                row.rag() == null ? null : ObRag.valueOf(row.rag()),
                percentComplete(row.stepsSettled(), row.stepCount()),
                current == null ? null : new ObJourneyReadDtos.ObStepDot(
                        current.getId(), current.getSequence(), current.getName(),
                        current.getStatus().name(), rag.ragFor(current), current.getDependsOnStepId()),
                current == null ? null : ObJourneyReadDtos.UserRef.of(
                        current.getOwnerUserId(),
                        current.getOwnerUserId() == null ? null
                                : reads.displayNameOf(current.getOwnerUserId()).orElse(null)),
                row.heldByJourneyId(),
                row.totalTatDays(),
                elapsedTatDays(rows),
                row.startedAt(),
                row.completedAt(),
                row.archivedAt(),
                row.templateId(),
                row.templateVersion(),
                views,
                parallelGroups(rows));
    }

    /**
     * The service in progress, or null when none is.
     *
     * <p>Null for a journey behind its gate, held behind a sibling, or
     * finished — the contract's own wording, and the reason this is not
     * "the first unfinished step": on a journey nobody has started, the first
     * step is {@code PENDING} and naming it as "in progress" would put a
     * service on the Delayed Projects grid that nobody has begun.
     */
    private static ObJourneyStep currentStep(List<ObJourneyStep> rows) {
        return rows.stream()
                .filter(s -> s.getStatus() == ObJourneyStepStatus.IN_PROGRESS
                        || s.getStatus() == ObJourneyStepStatus.BLOCKED
                        || s.getStatus() == ObJourneyStepStatus.WAITING_ON_CLIENT)
                .findFirst().orElse(null);
    }

    /**
     * Settled over total, rounded down, and 100 for a journey with no steps.
     *
     * <p>{@code ObClientService#percentComplete}'s answer, restated here rather
     * than shared: making one package depend on another's private arithmetic
     * to save four lines is the coupling feature packaging exists to avoid.
     * {@code SKIPPED} counts as settled — a waived service is not outstanding
     * work, and a journey that could never reach 100% because one service was
     * waived would read as permanently stuck.
     */
    private static int percentComplete(int settled, int total) {
        return total == 0 ? 100 : (int) Math.floor(settled * 100.0 / total);
    }

    /**
     * Working days consumed, client waits excluded — {@code ObJourneyStrip}'s
     * {@code utilizedHours} expressed in the unit this schema asks for.
     *
     * <p>Hours divided by the working day rather than counted separately: the
     * two would be a second opinion about the same clock, and
     * {@link ObJourneyStepRagService#hoursConsumed} is the one that already
     * knows a {@code WAITING_ON_CLIENT} step reads its last pause.
     */
    private double elapsedTatDays(List<ObJourneyStep> rows) {
        double hours = rows.stream()
                .map(rag::hoursConsumed)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();
        // One working day's hours from the same source a TAT budget is
        // measured in, so the numerator and denominator cannot disagree about
        // how long a day is.
        double hoursPerDay = ObStepTatBudget.hours(workingCalendars, 1).doubleValue();
        return hoursPerDay == 0 ? 0 : hours / hoursPerDay;
    }

    /**
     * Services that may run at once — every step sharing a dependency, grouped.
     *
     * <p>The ribbon draws these as parallel rather than sequential. Steps with
     * no dependency form one group (they can all start the moment the gate
     * opens); the rest group by the step they wait on. A group of one is still
     * a group: dropping singletons would make the array's meaning depend on
     * its length.
     */
    private static List<List<Long>> parallelGroups(List<ObJourneyStep> rows) {
        Map<Long, List<Long>> byDependency = new LinkedHashMap<>();
        for (ObJourneyStep step : rows) {
            byDependency
                    .computeIfAbsent(step.getDependsOnStepId(), key -> new ArrayList<>())
                    .add(step.getId());
        }
        return List.copyOf(byDependency.values());
    }
}
