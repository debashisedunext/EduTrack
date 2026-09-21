package com.edunext.edutrack.api.feature.portal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /portal/me/password} — the client's own password change, and the
 * one route {@code PortalPasswordChangeGate} leaves open to an account that has
 * not chosen its password yet.
 *
 * <h2>Why this is not on {@link PortalAuthController}</h2>
 *
 * <p>It would read as though it belonged there, and it must not live there.
 * {@code SecurityConfig.PUBLIC_API_PATHS} permits
 * {@code /api/v1/portal/auth/**} wholesale — correct for three routes whose
 * callers hold no token by definition — so a change-password route under that
 * prefix would be authenticated by a method annotation and by nothing in the
 * filter chain. One refactor of that annotation away from being an
 * unauthenticated password-change endpoint is not a place to put one.
 *
 * <p>Under {@code /portal/me/} the chain's {@code .authenticated()} rule
 * applies, {@code PortalRouteFilter} still polices the tree, and the route is
 * the exact mirror of staff's {@code PATCH /me/password} — which is what a
 * reader will expect it to be.
 *
 * <h2>Who is changing their password is not in the body</h2>
 *
 * <p>{@link ClientPrincipal#of} reads the account id from the verified token.
 * There is no path segment and no field a caller could supply, which is the
 * same property {@code PortalOnboardingController} has for client ids and for
 * the same reason: a parameter is something somebody can widen.
 */
@RestController
@RequestMapping(path = "/api/v1/portal/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "portal")
@PreAuthorize("isAuthenticated()")
class PortalPasswordController {

    private final PortalPasswordChangeService passwords;

    PortalPasswordController(PortalPasswordChangeService passwords) {
        this.passwords = passwords;
    }

    /**
     * <p>200 with a fresh session rather than 204, which is the one place this
     * departs from staff's route. {@link PortalPasswordChangeService} gives the
     * reason: the portal has no refresh route, so a 204 would leave the caller
     * holding the only token they have, still carrying the must-change claim
     * the change just cleared, and still being refused by the gate.
     *
     * <p><b>Reachable while the gate is refusing everything else</b> — it is
     * {@code PortalPasswordChangeGate.ALWAYS_ALLOWED}'s single entry. Renaming
     * this path without changing that set is a deadlock: every portal route
     * refused, including the way out.
     */
    @PatchMapping(path = "/password",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changePortalPassword",
            summary = "Change your own portal password")
    PortalAuthDtos.LoginResponseEnvelope change(Authentication authentication,
                                                @Valid @RequestBody
                                                PortalAuthDtos.PasswordChangeRequest request) {

        ClientPrincipal caller = ClientPrincipal.of(authentication)
                .orElseThrow(PortalAuthExceptions.InvalidPortalCredentials::new);

        return new PortalAuthDtos.LoginResponseEnvelope(
                passwords.change(caller.accountId(), request.currentPassword(), request.newPassword()));
    }
}
