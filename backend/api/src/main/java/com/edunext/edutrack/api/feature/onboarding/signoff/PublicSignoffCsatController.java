package com.edunext.edutrack.api.feature.onboarding.signoff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-119 · the CSAT route on the public sign-off surface.
 *
 * <p>{@link PublicSignoffAcceptController}'s own javadoc named this route
 * before it existed: "accept, object and csat are Stream B's and follow".
 * This is csat, the last of the three.
 *
 * <h2>{@code permitAll()}, and no rate limit — {@link
 * PublicSignoffAcceptController}'s reasoning, unchanged</h2>
 *
 * <p>The caller holds a session {@code verifyObSignoffOtp} minted, not a
 * principal — the contract declares {@code security: []} and {@code
 * RouteAuthorizationTest} still requires the decision written down here
 * rather than inherited, hence {@code EXPECTED_PUBLIC_ROUTES}. And the
 * session token is 256 bits of {@code SecureRandom} — there is nothing here
 * to grind that {@code verifyObSignoffOtp}'s own rate limit has not already
 * made expensive.
 *
 * <h2>The method decides nothing</h2>
 *
 * <p>{@link ObSignoffCsatService} holds the ordering and the eligibility
 * rules, and {@link PublicSignoffExceptionHandler} (the shared 401/429) plus
 * {@link PublicSignoffCsatExceptionHandler} (this route's own two 422s)
 * render every refusal.
 */
@RestController
@RequestMapping("/api/v1/public/onboarding/signoff")
@Tag(name = "onboarding")
@PreAuthorize("permitAll()")
class PublicSignoffCsatController {

    private final ObSignoffCsatService service;

    PublicSignoffCsatController(ObSignoffCsatService service) {
        this.service = service;
    }

    /**
     * <p><b>{@code 200} with no body.</b> The contract's own response for
     * this operation: "Recorded. Thank-you state." — nothing further for
     * OB-09 to render beyond the fact that it worked.
     */
    @PostMapping(value = "/csat",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitObCsat",
            summary = "The one-question go-live survey (OB-09)")
    ResponseEntity<Void> submit(@Valid @RequestBody PublicSignoffCsatDtos.CsatRequest request) {
        service.submit(request.sessionToken(), request.score(), request.comment());
        return ResponseEntity.ok().build();
    }
}
