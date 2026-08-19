package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The contract's {@code TicketCreateRequest} and {@code TicketPatchRequest}.
 *
 * <p>The bounds are the schema, not belt-and-braces — PLAN.md §2.2 (D-4).
 * springdoc turns them into {@code minLength}/{@code maxLength}/{@code required}
 * and orval turns that into Zod, so both sides enforce one rule written once.
 * Changing a number here changes the browser's validation too.
 */
final class TicketCreateDtos {

    record CreateRequest(
            @NotNull Long projectId,

            @NotBlank @Size(min = 3, max = 300) String title,

            @Size(max = RichTextSanitizer.MAX_LENGTH) String description,

            @NotNull Integer taskTypeId,

            @Schema(description = "§7.5's Where it happened. Optional here and mandatory on the form "
                    + "for bug-type task types only — the rule lives in the form because a change "
                    + "request may genuinely span three modules, and a draft is saved precisely "
                    + "because the answer is not known yet.")
            Integer moduleId,

            @Size(max = 120) String screenName,
            @Size(max = 120) String feature,

            @Schema(description = "Sanitised HTML — PLAN.md §3.9. The server sanitises on write; the "
                    + "client's copy is advice.")
            @Size(max = RichTextSanitizer.MAX_LENGTH) String stepsToGenerate,

            @NotBlank String level,

            Long clientId,
            Long clientContactId,

            @Schema(description = "Ignored on write. §4B.2: the server derives it from whether clientId "
                    + "and clientContactId are both set, never from what the caller sends. Accepted "
                    + "only so a client round-tripping a Ticket it read back is not refused for an "
                    + "extra field.")
            Boolean isClientRaised,

            Long assigneeId,
            List<Long> watcherIds,
            BigDecimal estimatedHrs,

            @Schema(description = "Omit to have it computed from the SLA policy against the working "
                    + "calendar. Supplying it requires PM or Admin.")
            Instant plannedCloseDate,

            Boolean saveAsDraft) {
    }

    /**
     * Every field optional — a PATCH says what changed and nothing else.
     *
     * <p>Absent and explicitly null are different here and the service treats
     * them so: absent leaves the column alone, null clears it. That distinction
     * is why §7.5's four fields can be un-set at all, which matters for a module
     * picked in error on a ticket that turns out to span three.
     */
    record PatchRequest(
            @Size(min = 3, max = 300) String title,
            @Size(max = RichTextSanitizer.MAX_LENGTH) String description,
            Integer moduleId,
            @Size(max = 120) String screenName,
            @Size(max = 120) String feature,
            @Size(max = RichTextSanitizer.MAX_LENGTH) String stepsToGenerate,
            BigDecimal estimatedHrs) {
    }

    record TicketResponse(TicketWire.Ticket data) {
    }

    private TicketCreateDtos() {
    }
}
