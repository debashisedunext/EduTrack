package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * C-115 · {@code /onboarding/escalations} per {@code contracts/openapi.yaml}
 * (OB-02, OB-10) — the internal escalation ladder C's scanner raises.
 *
 * <h2>Auth: {@code isAuthenticated()} only — the same interim state every
 * onboarding controller in this codebase declares</h2>
 *
 * <p>{@code ModuleAccessGuard} is written but not yet wired into {@code
 * SecurityConfig} ({@code ObJourneyTemplateController}'s own javadoc states
 * the position first). Row visibility is enforced inside {@link
 * ObEscalationService} via {@link ObEscalationScope} — A-112's rule, applied
 * to a list a caller with no onboarding standing sees as empty rather than
 * as a 403, exactly {@code ObNotificationController}'s own reasoning.
 *
 * <p>{@code Idempotency-Key} is accepted on the two write routes and not yet
 * honoured — every other onboarding route's identical note on this header;
 * the 24-hour replay store does not exist yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding/escalations")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObEscalationController {

    private final ObEscalationService service;

    ObEscalationController(ObEscalationService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObEscalations", summary = "The internal escalation ladder (OB-02, OB-10)")
    ObEscalationDtos.ObEscalationListResponse list(
            Authentication caller,
            @RequestParam(required = false) Long obClientId,
            @RequestParam(required = false) Long journeyId,
            @RequestParam(required = false) Long escalatedTo,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.list(scopeOf(caller), obClientId, journeyId, escalatedTo, level, state, cursor, limit);
    }

    @PostMapping(value = "/{escalationId}/acknowledge", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acknowledgeObEscalation", summary = "Say you have seen this rung (OB-02)")
    ObEscalationDtos.ObEscalationResponse acknowledge(
            Authentication caller, @PathVariable long escalationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return new ObEscalationDtos.ObEscalationResponse(
                service.acknowledge(scopeOf(caller), escalationId, userId(caller)));
    }

    @PostMapping(value = "/{escalationId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveObEscalation", summary = "Close a rung of the ladder (OB-02)")
    ObEscalationDtos.ObEscalationResponse resolve(
            Authentication caller, @PathVariable long escalationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObEscalationDtos.ObEscalationResolveRequest request) {
        return new ObEscalationDtos.ObEscalationResponse(
                service.resolve(scopeOf(caller), escalationId, userId(caller), request.note()));
    }

    private static ObEscalationScope scopeOf(Authentication caller) {
        return CallerIdentity.of(caller).map(ObEscalationScope::of)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-escalations route reached with no resolvable caller identity"));
    }

    private static long userId(Authentication caller) {
        return CallerIdentity.of(caller).map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-escalations route reached with no resolvable caller identity"));
    }
}
