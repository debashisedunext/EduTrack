package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-112 · {@code /onboarding/clients/{obClientId}/communications} — the
 * <b>client-level stitched view</b> plan §6 asks for and no screen in §9
 * draws.
 *
 * <h2>Why it is a separate controller from the per-step one</h2>
 *
 * <p>Not tidiness — the paths have different roots. Spring maps a class to
 * one {@code @RequestMapping} prefix, and {@code
 * /onboarding/journey-steps/{stepId}/...} and {@code
 * /onboarding/clients/{obClientId}/...} share none of it. Both sit on the one
 * {@link ObCommunicationService}, which is where the shared behaviour
 * actually lives.
 *
 * <p><b>Read-only, and permanently.</b> Communications are written against
 * the service they happened on; there is no such thing as a communication
 * about a client in general. This controller stitches, it does not accept.
 *
 * <p>Auth and scoping are {@link ObStepCommunicationController}'s — see that
 * class's javadoc for the interim {@code isAuthenticated()} position and why
 * an out-of-scope client answers an empty list rather than a 403.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/communications")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObClientCommunicationController {

    private final ObCommunicationService service;

    ObClientCommunicationController(ObCommunicationService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObClientCommunications",
            summary = "Everything said to this client, across every service (plan §6)")
    ObCommunicationDtos.ObClientCommunicationListResponse list(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestParam(required = false) Long journeyId,
            @RequestParam(required = false) Boolean clientVisibleOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.listForClient(scopeOf(caller), obClientId, journeyId, clientVisibleOnly, cursor, limit);
    }

    private static ObCommunicationScope scopeOf(Authentication caller) {
        return CallerIdentity.of(caller).map(ObCommunicationScope::of)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-communications route reached with no resolvable caller identity"));
    }
}
