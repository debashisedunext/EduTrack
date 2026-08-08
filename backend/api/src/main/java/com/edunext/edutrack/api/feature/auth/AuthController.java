package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * identity it has already vouched for is {@link AccessTokenIssuer}'s, and the
 * long-lived half of the session is {@link RefreshTokenIssuer}'s. This class
 * only sequences them — verify, then mint, then wrap.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth", description = "Login, tokens, password lifecycle.")
class AuthController {

    private final AuthenticationService authentication;
    private final AccessTokenIssuer tokens;
    private final RefreshTokenIssuer refreshTokens;

    AuthController(AuthenticationService authentication,
                   AccessTokenIssuer tokens,
                   RefreshTokenIssuer refreshTokens) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
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

                    Returns a 15-minute JWT access token (A-022) in the body, and a \
                    7-day opaque refresh token as an `HttpOnly; Secure; SameSite=Strict` \
                    cookie (A-023). The refresh token is never in the body — script must \
                    not be able to read a credential that lives seven days.""")
    @ApiResponse(
            responseCode = "200",
            description = "Credentials accepted.",
            headers = @Header(
                    name = HttpHeaders.SET_COOKIE,
                    description = "`refresh_token=…; Path=/api/v1/auth; HttpOnly; Secure; "
                            + "SameSite=Strict; Max-Age=604800`. Absent if the token store was "
                            + "unreachable, in which case the session simply cannot be renewed.",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Malformed request body.", content = @Content)
    @ApiResponse(responseCode = "401", description = "Invalid credentials.", content = @Content)
    @ApiResponse(responseCode = "423", description = "Account locked after repeated failures.", content = @Content)
    ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest request,
            // Optional on purpose. A missing User-Agent is unusual but not a
            // reason to refuse a login, and rejecting it here would turn a
            // header quirk into an outage for whatever client omits it. It
            // fingerprints as the empty string and simply binds weakly.
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {

        AuthenticatedUser user = authentication.authenticate(request.username(), request.password());
        AccessToken token = tokens.issue(user);
        SessionResponse body = new SessionResponse(Session.issue(user, token));

        // Empty only when the token store is unreachable. The login still
        // succeeds — see RefreshTokenIssuer#issue for why that degrades rather
        // than fails.
        return refreshTokens.issue(user, userAgent)
                .map(cookie -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(body))
                .orElseGet(() -> ResponseEntity.ok(body));
    }
}
