package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-115 · the wire shapes for {@code acceptObSignoff}, matching the contract
 * A-118 declared for it.
 *
 * <p>The session token is a body field, like the link token on A-121's two
 * operations and for the same reason the contract states once above the whole
 * public tree: a URL carrying a credential lands in browser history, in the
 * {@code Referer} of every asset the page loads, and in the access log of
 * everything in between.
 */
final class PublicSignoffAcceptDtos {

    private PublicSignoffAcceptDtos() {
    }

    @Schema(name = "ObSignoffAcceptRequest")
    record AcceptRequest(
            @NotBlank
            @Size(max = 200)
            String sessionToken,

            /**
             * <p>Typed by the signatory, and the contract says what it is for:
             * "a name the person entered themselves is what distinguishes
             * acceptance from a click". It is not defaulted from the contact
             * row — doing so would produce a record that reads as though
             * somebody typed their name when nobody did, which is the one thing
             * PHASE-2-BUILD-PLAN decision 5's recorded acceptance has to be
             * able to stand behind.
             */
            @NotBlank
            @Size(max = 160)
            String acceptedName,

            /**
             * Optional remark. Stored on {@code ob_signoffs.acceptance_note}
             * rather than dropped — see the B-115 migration for why it is not
             * {@code objection_note}.
             */
            @Size(max = 2000)
            String note
    ) {
    }

    /**
     * The contract's {@code ObSignoffAcceptResult}.
     *
     * <p><b>{@code stepCompleted} false is a normal outcome</b>, not an error,
     * and the response is shaped so OB-09 can render it as one: the acceptance
     * is recorded and the sign-off is {@code SIGNED} either way, and what
     * failed is our own completion gate. PHASE-2-BUILD-PLAN §3 #4 is the ruling
     * this implements — the prototype enforced three gates on complete and none
     * on accept, so a client could accept a service whose required documents
     * were never attached.
     */
    @Schema(name = "ObSignoffAcceptResult")
    record AcceptResult(
            SignoffDetail signoff,
            boolean stepCompleted,

            @Schema(description = "Stable reason codes for what the gate refused. "
                    + "Empty when stepCompleted is true.")
            List<String> gateFailures,

            boolean clientWentLive
    ) {
    }

    record AcceptResultResponse(AcceptResult data) {
    }

    /**
     * The contract's {@code ObSignoffDetail}, projected for this surface.
     *
     * <p><b>{@code requestedBy} is always null here, and that is a decision
     * rather than an omission.</b> The schema declares it nullable
     * ({@code oneOf UserRef | null}), so this is contract-valid — but the
     * reason it is null is worth stating: that field names the EduTrack staff
     * member who asked for the sign-off, and this response goes to a customer
     * over an unauthenticated surface. CP-03 hides owner names from the client
     * portal, where the reader has at least authenticated; handing one over
     * here would be the same disclosure with less standing behind it.
     *
     * <p>Same for {@code signedIp} and {@code signedUserAgent}: the contract
     * puts them on {@code ObSignoffDetail} for OB-05's evidence panel, where
     * staff read them. They are echoed here because they are the client's own
     * request metadata and the page shows "recorded from this device" — a
     * caller learns nothing about us from them.
     *
     * <p>This is narrower than the staff shape on purpose, and it is the same
     * call {@code PublicSignoffOtpDtos.ChecklistItem} recorded and raised:
     * <b>the contract should grow its own public sign-off schema rather than
     * reuse the staff one</b>, and that is a contract change with a client
     * regeneration behind it. Raised on the PR rather than made silently here.
     */
    @Schema(name = "ObSignoffDetail")
    record SignoffDetail(
            long id,
            long obClientId,
            long journeyId,
            Long stepId,
            ObSignoffKind kind,
            ObSignoffStatus status,

            @Schema(description = "Always null on this surface — it names a member of our staff.")
            Object requestedBy,

            Instant requestedAt,
            Contact sentToContact,
            Instant tokenExpiresAt,
            Instant signedAt,
            Instant objectedAt,

            @Schema(description = "Whether getObSignoffCertificate will return a PDF. "
                    + "Derived from pdf_storage_key being set; the key itself is never on the wire.")
            boolean hasCertificate,

            Contact signedByContact,
            String signedIp,
            String signedUserAgent,
            String objectionNote
    ) {
    }

    /**
     * The contract's {@code ObContact}, minus the consent columns.
     *
     * <p>Its own record rather than {@code ObClientDtos.ObContact}, which is
     * Stream B's client package and would have to be widened to be reachable.
     * The five fields the schema marks required are all served, so the wire
     * shape is compatible; what is left off is {@code whatsappOptIn} and its
     * two companions, which are consent facts staff record about a contact and
     * have no business on a page the contact themselves is reading.
     */
    record Contact(
            long id,
            String name,
            String designation,
            String email,
            String phone,
            boolean isPrimary,
            boolean isActive
    ) {
    }
}
