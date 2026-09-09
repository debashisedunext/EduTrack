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
 * B-117 · the objection route on the public sign-off surface.
 *
 * <p>{@link PublicSignoffAcceptController}'s own javadoc named this route
 * before it existed: "accept, object and csat are Stream B's and follow".
 * This is object; csat is B-119.
 *
 * <h2>{@code permitAll()}, and no rate limit — {@link
 * PublicSignoffAcceptController}'s reasoning, unchanged</h2>
 *
 * <p>The caller holds a session {@code verifyObSignoffOtp} minted, not a
 * principal — the contract declares {@code security: []} and {@code
 * RouteAuthorizationTest} still requires the decision written down here
 * rather than inherited, hence {@code EXPECTED_PUBLIC_ROUTES}. And the
 * session token is 256 bits of {@code SecureRandom}, dead in fifteen minutes
 * — there is nothing to grind that {@code verifyObSignoffOtp}'s own rate
 * limit has not already made expensive.
 *
 * <h2>The method decides nothing</h2>
 *
 * <p>{@link ObSignoffObjectService} holds the ordering — the objection is
 * written before the step is asked to revert — and {@link
 * PublicSignoffExceptionHandler} renders the one 401 every failure on this
 * surface answers with.
 */
@RestController
@RequestMapping("/api/v1/public/onboarding/signoff")
@Tag(name = "onboarding")
@PreAuthorize("permitAll()")
class PublicSignoffObjectController {

    private final ObSignoffObjectService service;

    PublicSignoffObjectController(ObSignoffObjectService service) {
        this.service = service;
    }

    @PostMapping(value = "/object",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "objectObSignoff",
            summary = "The client objects, and the service reverts (OB-09)")
    ResponseEntity<PublicSignoffObjectDtos.SignoffResponse> object(
            @Valid @RequestBody PublicSignoffObjectDtos.ObjectRequest request) {

        PublicSignoffObjectDtos.SignoffDetail result = service.object(request.sessionToken(), request.note());
        return ResponseEntity.ok(new PublicSignoffObjectDtos.SignoffResponse(result));
    }
}
