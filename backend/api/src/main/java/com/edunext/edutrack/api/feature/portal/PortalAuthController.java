package com.edunext.edutrack.api.feature.portal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-130 · {@code /portal/auth} — how a client gets a session.
 *
 * <h2>Three routes, all unauthenticated, and that is the point</h2>
 *
 * <p>Nobody holding a portal token needs any of these; everybody who needs them
 * holds no token. So they are in {@code SecurityConfig.PUBLIC_API_PATHS},
 * alongside {@code /auth/login} and A-120's public sign-off surface, and they
 * carry {@code @SecurityRequirements()} so the contract says so too.
 *
 * <p>They sit under {@code /api/v1/portal/} anyway, which
 * {@code PortalRouteGuard} polices. That is safe and deliberate:
 * {@code blocks} answers false for a caller who is neither staff nor client,
 * because "which kind of signed-in caller is this?" is not a question that
 * arises until there is one. A staff token on these routes still 404s, which is
 * correct — a staff member has no business redeeming a client's link.
 *
 * <h2>What stands in for authentication</h2>
 *
 * <p>On the two credential routes, the token in the path: a 256-bit random
 * value whose SHA-256 is the only copy we hold, single-use, seven-day TTL.
 * A-120's public surface makes the same trade in the same words — opening the
 * path does not open the data.
 *
 * <p>On login, the password. There is no rate limiter on it yet, and that is
 * this task's most significant omission rather than an oversight: A-076's
 * {@code LoginRateLimiter} is keyed on a staff identifier and budgets against
 * {@code /auth/login}, so pointing it here needs a second budget rather than a
 * second caller. Until it lands, {@code client_accounts.failed_attempts} and
 * the fifteen-minute lock are what bound guessing against a <i>known</i>
 * username — they do nothing about one password sprayed across many. Named on
 * the task, and named here.
 */
@RestController
@RequestMapping("/api/v1/portal/auth")
@Tag(name = "portal")
@SecurityRequirements()
/*
  Said explicitly rather than inherited. Without it these three would take
  SecurityConfig's "any authenticated caller" default and read, in the source,
  exactly like routes somebody had reasoned about — which is what
  RouteAuthorizationTest refuses. The permit is real: the caller has no account
  yet, or is signing in to get one.
*/
@PreAuthorize("permitAll()")
class PortalAuthController {

    private final PortalAuthService authentication;
    private final PortalCredentialService credentials;
    private final ClientAccessTokenIssuer tokens;

    PortalAuthController(PortalAuthService authentication,
                         PortalCredentialService credentials,
                         ClientAccessTokenIssuer tokens) {
        this.authentication = authentication;
        this.credentials = credentials;
        this.tokens = tokens;
    }

    /**
     * <p>200 with a bearer token the caller stores and sends. <b>No refresh
     * cookie</b> — see {@link PortalAuthService}'s note on why rotation is not
     * in this task, and what that costs.
     */
    @PostMapping(path = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "portalLogin", summary = "Sign in to the client portal")
    PortalAuthDtos.LoginResponseEnvelope login(@Valid @RequestBody PortalAuthDtos.LoginRequest request) {
        ClientAccountRow account = authentication.authenticate(request.username(), request.password());
        ClientAccessTokenIssuer.Minted minted = tokens.issue(account);

        return new PortalAuthDtos.LoginResponseEnvelope(new PortalAuthDtos.LoginResponse(
                minted.value(), minted.expiresInSeconds(), PortalAuthDtos.Client.of(account)));
    }

    /**
     * Is this link still good, and who is it for?
     *
     * <p>A {@code GET} that changes nothing, so following the link twice before
     * choosing a password is harmless — and so a mail client that prefetches
     * URLs cannot spend somebody's credential link by looking at it. That last
     * point is the reason redemption below is a {@code POST} and not this.
     */
    @GetMapping(path = "/credential/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "describePortalCredentialLink",
            summary = "Whether a credential link is still valid, and the username it is for")
    PortalAuthDtos.CredentialLinkEnvelope describe(@PathVariable String token) {
        return new PortalAuthDtos.CredentialLinkEnvelope(credentials.describe(token));
    }

    /**
     * Spends the link and sets the password.
     *
     * <p>204 rather than a session: redeeming is not signing in. Handing back a
     * token here would mean a link in an inbox is directly exchangeable for a
     * session, so anyone who reads that mailbox later — a shared
     * {@code info@} address, a forwarded thread — gets in without ever knowing
     * the password. The client redeems, then signs in with what they chose.
     */
    @PostMapping(path = "/credential/{token}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "redeemPortalCredentialLink",
            summary = "Choose a password and activate the portal login")
    ResponseEntity<Void> redeem(@PathVariable String token,
                                @Valid @RequestBody PortalAuthDtos.RedeemRequest request) {
        credentials.redeem(token, request.password());
        return ResponseEntity.noContent().build();
    }
}
