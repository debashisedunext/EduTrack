package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-112 · {@code /onboarding/journey-steps/{stepId}/communications} per
 * {@code contracts/openapi.yaml} — one service's own timeline (plan §6).
 *
 * <h2>{@code GET} and {@code POST}. No {@code PUT}, {@code PATCH} or
 * {@code DELETE}, ever</h2>
 *
 * <p>This class is layer 2 of CLAUDE.md's four. {@code
 * ob_step_communications} is append-only, and the guarantee is worth as much
 * as the weakest layer: a mutating route here would defeat the trigger below
 * it, because a route that exists is a route somebody wires a button to.
 * {@code ContractConformanceTest} independently refuses a mutation verb on an
 * append-only path, and this file is the reason it never has to.
 *
 * <h2>Auth: {@code isAuthenticated()} only — the interim state every
 * onboarding controller in this codebase declares</h2>
 *
 * <p>{@code ModuleAccessGuard} is written but not yet wired into {@code
 * SecurityConfig} ({@code ObJourneyTemplateController}'s own javadoc states
 * the position first). Row visibility is enforced inside {@link
 * ObCommunicationService} via {@link ObCommunicationScope} — A-112's rule,
 * applied so that a caller with no onboarding standing sees an empty timeline
 * on the read and a 404 on the append, rather than a 403 on either.
 *
 * <p>{@code Idempotency-Key} is accepted on the write and not yet honoured —
 * every other onboarding route's identical note on this header; the 24-hour
 * replay store does not exist yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-steps/{stepId}/communications")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObStepCommunicationController {

    private final ObCommunicationService service;

    ObStepCommunicationController(ObCommunicationService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObStepCommunications",
            summary = "What was said about this service, and to whom (plan §6)")
    ObCommunicationDtos.ObStepCommunicationListResponse list(
            Authentication caller,
            @PathVariable long stepId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.listForStep(scopeOf(caller), stepId, cursor, limit);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createObStepCommunication",
            summary = "Record a call, mail or meeting against this service")
    ObCommunicationDtos.ObStepCommunicationResponse create(
            Authentication caller,
            @PathVariable long stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObCommunicationDtos.ObStepCommunicationCreateRequest request) {
        return new ObCommunicationDtos.ObStepCommunicationResponse(
                service.record(scopeOf(caller), stepId, userId(caller), request));
    }

    private static ObCommunicationScope scopeOf(Authentication caller) {
        return CallerIdentity.of(caller).map(ObCommunicationScope::of)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-communications route reached with no resolvable caller identity"));
    }

    private static long userId(Authentication caller) {
        return CallerIdentity.of(caller).map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-communications route reached with no resolvable caller identity"));
    }
}
