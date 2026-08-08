package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-020 · {@code POST /api/v1/auth/login}. Blueprint §10.1, screen S-01.
 *
 * <p>{@link SecurityRequirements} with no entries overrides the global bearer
 * requirement {@code OpenApiConfig} applies to every operation. Without it the
 * generated client would attach an {@code Authorization} header to the one call
 * whose entire purpose is to obtain one. The global default is the right way
 * round — a forgotten annotation documents an endpoint as protected rather than
 * public — but login is one of the handful that genuinely opts out.
 *
 * <p>The controller does no security thinking of its own: it validates the
 * request shape and delegates. Credential verification and scope resolution
 * are {@link AuthenticationService}'s concern; minting the token for an
 * identity it has already vouched for is {@link AccessTokenIssuer}'s. This
 * class only sequences the two — verify, then mint, then wrap the pair.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth", description = "Login, tokens, password lifecycle.")
class AuthController {

    private final AuthenticationService authentication;
    private final AccessTokenIssuer tokens;

    AuthController(AuthenticationService authentication, AccessTokenIssuer tokens) {
        this.authentication = authentication;
        this.tokens = tokens;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(
            operationId = "login",
            summary = "Exchange credentials for a session",
            description = """
                    Failures are deliberately indistinguishable. Wrong username, wrong \
                    password, unknown user and a deactivated account all return the same \
                    `invalid-credentials` problem, and all cost the same amount of time \
                    to answer. Saying — or timing — which one it was turns the login form \
                    into a username oracle.

                    Returns a 15-minute JWT access token (A-022). The refresh cookie \
                    (A-023) is not issued here yet.""")
    @ApiResponse(responseCode = "200", description = "Credentials accepted.")
    @ApiResponse(responseCode = "400", description = "Malformed request body.", content = @Content)
    @ApiResponse(responseCode = "401", description = "Invalid credentials.", content = @Content)
    @ApiResponse(responseCode = "423", description = "Account locked after repeated failures.", content = @Content)
    SessionResponse login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedUser user = authentication.authenticate(request.username(), request.password());
        AccessToken token = tokens.issue(user);
        return new SessionResponse(Session.issue(user, token));
    }
}
