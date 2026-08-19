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
 * C-048 · {@code POST /tickets/{ticketId}/force-move}, per
 * {@code contracts/openapi.yaml} — blueprint §2's "Force-move ribbon
 * backwards" row and §4A.1's {@code OVERRIDE} action.
 *
 * <p>{@code ticket.force_move} is Admin and PM's alone
 * ({@code V20260806_0900}) — unlike {@code ticket.handoff}/{@code ticket.rework},
 * which every role holds and which {@code StageOwnership.mayAdvance} (C-043)
 * narrows per ticket, the capability itself is the whole authorisation
 * question here. Every caller who reaches {@link ForceMoveService} already
 * holds a role {@code StageOwnership.mayAdvance} would admit regardless of
 * assignment, so the golden rule's own refusal is unreachable through this
 * route in practice; it is still mapped by {@link ForceMoveExceptionHandler},
 * on {@code HandoffExceptionHandler}'s own precedent for a defensive route
 * rather than an unreachable one.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/force-move")
@Tag(name = "ribbon")
class ForceMoveController {

    private final ForceMoveService service;

    ForceMoveController(ForceMoveService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.force_move')")
    @Operation(operationId = "forceMoveTicket",
            summary = "Force-move to any stage — PM and Admin only (C-048)",
            description = """
                    Any stage to any stage — forward, backward or sideways — logged as \
                    `OVERRIDE` rather than as whichever direction it happens to move. \
                    `toStageCode` is always required; unlike `rework`/`skip` there is no \
                    default destination. `reason` is mandatory so the override is \
                    self-explaining in the ribbon's history the way a rework's is.""")
    ForceMoveDtos.RibbonResponse forceMove(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody ForceMoveDtos.ForceMoveRequest request) {

        return service.forceMove(caller, ticketId, request);
    }
}
