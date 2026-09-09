package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.domain.onboarding.ObPrereqSubmittedVia;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * C-121 · the wire shapes for {@code /portal/onboarding/**} — CP-03 and
 * CP-04.
 *
 * <h2>Prerequisites reuse the staff wire shape on purpose</h2>
 *
 * <p>{@code contracts/openapi.yaml}'s own {@code ObClientPrereqTaskDetail}
 * description already settles this: "One schema for both principals ... a
 * prerequisite is the one object in this module that needs no separate
 * portal serializer" — plan §9/§11's "separate portal DTO serializers" rule
 * is about <em>journeys</em> (owners, internal comms), and none of that is on
 * a prerequisite row. So {@link PortalPrereqTask}/{@link
 * PortalPrereqTaskDetail}/{@link PortalReferenceDoc}/{@link
 * PortalSubmissionFile} are field-for-field the same shape as the contract's
 * {@code ObClientPrereqTask} family, and the operations in {@code
 * openapi.yaml} declare exactly those existing schemas rather than new
 * {@code Portal}-prefixed ones. Only the comment thread departs — see {@link
 * PortalPrereqComment}'s own note — and the journey accordions, which plan
 * §9's CP-03 row genuinely does narrow.
 */
public final class PortalOnboardingDtos {

    private PortalOnboardingDtos() {
    }

    // ── shared refs ──────────────────────────────────────────────────────

    public record PortalUserRef(long id, String displayName) {
    }

    /** A lean contact ref for the comment thread — see {@link PortalPrereqComment}'s note. */
    public record PortalContactRef(long id, String name, String email) {
    }

    // ── CP-03 · the read-only journey accordions (this is the part plan §9/§11 narrows) ──

    /**
     * One step dot. Deliberately narrow — id, sequence, name, status, rag,
     * the dependency badge — because this is the whole of what plan §9's CP-03
     * row permits: "step status only; no owner names, internal comms, or
     * block reasons". No TAT figures either: plan §11's never-visible list
     * names "TAT internals" for the client explicitly.
     *
     * @param openEscalation always {@code null} today. The named slot C-126
     *                       fills — see this task's own summary. Left on the
     *                       wire now rather than added later so C-126 widens
     *                       a contract field instead of introducing one.
     */
    public record PortalStepDot(long id, int sequence, String name, String status, String rag,
                                Long dependsOnStepId, Object openEscalation) {

        static PortalStepDot of(long id, int sequence, String name, String status, String rag,
                                Long dependsOnStepId) {
            return new PortalStepDot(id, sequence, name, status, rag, dependsOnStepId, null);
        }
    }

    /**
     * One product's journey, read-only. No {@code totalTatDays}/{@code
     * utilizedHours} — plan §11's never-visible rule, one level up.
     * {@code heldByJourneyId} survives because it answers "why is nothing
     * moving yet" without naming an internal block reason.
     */
    public record PortalJourneyStrip(long id, PortalProductRef product, String gateStatus, String rag,
                                     int percentComplete, Long heldByJourneyId,
                                     List<PortalStepDot> steps) {
    }

    public record PortalProductRef(long id, String code, String name) {
    }

    // ── the interactive prerequisites — the staff wire shape, unchanged ─────

    /** {@code ObClientPrereqTask}, field-for-field. */
    public record PortalPrereqTask(
            long id, long obClientId, Long templateTaskId, int sequence, String title, String description,
            boolean isMandatory, boolean isAdHoc,
            ObPrereqTaskStatus status, Instant dueAt, boolean isOverdue,
            Instant submittedAt, ObPrereqSubmittedVia submittedVia,
            Instant verifiedAt, PortalUserRef verifiedBy,
            Instant skippedAt, PortalUserRef skippedBy, String skipReason,
            int commentCount, int attachmentCount) {
    }

    public record PortalPrereqs(
            long obClientId, int templateVersion, String status, Instant clearedAt,
            ObGateStatus gateStatus, int mandatoryTotal, int mandatoryVerified, int optionalOutstanding,
            List<PortalPrereqTask> tasks) {
    }

    /** {@code ObPrereqTemplateTaskDoc}, field-for-field — plus {@code downloadUrl}, additive to that schema. */
    public record PortalReferenceDoc(long id, long templateTaskId, String label, long attachmentId,
                                     String fileName, Long sizeBytes, String downloadUrl) {
    }

    /** {@code ObPrereqSubmissionFile}, field-for-field — plus {@code downloadUrl}, additive to that schema. */
    public record PortalSubmissionFile(long attachmentId, String fileName, long sizeBytes,
                                       ObAttachmentUploaderType uploadedByType, Instant uploadedAt,
                                       String downloadUrl) {
    }

    /** {@code ObClientPrereqTaskDetail} — {@link PortalPrereqTask} plus the two lists CP-04 renders. */
    public record PortalPrereqTaskDetail(
            long id, long obClientId, Long templateTaskId, int sequence, String title, String description,
            boolean isMandatory, boolean isAdHoc,
            ObPrereqTaskStatus status, Instant dueAt, boolean isOverdue,
            Instant submittedAt, ObPrereqSubmittedVia submittedVia,
            Instant verifiedAt, PortalUserRef verifiedBy,
            Instant skippedAt, PortalUserRef skippedBy, String skipReason,
            int commentCount, int attachmentCount,
            List<PortalReferenceDoc> referenceDocs, List<PortalSubmissionFile> submissions) {

        static PortalPrereqTaskDetail of(PortalPrereqTask t, List<PortalReferenceDoc> referenceDocs,
                                         List<PortalSubmissionFile> submissions) {
            return new PortalPrereqTaskDetail(
                    t.id(), t.obClientId(), t.templateTaskId(), t.sequence(), t.title(), t.description(),
                    t.isMandatory(), t.isAdHoc(), t.status(), t.dueAt(), t.isOverdue(),
                    t.submittedAt(), t.submittedVia(), t.verifiedAt(), t.verifiedBy(),
                    t.skippedAt(), t.skippedBy(), t.skipReason(), t.commentCount(), t.attachmentCount(),
                    referenceDocs, submissions);
        }
    }

    /**
     * {@code ObPrereqComment}'s shape narrowed to what CP-04's thread render
     * actually needs — the one place this task departs from full parity with
     * the staff wire shape. The full {@code ObPrereqComment.clientAuthor} is
     * typed as the rich {@code ObContact} (designation, WhatsApp consent, the
     * lot), which would cost an extra join per comment for fields a comment
     * bubble does not render. {@link PortalContactRef} is the three fields
     * CP-04 actually shows. Named here rather than silently matched, since it
     * is a genuine, if small, portal-specific schema — {@code
     * PortalPrereqCommentListResponse} in {@code openapi.yaml}, not a reuse
     * of {@code ObPrereqCommentListResponse}.
     */
    public record PortalPrereqComment(long id, long prereqTaskId, ObPrereqActorType authorType,
                                      PortalUserRef staffAuthor, PortalContactRef clientAuthor,
                                      String body, boolean isSystem, Instant createdAt) {
    }

    // ── CP-03's home read ────────────────────────────────────────────────

    public record PortalOnboardingHome(long obClientId, String clientName,
                                       PortalPrereqs prereqs, List<PortalJourneyStrip> journeys) {
    }

    // ── requests ─────────────────────────────────────────────────────────

    public record PortalPrereqSubmitRequest(@Size(max = 4000) String note) {
    }

    public record PortalPrereqCommentCreateRequest(@NotBlank @Size(max = 4000) String body) {
    }

    // ── envelopes ────────────────────────────────────────────────────────

    public record PortalOnboardingHomeResponse(PortalOnboardingHome data) {
    }

    public record PortalPrereqTaskDetailResponse(PortalPrereqTaskDetail data) {
    }

    public record PortalPrereqTaskResponse(PortalPrereqTask data) {
    }

    public record PortalPrereqCommentResponse(PortalPrereqComment data) {
    }

    public record PortalPrereqCommentListResponse(List<PortalPrereqComment> data, PageMeta meta) {
    }

    public record PortalAttachmentResponse(PortalSubmissionFile data) {
    }

    // ── CP-05 · the sign-off list ───────────────────────────────────────────

    /**
     * One row of CP-05's list — pending or past, the screen sorts.
     *
     * <p>{@code stepTitle} is {@code null} exactly when {@code kind} is
     * {@code GO_LIVE} ({@link ObSignoffKind}'s own note); {@code productName}
     * follows the same {@code null}-for-{@code GO_LIVE} shape one join further
     * out, since a go-live sign-off names the journey rather than one product's
     * step.
     *
     * <p>No token, no hash, no OTP state, no IP or user agent — see
     * {@link PortalSignoffReader}'s class note on why. {@code sentToEmail} is
     * the address the link went to, served so a client who has mislaid the
     * email knows which inbox to search; it is this client's own contact, not
     * a colleague's.
     *
     * <p>{@code hasCertificate} mirrors {@code PortalTicketDtos}'s pattern of
     * deriving a boolean from a storage key rather than exposing the key
     * itself. It reads {@code false} for every row today — {@code
     * pdf_storage_key} is never written until B-116 (the acceptance PDF) is
     * built — which is the accurate answer rather than a placeholder, on
     * {@code ObSignoffAcceptService.wentLive}'s own precedent for the same
     * situation one field over.
     *
     * <h2>Why there is no deep-link URL on this row</h2>
     *
     * <p>{@code ob_signoffs.token_hash} is a one-way hash — {@code
     * ObSignoffTokens}'s whole point is that "our own database must not be
     * able to yield a working link". So no read, here or anywhere, can ever
     * hand back the plaintext a PENDING row's email carries, and this DTO does
     * not pretend otherwise with a link that would 401. Plan §8's "deep-linking
     * into the same flow" is served by routing the client to {@code /signoff}
     * (OB-09, unchanged) and by naming the inbox to check — see
     * {@code PortalSignoffListPage}'s own note for the frontend half of this
     * decision, and this task's STREAM-C-TICKETS.md entry for why a
     * self-service resend was not built to close the gap instead.
     */
    public record PortalSignoff(
            long id,
            ObSignoffKind kind,
            ObSignoffStatus status,
            String productName,
            String stepTitle,
            Instant requestedAt,
            Instant tokenExpiresAt,
            String sentToEmail,
            Instant signedAt,
            String signedName,
            String acceptanceNote,
            Instant objectedAt,
            String objectionNote,
            boolean hasCertificate) {
    }

    public record PortalSignoffListResponse(List<PortalSignoff> data) {
    }
}
