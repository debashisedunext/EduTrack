package com.edunext.edutrack.api.feature.onboarding.signoff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-121 · the two OTP operations on the public sign-off surface.
 *
 * <h2>The first controller under the module's only unauthenticated prefix</h2>
 *
 * <p>A-120 built the surface — {@link PublicSignoffAccess}, the limiter, the
 * token resolver, the identical refusal — and added
 * {@code /api/v1/public/onboarding/**} to {@code PUBLIC_API_PATHS}. Nothing
 * served it. These are the first two routes to sit on it; accept, object and
 * csat are Stream B's and follow.
 *
 * <h2>{@code permitAll()}, said out loud</h2>
 *
 * <p>There is no principal to authorise: the caller is a customer holding a
 * link and no account, and the contract declares both operations
 * {@code security: []}. {@code RouteAuthorizationTest} still requires the
 * decision to be written down — a route with no annotation inherits "any
 * authenticated caller" and reads, in the source, exactly like one somebody
 * reasoned about — so the answer is stated rather than left to the chain, and
 * both routes are named in that test's {@code EXPECTED_PUBLIC_ROUTES}.
 *
 * <p>What stands in for authentication is the token in the body, checked by
 * {@link PublicSignoffAccess}. {@code onlyTheContractsPublicOperationsArePublic}
 * is what keeps the two halves honest: it fails if a route is public here and
 * not public in the contract.
 *
 * <h2>Both take the token in the body</h2>
 *
 * <p>The contract's first standing property, and the reason there is no
 * {@code GET} anywhere on this tree: a URL carrying the token lands in browser
 * history, in the {@code Referer} of every asset the page loads, and in the
 * access log of everything in between. OB-09 is a POST-then-render page for
 * exactly this reason.
 *
 * <h2>Neither method decides anything</h2>
 *
 * <p>The rate limit, the resolution and the shape of a refusal all live in
 * {@link PublicSignoffAccess} and {@link ObSignoffOtpService}; the refusals are
 * rendered by {@link PublicSignoffExceptionHandler}. Four controllers each
 * remembering to rate-limit is four chances to forget, which is the whole
 * argument A-120's class javadoc makes.
 */
@RestController
@RequestMapping("/api/v1/public/onboarding/signoff")
@Tag(name = "onboarding")
@PreAuthorize("permitAll()")
class PublicSignoffOtpController {

    private final ObSignoffOtpService service;

    PublicSignoffOtpController(ObSignoffOtpService service) {
        this.service = service;
    }

    /**
     * <p><b>202 whatever happens</b>, with no body — for an unknown, expired,
     * cancelled or already-signed token exactly as for a good one. The contract
     * calls this "deliberately indistinguishable from the successful case", and
     * it is the enumeration oracle the surface exists to close. There is
     * nothing to branch on here because {@link ObSignoffOtpService#issue}
     * returns nothing to branch on.
     */
    @PostMapping(value = "/otp", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "requestObSignoffOtp",
            summary = "Send the one-time code for a sign-off link (OB-09)")
    ResponseEntity<Void> requestOtp(@Valid @RequestBody PublicSignoffOtpDtos.TokenRequest request,
                                    HttpServletRequest http) {
        service.issue(request.token(), http);
        return ResponseEntity.accepted().build();
    }

    /**
     * <p>The point at which the caller becomes identified and OB-09 can render.
     * Every failure is one {@code 401} with one body — see
     * {@link InvalidSignoffTokenException}.
     */
    @PostMapping(value = "/otp/verify",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "verifyObSignoffOtp",
            summary = "Exchange the code for a short-lived session, and the page (OB-09)")
    ResponseEntity<PublicSignoffOtpDtos.SessionResponse> verifyOtp(
            @Valid @RequestBody PublicSignoffOtpDtos.OtpVerifyRequest request,
            HttpServletRequest http) {

        PublicSignoffOtpDtos.Session session =
                service.verify(request.token(), request.otp(), http);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new PublicSignoffOtpDtos.SessionResponse(session));
    }
}
