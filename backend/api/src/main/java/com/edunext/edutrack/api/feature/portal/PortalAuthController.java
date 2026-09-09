package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.security.ClientAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-121 · CP-01 — {@code /portal/auth/**}: login, the credential-link
 * redemption that stands in for a first login, session refresh, logout and
 * the forced password set.
 *
 * <h2>Not yet reachable — flagged for Stream A</h2>
 *
 * <p>{@code login}, {@code redeem} and {@code refresh} must be public routes
 * — the caller holds no bearer token by definition, exactly {@code
 * AuthController.login}'s own case. {@code SecurityConfig.PUBLIC_API_PATHS}
 * is where that is declared, and this task was told not to edit anything
 * under {@code api/security/}. So today these three routes 401 before they
 * are ever reached: {@code SecurityConfig}'s {@code authorizeHttpRequests}
 * rule requires authentication on {@code /api/**} ahead of {@code
 * PortalRouteFilter} ever running. <b>Stream A needs to add</b>:
 * <pre>
 *   "/api/v1/portal/auth/login",
 *   "/api/v1/portal/auth/refresh",
 *   "/api/v1/portal/auth/redeem",
 * </pre>
 * to that array. {@code logout} and {@code password} are correctly
 * authenticated already — a caller reaches them holding a CLIENT token.
 *
 * <h2>Prefix, and why {@code /portal/auth} rather than {@code
 * /portal/onboarding/auth}</h2>
 *
 * <p>Login precedes knowing which module the client will choose — {@link
 * com.edunext.edutrack.api.feature.portal.onboarding} would be the wrong
 * home for a route that exists before either module is picked. Mirrors
 * {@code AuthController}'s own {@code /auth/**} namespacing, one level under
 * the portal's own prefix rather than the staff one.
 */
@RestController
@RequestMapping(path = "/api/v1/portal/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "portal")
class PortalAuthController {

    private final PortalLoginService login;
    private final PortalLoginRateLimiter rateLimiter;
    private final PortalRefreshTokenIssuer refreshTokens;

    PortalAuthController(PortalLoginService login, PortalLoginRateLimiter rateLimiter,
                         PortalRefreshTokenIssuer refreshTokens) {
        this.login = login;
        this.rateLimiter = rateLimiter;
        this.refreshTokens = refreshTokens;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @PreAuthorize("permitAll()")
    @Operation(operationId = "portalLogin",
            summary = "Exchange a client's own username and password for a portal session (CP-01)")
    ResponseEntity<PortalAuthDtos.PortalSessionResponse> login(
            @Valid @RequestBody PortalAuthDtos.PortalLoginRequest request,
            HttpServletRequest httpRequest) {

        String clientKey = ClientAddress.of(httpRequest);
        rateLimiter.checkAndSpend(request.username(), clientKey)
                .ifPresent(retryAfter -> {
                    throw new PortalTooManyLoginAttemptsException(retryAfter);
                });

        PortalLoginService.Signed signed;
        try {
            signed = login.login(request.username(), request.password());
        } catch (PortalInvalidCredentialsException e) {
            rateLimiter.recordFailure(request.username(), clientKey);
            throw e;
        }
        rateLimiter.recordSuccess(request.username(), clientKey);

        return respond(signed);
    }

    /**
     * The newly-created / reset-password path's own entry point — see {@link
     * PortalLoginService}'s class note on why this authenticates rather than
     * merely validating.
     */
    @PostMapping(path = "/redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @PreAuthorize("permitAll()")
    @Operation(operationId = "portalRedeemCredential",
            summary = "Redeem a one-time credential link and start a session (CP-01)")
    ResponseEntity<PortalAuthDtos.PortalSessionResponse> redeem(
            @Valid @RequestBody PortalAuthDtos.PortalRedeemRequest request,
            HttpServletRequest httpRequest) {

        // The 256-bit token itself is the rate-limited identifier here — there
        // is no username to key on before the token is looked up.
        String clientKey = ClientAddress.of(httpRequest);
        rateLimiter.checkAndSpend(request.token(), clientKey)
                .ifPresent(retryAfter -> {
                    throw new PortalTooManyLoginAttemptsException(retryAfter);
                });

        PortalLoginService.Signed signed;
        try {
            signed = login.redeem(request.token());
        } catch (PortalInvalidCredentialTokenException e) {
            rateLimiter.recordFailure(request.token(), clientKey);
            throw e;
        }
        rateLimiter.recordSuccess(request.token(), clientKey);
        return respond(signed);
    }

    @PostMapping(path = "/refresh")
    @SecurityRequirements
    @PreAuthorize("permitAll()")
    @Operation(operationId = "portalRefreshSession",
            summary = "Rotate the portal refresh token and issue a new access token (CP-01)")
    ResponseEntity<PortalAuthDtos.PortalSessionResponse> refresh(
            @CookieValue(name = "${edutrack.auth.portal-refresh-token.cookie-name:portal_refresh_token}",
                    required = false) String refreshToken) {

        PortalLoginService.Signed signed = login.refresh(refreshToken);
        return respond(signed);
    }

    @PostMapping(path = "/logout")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "portalLogout", summary = "End the portal session (CP-01)")
    ResponseEntity<Void> logout(
            @CookieValue(name = "${edutrack.auth.portal-refresh-token.cookie-name:portal_refresh_token}",
                    required = false) String refreshToken) {

        login.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokens.clearing().toString())
                .build();
    }

    /**
     * The forced-change screen's submit — CP-01's other half. No {@code
     * currentPassword}; see {@link PortalAuthDtos.PortalSetPasswordRequest}.
     */
    @PatchMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "portalSetPassword",
            summary = "Set the client's own password, clearing the forced-change flag (CP-01)")
    ResponseEntity<Void> setPassword(
            @Valid @RequestBody PortalAuthDtos.PortalSetPasswordRequest request,
            Authentication caller) {

        login.setPassword(accountId(caller), request.newPassword());
        // The claim on the token that made this call is now stale for up to
        // its remaining lifetime — the same window PasswordChangeGate's own
        // note names for the staff path. The frontend is expected to call
        // /refresh immediately after a 204 here to pick up a token with no
        // mustChangePassword claim, rather than waiting out the access
        // token's natural expiry.
        return ResponseEntity.noContent().build();
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private ResponseEntity<PortalAuthDtos.PortalSessionResponse> respond(PortalLoginService.Signed signed) {
        PortalAuthDtos.PortalSessionResponse body = new PortalAuthDtos.PortalSessionResponse(
                PortalAuthDtos.PortalSession.issue(signed.account(), signed.accessToken()));

        return signed.refreshCookie()
                .map(cookie -> ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body))
                .orElseGet(() -> ResponseEntity.ok(body));
    }

    /**
     * The CLIENT principal's own subject — {@code client_accounts.id}, never
     * a {@code users} id. Not read through {@code CallerIdentity}, which
     * refuses a CLIENT-typed token by construction; read through {@link
     * ClientPrincipal} instead, exactly as every other portal route does.
     */
    private static long accountId(Authentication caller) {
        return ClientPrincipal.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated portal-auth route reached with no resolvable CLIENT principal"))
                .accountId();
    }
}
