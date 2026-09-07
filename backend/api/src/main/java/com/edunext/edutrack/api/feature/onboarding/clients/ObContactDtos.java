package com.edunext.edutrack.api.feature.onboarding.clients;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * B-103 · the wire shape for the three SPOC operations, matching
 * {@code contracts/openapi.yaml}'s {@code ObContactUpsertRequest}.
 *
 * <p>The read shape is {@code ObClientDtos.ObContact}, unchanged in name and
 * widened by this task: every one of these operations answers with the whole
 * client document rather than the contact it wrote, so there is no
 * contact-shaped response to declare.
 *
 * <h2>A record, where {@code ObClientUpdateRequest} needed a class</h2>
 *
 * <p>That one is partial by field and has to tell an omitted field from an
 * explicit {@code null}, which a record cannot do. This one is <b>the whole
 * representation</b> — the SPOC row editor sends every field on every save, so
 * an absent one is a cleared one, which is {@code ContactWriteRequest}'s call
 * one module over and needs no presence tracking to express.
 *
 * <p>The two {@code Boolean} fields are the exception and are boxed on purpose:
 * absent means <em>unchanged</em> for {@code isActive} and <em>false</em> for
 * {@code whatsappOptIn}, and a primitive could not tell either from a sent
 * {@code false}. {@code isPrimary} is {@code @NotNull} instead — a SPOC form
 * that does not say whether this is the primary is a form that has not been
 * filled in, and defaulting it either way decides the client's most consequential
 * contact by omission.
 */
final class ObContactDtos {

    private ObContactDtos() {
    }

    /** {@code ObContactUpsertRequest}. */
    record ObContactUpsertRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 120) String designation,
            @NotBlank @Email @Size(max = 200) String email,
            @Size(max = 32) String phone,
            Boolean whatsappOptIn,
            @Size(max = 32) String whatsappOptInSource,
            @NotNull Boolean isPrimary,
            Boolean isActive) {

        boolean optedIn() {
            // Consent defaults to withheld, which is the recoverable direction:
            // a SPOC wrongly marked as not consenting is asked again, and one
            // wrongly marked as consenting is messaged without permission.
            return Boolean.TRUE.equals(whatsappOptIn);
        }

        boolean primary() {
            return Boolean.TRUE.equals(isPrimary);
        }

        /** Absent means unchanged on a PATCH and active on a POST — the caller decides which. */
        boolean activeOr(boolean fallback) {
            return isActive == null ? fallback : isActive;
        }
    }
}
