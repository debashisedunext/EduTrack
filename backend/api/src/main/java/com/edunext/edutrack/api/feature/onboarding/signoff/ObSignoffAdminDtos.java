package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The staff sign-off surface's wire shapes — {@code listObSignoffs},
 * {@code requestObSignoff}, {@code getObSignoff}, {@code resendObSignoff} and
 * {@code cancelObSignoff}, all five of which the contract has described since
 * A-118 and none of which had a controller until now.
 *
 * <h2>Every secret on the row is absent, and that is structural</h2>
 *
 * <p>{@code tokenHash}, {@code otpHash}, {@code otpExpiresAt} and
 * {@code otpAttempts} appear on no record in this file. The contract states the
 * rule once for the whole document — "nothing here exposes {@code tokenHash} or
 * {@code otpHash}, on any response" — and A-107's reasoning is that a SHA-256 on
 * the wire hands an attacker with an offline dictionary the one value the column
 * was hashed to deny them. The omission lives in the {@code SELECT} as well as
 * here ({@link ObSignoffAdminRepository}), on {@code PortalSignoffReader}'s own
 * precedent: a projection that never reads a column cannot leak it through a
 * serializer somebody edits later.
 *
 * <p>{@code pdf_storage_key} is absent for the same reason in a different key —
 * it is reduced to {@link ObSignoff#hasCertificate()}, a boolean, so a caller
 * learns that a certificate exists without learning where in object storage to
 * find it. {@code getObSignoffCertificate} stays the only way to reach the
 * bytes.
 *
 * <h2>Duplicated refs, on this module's standing precedent</h2>
 *
 * <p>{@link UserRef} and {@link ObContact} are spelled again here rather than
 * imported from {@code ObClientDtos}, which is package-private in
 * {@code feature/onboarding/clients/} and would have to be widened to be shared.
 * {@code ObAttachmentDtos} and {@code ObClientDtos} both record the same choice
 * against {@code ObEscalationDtos.UserRef}: feature packaging means a DTO
 * belongs to its feature, and widening one to save a record is how a shared
 * {@code dto/} package starts.
 */
final class ObSignoffAdminDtos {

    private ObSignoffAdminDtos() {
    }

    /** {@code UserRef} — duplicated per package on {@code ObEscalationDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        /** Null in, null out: {@code requested_by} is nullable and a simulated row has none. */
        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * {@code ObContact} — the contract's shape, which is
     * {@code ObClientDtos.ObContact}'s shape.
     *
     * @param isPrimary read from {@code is_primary_key}, the generated column
     *                  {@code uq_ob_client_contacts_primary} is on, rather than
     *                  from {@code is_primary}. The generated one is 1 only
     *                  while the contact is <em>also</em> active, so this
     *                  reports the fact the database enforces instead of a
     *                  second one that can disagree with it — B-103's reasoning
     *                  in {@code ObClientReadRepository}, followed rather than
     *                  re-derived.
     */
    record ObContact(long id, String name, String designation, String email, String phone,
                     boolean whatsappOptIn, Instant whatsappOptInAt, String whatsappOptInSource,
                     boolean isPrimary, boolean isActive) {
    }

    /**
     * {@code ObSignoff} — one row of {@code ob_signoffs}, minus every secret on it.
     *
     * @param tokenExpiresAt when the emailed link stops working. On the response
     *                       so OB-05 can say so; the token it belongs to is on no
     *                       response in the contract, and cannot be — only the
     *                       SHA-256 is stored.
     * @param hasCertificate whether {@code getObSignoffCertificate} will return a
     *                       PDF, derived from {@code pdf_storage_key} being set.
     *                       The key itself never travels.
     */
    record ObSignoff(long id, long obClientId, long journeyId, Long stepId,
                     ObSignoffKind kind, ObSignoffStatus status,
                     UserRef requestedBy, Instant requestedAt,
                     ObContact sentToContact, Instant tokenExpiresAt,
                     Instant signedAt, Instant objectedAt, boolean hasCertificate) {
    }

    /**
     * {@code ObSignoffDetail} — the acceptance evidence, on top of the row.
     *
     * <p>{@code @JsonUnwrapped} rather than thirteen repeated components, on
     * {@code ObNotificationDtos.Meta}'s own precedent in this module: the
     * contract composes this with {@code allOf}, and a flattened copy would be
     * a second list of the same fields that drifts the first time one is added
     * to only one of them.
     *
     * <p>All four added fields are the legal record PHASE-2-BUILD-PLAN decision
     * 5 chose over statutory e-sign, and no operation in this file writes any of
     * them. They are populated by {@code ObSignoffAcceptService} from the
     * request that carried the acceptance, and read-only everywhere after.
     */
    record ObSignoffDetail(@JsonUnwrapped ObSignoff signoff,
                           ObContact signedByContact,
                           String signedIp,
                           String signedUserAgent,
                           String objectionNote) {
    }

    /**
     * {@code ObSignoffRequestBody}.
     *
     * <p>{@code sentToContactId} is required rather than defaulted to the
     * primary SPOC, and the contract says why in as many words: "the person who
     * signs off a data migration is frequently not the person who signs the
     * contract, and a default that is usually right is one nobody checks".
     *
     * <p>{@code stepId} is validated against {@code kind} in the service rather
     * than by an annotation — {@code ck_ob_signoffs_step_matches_kind} is a
     * two-field rule, and a cross-field constraint stated in Java beside the
     * database's own is the pair that drifts.
     */
    record ObSignoffRequestBody(@NotNull ObSignoffKind kind,
                                Long stepId,
                                @NotNull Long sentToContactId) {
    }

    /**
     * {@code ObSignoffCancelRequest}.
     *
     * <p>The reason is mandatory because the row survives the withdrawal: the
     * contract keeps a cancelled sign-off as {@code CANCELLED} rather than
     * deleting it, precisely so staff can answer "we asked and then withdrew"
     * later, and a withdrawal with no reason cannot answer the half of that
     * question a client actually asks.
     */
    record ObSignoffCancelRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    record ObSignoffResponse(ObSignoff data) {
    }

    record ObSignoffDetailResponse(ObSignoffDetail data) {
    }

    record ObSignoffListResponse(List<ObSignoff> data, PageMeta meta) {
    }
}
