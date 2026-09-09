package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObPrereqHistory;
import com.edunext.edutrack.domain.onboarding.ObPrereqSubmittedVia;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-125 · the wire shapes for the eleven instance operations, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>Record types throughout, package-private, on
 * {@code ObPrereqTemplateDtos}' own convention — and every response type
 * named exactly as the contract's component is, for the springdoc
 * simple-name collision {@code ObJourneyTemplateResponse} records.
 */
final class ObClientPrereqDtos {

    private ObClientPrereqDtos() {
    }

    // ── requests ──────────────────────────────────────────────────────

    record ObClientPrereqTaskCreateRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull @Min(1) @Max(365) Integer tatDays,
            @NotNull Boolean isMandatory) {
    }

    /**
     * Three fields, and deliberately not {@code status} or
     * {@code isMandatory} — the contract says what each of those would let a
     * field update do to a gate. All three are optional; a null means "leave
     * it", which is what makes this a PATCH rather than a whole
     * representation.
     */
    record ObClientPrereqTaskUpdateRequest(
            @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @Min(1) @Max(365) Integer tatDays) {
    }

    record ObPrereqSubmitRequest(
            @Size(max = 4000) String note,
            List<Long> attachmentIds) {
    }

    record ObPrereqVerifyRequest(@Size(max = 4000) String note) {
    }

    record ObPrereqReturnRequest(@NotBlank @Size(max = 2000) String comment) {
    }

    record ObPrereqSkipRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    record ObPrereqCommentCreateRequest(@NotBlank @Size(max = 4000) String body) {
    }

    // ── responses ─────────────────────────────────────────────────────

    /** Duplicated per package, on {@code ObClientDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /** The client contact half of an author or actor pair. */
    record ObContactRef(long id, String name, String email) {
    }

    record ObClientPrereqTaskDto(
            Long id, Long obClientId, Long templateTaskId, int sequence,
            String title, String description, int tatDays, boolean isMandatory, boolean isAdHoc,
            ObPrereqTaskStatus status, Instant dueAt, boolean isOverdue,
            Instant submittedAt, ObPrereqSubmittedVia submittedVia,
            Instant verifiedAt, UserRef verifiedBy,
            Instant skippedAt, UserRef skippedBy, String skipReason,
            int commentCount, int attachmentCount,
            List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> referenceDocs) {

        static ObClientPrereqTaskDto of(ObClientPrereqTask t, Instant now,
                                        UserRef verifiedBy, UserRef skippedBy,
                                        int commentCount, int attachmentCount,
                                        List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> referenceDocs) {

            return new ObClientPrereqTaskDto(
                    t.getId(), t.getObClientId(), t.getTemplateTaskId(), t.getSequence(),
                    t.getTitle(), t.getDescription(), t.getTatDays(), t.isMandatory(), t.isAdHoc(),
                    t.getStatus(), t.getDueAt(), t.isOverdue(now),
                    t.getSubmittedAt(), t.getSubmittedVia(),
                    t.getVerifiedAt(), verifiedBy,
                    t.getSkippedAt(), skippedBy, t.getSkipReason(),
                    commentCount, attachmentCount, referenceDocs);
        }
    }

    record ObClientPrereqTaskResponse(ObClientPrereqTaskDto data) {
    }

    record ObPrereqSubmissionFile(
            Long attachmentId, String fileName, Long sizeBytes,
            ObPrereqActorType uploadedByType, Instant uploadedAt) {
    }

    record ObClientPrereqTaskDetail(
            Long id, Long obClientId, Long templateTaskId, int sequence,
            String title, String description, int tatDays, boolean isMandatory, boolean isAdHoc,
            ObPrereqTaskStatus status, Instant dueAt, boolean isOverdue,
            Instant submittedAt, ObPrereqSubmittedVia submittedVia,
            Instant verifiedAt, UserRef verifiedBy,
            Instant skippedAt, UserRef skippedBy, String skipReason,
            int commentCount, int attachmentCount,
            List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> referenceDocs,
            List<ObPrereqSubmissionFile> submissions) {

        /**
         * {@code referenceDocs} now rides on {@code base} — the list carries
         * them too, so OB-05's accordion can name the template beside each
         * row. Taken from there rather than re-read, so the two documents
         * cannot disagree about one task.
         */
        static ObClientPrereqTaskDetail of(
                ObClientPrereqTaskDto base,
                List<ObPrereqSubmissionFile> submissions) {

            return new ObClientPrereqTaskDetail(
                    base.id(), base.obClientId(), base.templateTaskId(), base.sequence(),
                    base.title(), base.description(), base.tatDays(), base.isMandatory(), base.isAdHoc(),
                    base.status(), base.dueAt(), base.isOverdue(),
                    base.submittedAt(), base.submittedVia(),
                    base.verifiedAt(), base.verifiedBy(),
                    base.skippedAt(), base.skippedBy(), base.skipReason(),
                    base.commentCount(), base.attachmentCount(),
                    base.referenceDocs(), submissions);
        }
    }

    record ObClientPrereqTaskDetailResponse(ObClientPrereqTaskDetail data) {
    }

    record ObPrereqGateResult(
            ObClientPrereqTaskDto task, ObGateStatus gateStatus, boolean gateOpened,
            List<Long> openedJourneyIds, int mandatoryTotal, int mandatoryVerified) {
    }

    record ObPrereqGateResultResponse(ObPrereqGateResult data) {
    }

    record ObClientPrereqs(
            Long obClientId, int templateVersion, String status, Instant clearedAt,
            ObGateStatus gateStatus, int mandatoryTotal, int mandatoryVerified,
            int optionalOutstanding, List<ObClientPrereqTaskDto> tasks) {

        static ObClientPrereqs of(com.edunext.edutrack.domain.onboarding.ObClientPrereqs header,
                                  ObGateStatus gateStatus,
                                  ObClientPrereqService.Progress progress,
                                  List<ObClientPrereqTaskDto> tasks) {

            return new ObClientPrereqs(
                    header.getObClientId(), header.getTemplateVersion(),
                    header.getStatus().name(), header.getClearedAt(), gateStatus,
                    progress.mandatoryTotal(), progress.mandatoryVerified(),
                    progress.optionalOutstanding(), tasks);
        }
    }

    record ObClientPrereqsResponse(ObClientPrereqs data) {
    }

    record ObPrereqComment(
            Long id, Long prereqTaskId, ObPrereqActorType authorType,
            UserRef staffAuthor, ObContactRef clientAuthor,
            String body, boolean isSystem, Instant createdAt) {

        static ObPrereqComment of(com.edunext.edutrack.domain.onboarding.ObPrereqComment c,
                                  UserRef staffAuthor, ObContactRef clientAuthor) {

            return new ObPrereqComment(c.getId(), c.getPrereqTaskId(), c.getAuthorType(),
                    staffAuthor, clientAuthor, c.getBody(), c.isSystem(), c.getCreatedAt());
        }
    }

    record ObPrereqCommentResponse(ObPrereqComment data) {
    }

    record ObPrereqCommentListResponse(List<ObPrereqComment> data, PageMeta meta) {
    }

    record ObPrereqHistoryEntry(
            Long id, Long prereqTaskId, Instant at, ObPrereqActorType actorType,
            UserRef staffActor, ObContactRef clientActor,
            ObPrereqTaskStatus fromStatus, ObPrereqTaskStatus toStatus,
            String reason, boolean isCorrection, Long correctsEntryId) {

        static ObPrereqHistoryEntry of(ObPrereqHistory h, UserRef staffActor, ObContactRef clientActor) {
            return new ObPrereqHistoryEntry(h.getId(), h.getPrereqTaskId(), h.getOccurredAt(),
                    h.getActorType(), staffActor, clientActor,
                    h.getFromStatus(), h.getToStatus(), h.getReason(),
                    h.isCorrection(), h.getCorrectsEntryId());
        }
    }

    record ObPrereqHistoryListResponse(List<ObPrereqHistoryEntry> data, PageMeta meta) {
    }

    // The cursor envelope both append-only listings carry is A-053's shared
    // `PageMeta`, not a sixth local `Meta` record. Five packages declare their
    // own and they have already drifted — one carries `totalCount`, four do
    // not — which is the drift PageMeta was written to stop.
}
