package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * B-117 · the wire shapes for {@code objectObSignoff}, matching the contract
 * A-118 declared for it.
 *
 * <p>The session token is a body field, on {@link PublicSignoffAcceptDtos}'s
 * own precedent and the same reason: a URL carrying a credential lands in
 * browser history, in the {@code Referer} of every asset the page loads, and
 * in the access log of everything in between.
 */
final class PublicSignoffObjectDtos {

    private PublicSignoffObjectDtos() {
    }

    @Schema(name = "ObSignoffObjectRequest")
    record ObjectRequest(
            @NotBlank
            @Size(max = 200)
            String sessionToken,

            /** Mandatory — the contract's own line: "an objection with no
             * reason guarantees a second round trip". */
            @NotBlank
            @Size(max = 2000)
            String note
    ) {
    }

    /**
     * The contract's {@code ObSignoff} — narrower than {@link
     * PublicSignoffAcceptDtos.SignoffDetail}, because {@code objectObSignoff}
     * returns the plain schema rather than {@code ObSignoffDetail}. No
     * {@code signedByContact}, {@code signedIp}, {@code signedUserAgent} or
     * {@code objectionNote} — none of those are on {@code ObSignoff} either.
     *
     * <p>{@code requestedBy} is always null, on {@link
     * PublicSignoffAcceptDtos.SignoffDetail}'s own reasoning: it names a
     * member of our staff, and this response goes to a customer over an
     * unauthenticated surface.
     */
    @Schema(name = "ObSignoff")
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
            PublicSignoffAcceptDtos.Contact sentToContact,
            Instant tokenExpiresAt,
            Instant signedAt,
            Instant objectedAt,

            @Schema(description = "Whether getObSignoffCertificate will return a PDF. "
                    + "Derived from pdf_storage_key being set; the key itself is never on the wire.")
            boolean hasCertificate
    ) {
    }

    record SignoffResponse(SignoffDetail data) {
    }
}
