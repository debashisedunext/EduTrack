package com.edunext.edutrack.api.feature.onboarding.implementationstages;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The wire shapes for {@code /onboarding/implementation-stages}, matching the
 * contract.
 */
final class ObImplementationStageDtos {

    private ObImplementationStageDtos() {
    }

    @Schema(name = "ObImplementationStageWriteRequest")
    record WriteRequest(

            @NotBlank
            @Size(max = 120)
            String name,

            /**
             * The position the caller wants this stage to end up at, 1-based —
             * <b>not</b> a column value to store as sent.
             *
             * <p>This is the whole of the "change sequence" requirement, and
             * the difference matters: the service moves the row to this
             * position and renumbers the rest, so a caller typing {@code 2}
             * into the fifth row gets what they meant rather than two rows
             * sharing a 2. Out-of-range values are clamped rather than
             * refused — {@code 0} means first and {@code 99} means last, which
             * is what somebody typing them into a position box intends.
             *
             * <p>Absent on a create appends to the end; absent on a PATCH
             * leaves the position alone. Both are the "I am not saying
             * anything about order" answer, which is different from any number
             * this field could carry.
             */
            @Min(1)
            Integer sequence,

            /** Absent means active — the contract's default, restated here. */
            Boolean isActive
    ) {
        boolean activeOrDefault() {
            return isActive == null || isActive;
        }
    }

    @Schema(name = "ObImplementationStage")
    record Stage(
            Long id,
            String name,

            @Schema(description = "1-based display position, contiguous across the whole master. "
                    + "The server owns this: it is renumbered on every write, so the value read "
                    + "back after a save is not necessarily the one that was sent.")
            int sequence,

            @Schema(description = "Retired stages stay in the list, in their own slot, and drop "
                    + "out of the pickers. There is no delete.")
            boolean isActive
    ) {
    }

    record ObImplementationStageListResponse(List<Stage> data) {
    }

    record ObImplementationStageResponse(Stage data) {
    }
}
