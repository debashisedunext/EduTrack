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
 * C-126 · {@code /onboarding/client-escalations} per {@code
 * contracts/openapi.yaml} (OB-02, OB-05) — the staff side of {@code
 * ob_client_escalations}. The client's own raise route lives under {@code
 * /portal/onboarding/**} on {@code PortalOnboardingController}; see that
 * class and this route's own contract comment for why.
 *
 * <h2>Auth: {@code isAuthenticated()} only</h2>
 *
 * <p>{@code ObEscalationController}'s own note applies verbatim: {@code
 * ModuleAccessGuard} is not yet wired into {@code SecurityConfig}, so row
 * visibility is enforced inside {@link ObClientEscalationService} via {@link
 * ObEscalationScope} instead — a caller with no onboarding standing sees an
 * empty list rather than a 403.
 */
@RestController
@RequestMapping("/api/v1/onboarding/client-escalations")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObClientEscalationController {

    private final ObClientEscalationService service;

    ObClientEscalationController(ObClientEscalationService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObClientEscalations", summary = "Escalations raised by clients from the portal (OB-02)")
    ObClientEscalationDtos.ObClientEscalationListResponse list(
            Authentication caller,
            @RequestParam(required = false) Long obClientId,
            @RequestParam(required = false) Long journeyId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.list(scopeOf(caller), obClientId, journeyId, state, cursor, limit);
    }

    @PostMapping(value = "/{escalationId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveObClientEscalation", summary = "Answer a client's escalation (OB-05)")
    ObClientEscalationDtos.ObClientEscalationResponse resolve(
            Authentication caller, @PathVariable long escalationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObEscalationDtos.ObEscalationResolveRequest request) {
        return new ObClientEscalationDtos.ObClientEscalationResponse(
                service.resolve(scopeOf(caller), escalationId, userId(caller), request.note()));
    }

    private static ObEscalationScope scopeOf(Authentication caller) {
        return CallerIdentity.of(caller).map(ObEscalationScope::of)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-client-escalations route reached with no resolvable caller identity"));
    }

    private static long userId(Authentication caller) {
        return CallerIdentity.of(caller).map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-client-escalations route reached with no resolvable caller identity"));
    }
}
