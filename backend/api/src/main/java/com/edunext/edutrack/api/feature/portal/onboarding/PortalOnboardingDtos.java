package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.domain.onboarding.ObPrereqSubmittedVia;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /**
     * C-126 · one open escalation on this step, or {@code null} — the shape
     * {@link PortalStepDot#openEscalation()}'s own slot named ahead of time.
     * Deliberately three fields: enough for CP-03 to say "you raised this" and
     * disable the control, nothing about who it went to or who will resolve it
     * — the same narrowing plan §9's row already applies to the rest of this
     * dot.
     */
    public record PortalOpenEscalation(long id, String comment, Instant raisedAt) {
    }

    // ── CP-03 · the read-only journey accordions (this is the part plan §9/§11 narrows) ──

    /**
     * One step dot. Deliberately narrow — id, sequence, name, status, rag,
     * the dependency badge — because this is the whole of what plan §9's CP-03
     * row permits: "step status only; no owner names, internal comms, or
     * block reasons". No TAT figures either: plan §11's never-visible list
     * names "TAT internals" for the client explicitly.
     *
     * @param openEscalation C-126's own slot, filled: the client's own open
     *                       escalation on this step, if any, or {@code null}.
     *                       Was always {@code null} and untyped ({@code
     *                       Object}) before this task — see {@link
     *                       PortalOpenEscalation}'s own note.
     */
    public record PortalStepDot(long id, int sequence, String name, String status, String rag,
                                Long dependsOnStepId, PortalOpenEscalation openEscalation) {

        static PortalStepDot of(long id, int sequence, String name, String status, String rag,
                                Long dependsOnStepId, PortalOpenEscalation openEscalation) {
            return new PortalStepDot(id, sequence, name, status, rag, dependsOnStepId, openEscalation);
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

    /** C-126 · CP-03's Escalate control. The comment is mandatory — plan §4/§9's own rule. */
    public record PortalEscalationRaiseRequest(@NotBlank @Size(max = 2000) String comment) {
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

    /**
     * C-126 · what CP-03 gets back after escalating — {@code
     * ObClientEscalationService.RaiseResult}'s three public fields, plus
     * whether this call actually raised it or found one already open (a
     * double-click, or a page the client had open in two tabs).
     */
    public record PortalClientEscalation(long id, String comment, Instant raisedAt, boolean isNew) {
    }

    public record PortalClientEscalationResponse(PortalClientEscalation data) {
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
     * <h2>Why there is still no deep-link URL on this row</h2>
     *
     * <p>{@code ob_signoffs.token_hash} is a one-way hash — {@code
     * ObSignoffTokens}'s whole point is that "our own database must not be able
     * to yield a working link". So no read, here or anywhere, can ever hand
     * back the plaintext a PENDING row's email carries, and this DTO does not
     * pretend otherwise with a link that would 401.
     *
     * <p><b>It no longer needs to.</b> A {@code PENDING} row now links to
     * {@link PortalSignoffReview} on this same authenticated surface, where the
     * client accepts or objects without the emailed link or the OTP at all —
     * see {@code PortalSignoffDecisionService} for why a portal session is a
     * stronger proof of the same two facts that flow establishes, not a weaker
     * one. {@code sentToEmail} stays, because the emailed link still exists and
     * still works, and a client part-way through it is owed the inbox it went
     * to.
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

    // ── CP-05 · deciding a sign-off in the portal ───────────────────────────

    /**
     * One Task List row on the review screen.
     *
     * <p>{@code isDone} means <b>answered</b>, not answered True — C-111's
     * distinction, restated on A-121's own checklist DTO and again here so a
     * renderer cannot quietly turn it into a tick. The screen shows "recorded"
     * for the same reason.
     */
    public record PortalSignoffChecklistItem(long id, int sequence, String label,
                                             boolean isMandatory, boolean isDone) {
    }

    /**
     * What the client reads before deciding — the portal's own OB-09.
     *
     * <p>Served for any status this client owns rather than {@code PENDING}
     * only, with {@code canDecide} carrying the difference: a sign-off staff
     * withdrew while the page was loading should read as withdrawn, not 404 on
     * a row the client was looking at a moment ago.
     *
     * <p>Deliberately narrower than the public page's {@code ObSignoffSession}:
     * no session token (the portal has no session to hand out — see
     * {@code PortalSignoffDecisionService} on why the minted one never leaves
     * that class), and no {@code sentToContact} card, which tells a contact
     * their own details back.
     */
    public record PortalSignoffReview(
            long id,
            ObSignoffKind kind,
            ObSignoffStatus status,
            String clientName,
            String productName,
            String stepTitle,
            Instant requestedAt,
            boolean canDecide,
            boolean csatOffered,
            List<PortalSignoffChecklistItem> checklist) {
    }

    public record PortalSignoffReviewResponse(PortalSignoffReview data) {
    }

    /**
     * Accepting.
     *
     * <p>{@code acceptedName} is mandatory and is not defaulted from the
     * contact row, on {@code PublicSignoffAcceptDtos.AcceptRequest}'s own
     * reasoning, which applies here word for word: "a name the person entered
     * themselves is what distinguishes acceptance from a click", and a
     * defaulted one produces a record that reads as though somebody typed their
     * name when nobody did. Being authenticated does not change that — it
     * establishes <em>which account</em> acted, not that a human put their name
     * to it.
     */
    public record PortalSignoffAcceptRequest(
            @NotBlank @Size(max = 160) String acceptedName,
            @Size(max = 2000) String note) {
    }

    /**
     * Objecting.
     *
     * <p>The note is mandatory where the acceptance note is optional — the
     * contract's own line, kept: "an objection with no reason guarantees a
     * second round trip".
     */
    public record PortalSignoffObjectRequest(
            @NotBlank @Size(max = 2000) String note) {
    }

    /**
     * How it was decided, and what our own side still owes.
     *
     * <p>{@code stepCompleted} false is a <b>normal outcome</b>, not an error,
     * and this shape exists so the portal can render it as one:
     * {@code PublicSignoffAcceptDtos.AcceptResult}'s contract, carried across
     * unchanged. The acceptance is recorded and the row is {@code SIGNED}
     * either way; {@code gateFailures} names what we have not finished, which
     * is ours to fix and not something to ask the client to click again for.
     *
     * <p>Both fields are empty on an objection — nothing completes, and the
     * step reverting is staff's business, not a gate the client failed.
     */
    public record PortalSignoffDecision(
            long id,
            ObSignoffStatus status,
            Instant signedAt,
            String signedName,
            String acceptanceNote,
            Instant objectedAt,
            String objectionNote,
            boolean stepCompleted,
            List<String> gateFailures,
            boolean clientWentLive,
            boolean hasCertificate,
            boolean csatOffered) {
    }

    public record PortalSignoffDecisionResponse(PortalSignoffDecision data) {
    }

    /**
     * B-119's one-question go-live survey, answered in the portal.
     *
     * <p>{@code score} is 1–5 and mandatory; the comment is optional, because
     * the survey's whole design is that it costs one tap. A client who closes
     * the tab has still gone live — the acceptance is already final before this
     * is ever offered.
     */
    public record PortalCsatRequest(
            @NotNull @Min(1) @Max(5) Integer score,
            @Size(max = 2000) String comment) {
    }
}
