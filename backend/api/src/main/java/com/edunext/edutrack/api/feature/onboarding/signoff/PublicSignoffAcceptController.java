package com.edunext.edutrack.api.feature.onboarding.signoff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-115 · the acceptance route on the public sign-off surface.
 *
 * <p>The third route on the tree A-120 opened and A-121's controller named the
 * rest of: "accept, object and csat are Stream B's and follow". This is accept;
 * object is B-117 and csat is B-119.
 *
 * <h2>{@code permitAll()}, for the reason stated one class over</h2>
 *
 * <p>There is no principal — the caller is a customer holding a session minted
 * by {@code verifyObSignoffOtp}, not a user. The contract declares the
 * operation {@code security: []}, and {@code RouteAuthorizationTest} still
 * requires the decision to be written down rather than inherited from the
 * chain, so it is annotated and named in that test's
 * {@code EXPECTED_PUBLIC_ROUTES}.
 *
 * <h2>No rate limit here, and that is not an omission</h2>
 *
 * <p>{@link PublicSignoffAccess} rate-limits the two operations that take the
 * <em>link</em> token, because those are the ones a caller can grind: the token
 * is the thing being guessed. This route takes a session token, which is 256
 * bits of {@code SecureRandom} minted moments earlier and dead in fifteen
 * minutes — there is nothing here to enumerate that {@code verifyObSignoffOtp}
 * has not already made expensive. The contract still declares {@code 429},
 * which the surface's global limiter answers.
 *
 * <h2>The method decides nothing</h2>
 *
 * <p>{@link ObSignoffAcceptService} holds the ordering that matters — the
 * acceptance is written before the gate is attempted — and
 * {@link PublicSignoffExceptionHandler} renders the one 401 every failure on
 * this surface answers with.
 */
@RestController
@RequestMapping("/api/v1/public/onboarding/signoff")
@Tag(name = "onboarding")
@PreAuthorize("permitAll()")
class PublicSignoffAcceptController {

    private final ObSignoffAcceptService service;

    PublicSignoffAcceptController(ObSignoffAcceptService service) {
        this.service = service;
    }

    /**
     * <p><b>200 with a body that can say the step did not complete.</b> The
     * contract's two outcomes are both successes: the acceptance is recorded
     * either way, and {@code stepCompleted} plus {@code gateFailures} carry
     * whether our own side finished. A gate failure is deliberately not a 4xx —
     * it is not the client's request that was wrong.
     */
    @PostMapping(value = "/accept",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptObSignoff",
            summary = "The client accepts (OB-09)")
    ResponseEntity<PublicSignoffAcceptDtos.AcceptResultResponse> accept(
            @Valid @RequestBody PublicSignoffAcceptDtos.AcceptRequest request,
            HttpServletRequest http) {

        PublicSignoffAcceptDtos.AcceptResult result =
                service.accept(request.sessionToken(), request.acceptedName(), request.note(), http);
        return ResponseEntity.ok(new PublicSignoffAcceptDtos.AcceptResultResponse(result));
    }
}
