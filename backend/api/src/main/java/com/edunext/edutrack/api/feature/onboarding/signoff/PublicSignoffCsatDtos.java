package com.edunext.edutrack.api.feature.onboarding.signoff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B-119 · the wire shape for {@code submitObCsat}, matching the contract's
 * {@code ObCsatRequest}.
 *
 * <p>The session token is a body field, on {@code PublicSignoffAcceptDtos}'
 * own precedent for the same reason the contract states once above the whole
 * public tree: a URL carrying a credential lands in browser history, in the
 * {@code Referer} of every asset the page loads, and in the access log of
 * everything in between.
 *
 * <p>No response record. {@code submitObCsat}'s {@code 200} is "Recorded.
 * Thank-you state." with no schema — the contract has nothing to say back
 * beyond the status code, and a record with no fields would be a shape that
 * exists only to be empty.
 */
final class PublicSignoffCsatDtos {

    private PublicSignoffCsatDtos() {
    }

    @Schema(name = "ObCsatRequest")
    record CsatRequest(
            @NotBlank
            @Size(max = 200)
            String sessionToken,

            /**
             * Plan §1.1 #9's one question, five points. {@code @NotNull}
             * rather than a primitive {@code int} field: a caller who omits
             * the field entirely must fail validation with a clear "score is
             * required" rather than silently becoming a score of zero, which
             * {@code @Min(1)} would otherwise reject with a less honest
             * message.
             */
            @NotNull
            @Min(1)
            @Max(5)
            Integer score,

            @Size(max = 2000)
            String comment
    ) {
    }
}
