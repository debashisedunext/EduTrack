package com.edunext.edutrack.api.feature.transitions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-047 · {@code POST /tickets/{ticketId}/skip-stage}, per
 * {@code contracts/openapi.yaml} — blueprint §4A.6's "skipping an optional
 * stage requires PM/Admin and a reason" and §2's "Skip a stage (with reason)"
 * row, which is ticked for Admin and PM alone.
 *
 * <p>{@code ticket.skip_stage} is Admin and PM's alone
 * ({@code V20260806_0900}) — a genuine capability rather than a row rule
 * wearing one, exactly like {@code ticket.force_move} and unlike
 * {@code ticket.handoff}/{@code ticket.rework}, which every role holds and
 * which {@code StageOwnership.mayAdvance} narrows per ticket. Both roles that
 * hold this one already satisfy {@code mayAdvance} regardless of assignment,
 * so the golden rule's refusal is defensive here rather than load-bearing; it
 * is still mapped by {@link SkipExceptionHandler}, on
 * {@code ForceMoveExceptionHandler}'s own precedent.
 *
 * <p>The mock refuses a non-PM caller with 422 rather than 403, which is why
 * the contract lists no 403 on this route. That difference does not survive
 * the real chain: {@code @PreAuthorize} answers 403 before the handler is
 * reached, and a caller who lacks the capability never gets as far as a
 * ticket-state answer. Flagged to Stream D rather than worked around — the
 * mock's 422 is the thing that is wrong, and it belongs to their file.
 *
 * <p>The path variable is the ticket <b>code</b>, on
 * {@code ReworkController}'s and {@code ForceMoveController}'s own note:
 * {@code CloseController} records {@code @PathVariable long ticketId} as a bug
 * found only by exercising the packaged app, and this route is new enough not
 * to repeat it.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/skip-stage")
@Tag(name = "ribbon")
class SkipController {

    private final SkipService service;

    SkipController(SkipService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.skip_stage')")
    @Operation(operationId = "skipStage",
            summary = "Skip an optional stage — PM and Admin only",
            description = """
                    Skips the stage the ticket is standing in and moves it on. \
                    `reason` is mandatory and `toStageCode` is where the ticket \
                    lands — the same meaning it carries on `/handoff`, `/rework` \
                    and `/force-move` — defaulting to the template's next stage.

                    The stage must be marked optional on the ticket's workflow \
                    template (§4A.6); a stage that is not is refused with 422, \
                    since the identical request would succeed from a stage that \
                    is. The segment renders struck-through with the reason on \
                    hover rather than disappearing — a stage that silently \
                    vanishes from the ribbon makes the history unreadable later.""")
    SkipDtos.RibbonResponse skip(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody SkipDtos.SkipRequest request) {

        return service.skip(caller, ticketId, request);
    }
}
