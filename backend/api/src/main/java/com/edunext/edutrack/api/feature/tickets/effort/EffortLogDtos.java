package com.edunext.edutrack.api.feature.tickets.effort;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * C-035 · the wire shapes for {@code logEffort} and {@code listEffortLogs}, per
 * {@code contracts/openapi.yaml}'s {@code EffortLog} schema.
 */
final class EffortLogDtos {

    private EffortLogDtos() {
    }

    /**
     * The contract's {@code UserRef}, declared locally rather than shared —
     * following {@code CommentDtos.UserRef} and {@code AttachmentDtos.UserRef}:
     * a common DTO across four streams' features is shared surface that has to
     * be renegotiated whenever any one of them wants another field.
     */
    record UserRef(long id, String displayName) {
    }

    /**
     * @param hours          <b>ignored for a correction.</b> §A-043's compensating
     *                       entry computes its own hours as the exact negation of
     *                       the entry it reverses ({@link EffortLogService}), so
     *                       a caller cannot post an arbitrary reversal that leaves
     *                       a residue reading as real work. Required by the
     *                       schema regardless, because {@code isCorrection} is a
     *                       sibling field a static schema cannot make conditional
     * @param workDate       likewise ignored for a correction — the negative
     *                       belongs on the day the original claimed, not on the
     *                       day it was corrected
     * @param isCorrection   defaults to false via the boxed type, matching
     *                       {@code CommentDtos.CommentWriteRequest.isClientVisible}'s
     *                       reasoning: an omitted field and an explicit
     *                       {@code false} must read the same
     * @param correctsEntryId required once {@code isCorrection} is true — see
     *                       {@link EffortCorrectionTargetRequiredException}
     */
    record EffortLogRequest(
            @NotNull
            @DecimalMin(value = "0.25")
            @DecimalMax(value = "24")
            BigDecimal hours,
            @NotNull LocalDate workDate,
            @Size(max = 2000) String note,
            Boolean isCorrection,
            Long correctsEntryId) {
    }

    /**
     * @param stageCode   stamped server-side from the ticket's position at the
     *                    moment of the append — never sent by the client
     * @param cycleNo     likewise stamped, except on a correction, which is
     *                    stamped from the entry it reverses rather than from the
     *                    ticket's current position — see {@link EffortLogService}
     */
    record EffortLogDto(
            long id,
            UserRef user,
            BigDecimal hours,
            LocalDate workDate,
            String note,
            String stageCode,
            int cycleNo,
            int iterationNo,
            boolean isCorrection,
            Long correctsEntryId,
            Instant createdAt) {

        static EffortLogDto of(TicketEffortLog row, Map<Long, UserRef> people) {
            return new EffortLogDto(
                    row.getId(),
                    people.get(row.getUserId()),
                    row.getHours(),
                    row.getWorkDate(),
                    row.getNote(),
                    row.getStageCode(),
                    row.getCycleNo(),
                    row.getIterationNo(),
                    row.isCorrection(),
                    row.getCorrectsEntryId(),
                    row.getLoggedAt());
        }
    }

    record EffortLogResponse(EffortLogDto data) {
    }

    /**
     * The contract's {@code EffortLogListResponse.meta} — {@code allOf: [Meta,
     * {cycleTotalHrs, grandTotalHrs}]}. Composes {@link PageMeta} rather than
     * restating its two fields: A-053's {@code PaginationRulesTest} refuses a
     * sixth class that declares its own {@code nextCursor}, on the five-classes,
     * three-shapes history in that test's own javadoc. {@code @JsonUnwrapped}
     * flattens {@code page} onto this record's own JSON level, which is what
     * makes the wire shape match the contract's {@code allOf} despite the nesting
     * on the Java side.
     *
     * @param cycleTotalHrs the ticket's <b>current</b> cycle total, independent
     *                      of any {@code cycle}/{@code stage}/{@code iteration}
     *                      filter on the request — the headline figure for
     *                      whoever is working the ticket now, unaffected by which
     *                      slice of the log they happen to be looking at
     * @param grandTotalHrs Σ across every cycle, likewise unfiltered
     */
    record ListMeta(@JsonUnwrapped PageMeta page, BigDecimal cycleTotalHrs, BigDecimal grandTotalHrs) {
    }

    record EffortLogListResponse(List<EffortLogDto> data, ListMeta meta) {
    }
}
