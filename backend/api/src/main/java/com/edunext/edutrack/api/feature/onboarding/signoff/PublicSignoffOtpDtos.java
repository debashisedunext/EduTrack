package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * A-121 · the wire shapes for the two OTP operations, matching the contract
 * A-118 declared for them.
 *
 * <p><b>The token is a body field on both, never a path or query parameter.</b>
 * The contract states the reason once above the whole public tree: a URL
 * carrying it lands in browser history, in the {@code Referer} of every asset
 * the page loads, and in the access log of everything between the client and
 * us. {@code ob_signoffs} stores only a SHA-256 so that our own database cannot
 * yield a working link; putting the plaintext in a URL would give it away at
 * the other end.
 */
final class PublicSignoffOtpDtos {

    private PublicSignoffOtpDtos() {
    }

    @Schema(name = "ObSignoffTokenRequest")
    record TokenRequest(
            @NotBlank
            @Size(max = 200)
            String token
    ) {
    }

    @Schema(name = "ObSignoffOtpVerifyRequest")
    record OtpVerifyRequest(
            @NotBlank
            @Size(max = 200)
            String token,

            /**
             * Six digits. Rejected here before the code is compared, so a
             * malformed value costs no attempt from the persisted budget — the
             * lockout is for wrong guesses, and a client whose browser sent a
             * blank field has not made one.
             */
            @NotBlank
            @Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
            String otp
    ) {
    }

    /**
     * What OB-09 renders, returned only after the code is proved.
     *
     * <p>Nothing here is reachable from the link alone — the contract's fourth
     * standing property: "a link on its own proves possession of an email; it
     * does not prove identity, and plan §8 makes the OTP the thing that does".
     */
    @Schema(name = "ObSignoffSession")
    record Session(
            @Schema(description = "Opaque, short-lived, and good for this one sign-off. "
                    + "Not a JWT and not a principal.")
            String sessionToken,

            Instant expiresAt,
            ObSignoffKind kind,
            String obClientName,
            String productName,
            String stepTitle,

            @Schema(description = "What the client is being asked to accept. "
                    + "Empty for a GO_LIVE sign-off, which is about the journey rather than one service.")
            List<ChecklistItem> checklist,

            @Schema(description = "Whether submitObCsat will be accepted after acceptance.")
            boolean csatOffered
    ) {
    }

    /**
     * One Task List entry as the client sees it.
     *
     * <p>{@code isDone} means <b>answered</b>, not answered True — C-111's
     * distinction, and the completion gate's: a screen reading this as "ticked"
     * would show an item outstanding that the server is willing to complete
     * over.
     *
     * <p><b>Narrower than the contract's {@code ObJourneyStepItem}, on
     * purpose, and this is a deviation worth reconciling rather than
     * hiding.</b> That schema also carries {@code doneBy} as a {@code UserRef},
     * which names an EduTrack staff member. Every other route carrying it
     * answers a colleague; this one answers a customer's SPOC over an
     * unauthenticated surface, and "which of our people ticked this box" is
     * not a fact the sign-off page needs to render. The six required fields are
     * all served, so nothing the contract marks required is missing — but the
     * contract should grow its own public item schema rather than reuse the
     * staff one, and that is a contract change with a client regeneration
     * behind it. Raised on the PR.
     */
    record ChecklistItem(
            Long id,
            Long stepId,
            int sequence,
            String label,
            boolean isMandatory,
            boolean isDone,
            Instant doneAt
    ) {
    }

    record SessionResponse(Session data) {
    }
}
