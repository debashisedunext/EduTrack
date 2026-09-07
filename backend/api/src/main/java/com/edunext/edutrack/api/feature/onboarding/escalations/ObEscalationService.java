package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObEscalation;
import com.edunext.edutrack.domain.onboarding.ObEscalationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * C-115 · {@code /onboarding/escalations} (OB-02, OB-10) — the internal
 * ladder's own read, acknowledge and resolve.
 */
@Service
public class ObEscalationService {

    private final ObEscalationReadRepository reads;
    private final ObEscalationRepository escalations;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObReportService}'s own
     * note: two constructors and no annotation is not an ambiguity Spring
     * resolves, it is a context that fails to start.
     */
    @Autowired
    ObEscalationService(ObEscalationReadRepository reads, ObEscalationRepository escalations) {
        this(reads, escalations, Clock.systemUTC());
    }

    /** Test seam. */
    ObEscalationService(ObEscalationReadRepository reads, ObEscalationRepository escalations, Clock clock) {
        this.reads = reads;
        this.escalations = escalations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ObEscalationDtos.ObEscalationListResponse list(ObEscalationScope scope, Long obClientId, Long journeyId,
                                                          Long escalatedTo, String level, String state,
                                                          String cursor, Integer limit) {
        if (scope.deniesEverything()) {
            return new ObEscalationDtos.ObEscalationListResponse(List.of(), PageMeta.last());
        }

        int clamped = PageLimit.clamp(limit);
        List<ObEscalationReadRepository.Row> rows = reads.list(
                scope, obClientId, journeyId, escalatedTo, level, state, cursor, PageLimit.fetchSize(clamped));

        CursorPage<ObEscalationReadRepository.Row> page = CursorPage.of(rows, clamped,
                row -> new Cursor(row.escalatedAt().toString(), row.id()));

        return new ObEscalationDtos.ObEscalationListResponse(
                page.data().stream().map(ObEscalationDtos.ObEscalationResponseData::of).toList(),
                page.meta());
    }

    /**
     * Stamps {@code acknowledgedBy}/{@code acknowledgedAt}. Idempotent in
     * effect: an already-acknowledged rung is returned unchanged, so the
     * record keeps saying when it was <em>first</em> seen. Does not touch
     * the ladder — L2 and L3 still follow on their own schedule.
     */
    @Transactional
    public ObEscalationDtos.ObEscalationResponseData acknowledge(ObEscalationScope scope, long escalationId, long callerId) {
        ObEscalationReadRepository.Row row = reads.findById(scope, escalationId)
                .orElseThrow(() -> new EscalationNotFoundException(escalationId));
        if (row.resolvedAt() != null) {
            throw new EscalationAlreadyResolvedException(escalationId);
        }
        if (row.acknowledgedAt() == null) {
            ObEscalation entity = escalations.findById(escalationId)
                    .orElseThrow(() -> new EscalationNotFoundException(escalationId));
            entity.setAcknowledgedBy(callerId);
            entity.setAcknowledgedAt(clock.instant());
            escalations.save(entity);
        }
        return ObEscalationDtos.ObEscalationResponseData.of(
                reads.findById(scope, escalationId).orElseThrow(() -> new EscalationNotFoundException(escalationId)));
    }

    /**
     * Closes exactly this rung — resolving L1 does not resolve L2 or L3
     * (each was sent to a different person, {@code uq_ob_escalations_open}'s
     * own reasoning for holding all three open at once).
     */
    @Transactional
    public ObEscalationDtos.ObEscalationResponseData resolve(ObEscalationScope scope, long escalationId,
                                                              long callerId, String note) {
        ObEscalationReadRepository.Row row = reads.findById(scope, escalationId)
                .orElseThrow(() -> new EscalationNotFoundException(escalationId));
        if (row.resolvedAt() != null) {
            throw new EscalationAlreadyResolvedException(escalationId);
        }
        ObEscalation entity = escalations.findById(escalationId)
                .orElseThrow(() -> new EscalationNotFoundException(escalationId));
        Instant now = clock.instant();
        entity.setResolvedBy(callerId);
        entity.setResolvedAt(now);
        entity.setResolutionNote(note);
        escalations.save(entity);

        return ObEscalationDtos.ObEscalationResponseData.of(
                reads.findById(scope, escalationId).orElseThrow(() -> new EscalationNotFoundException(escalationId)));
    }
}
