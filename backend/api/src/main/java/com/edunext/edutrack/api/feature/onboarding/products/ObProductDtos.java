package com.edunext.edutrack.api.feature.onboarding.products;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A-124 · the wire shapes for {@code /onboarding/products}, matching the
 * contract A-118 declared for them.
 */
final class ObProductDtos {

    private ObProductDtos() {
    }

    @Schema(name = "ObProductWriteRequest")
    record WriteRequest(

            /**
             * Immutable once a client has bought the product, which the service
             * enforces rather than this record — a caller who sends the same
             * code back on a PATCH is not making a mistake, and refusing that
             * would make the obvious round-trip edit fail.
             */
            @NotBlank
            @Size(max = 32)
            @Pattern(regexp = "^[A-Z0-9_-]+$",
                    message = "must be upper-case letters, digits, underscore or hyphen")
            String code,

            @NotBlank
            @Size(max = 160)
            String name,

            /** Absent means active — the contract's default, restated here. */
            Boolean isActive
    ) {
        boolean activeOrDefault() {
            return isActive == null || isActive;
        }
    }

    @Schema(name = "ObProduct")
    record Product(
            Long id,
            String code,
            String name,
            boolean isActive,

            @Schema(description = "Whether an active journey template binds to this product. "
                    + "The OB-04 picker's gate: a product with none cannot be bought, because "
                    + "a purchase with no template to instantiate boards a client into nothing.")
            boolean hasActiveTemplate,

            /**
             * Null when there is no active template, and {@code 0} when there is
             * one with no steps yet — two different facts, and collapsing them
             * would have the OB-07 card claim a product costs nothing when what
             * is true is that nobody has said yet.
             */
            @Schema(nullable = true,
                    description = "Sum of the active template's step TATs, in working days — what a "
                            + "journey for this product costs, shown on the OB-07 card. Null when "
                            + "there is no active template.")
            Integer totalTatDays,

            @Schema(description = "Journeys instantiated from this product, across all clients. "
                    + "Part of the ETag, because it is what a retire decision is made against.")
            int journeyCount
    ) {
    }

    record ObProductListResponse(List<Product> data) {
    }

    record ObProductResponse(Product data) {
    }
}
