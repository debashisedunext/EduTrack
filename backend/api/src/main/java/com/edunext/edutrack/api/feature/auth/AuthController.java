package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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
    private final RefreshRotationService rotation;
    private final LogoutService logout;

    AuthController(AuthenticationService authentication,
                   AccessTokenIssuer tokens,
                   RefreshTokenIssuer refreshTokens,
                   RefreshRotationService rotation,
                   LogoutService logout) {
        this.authentication = authentication;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.rotation = rotation;
        this.logout = logout;
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
    @ApiResponse(
            responseCode = "401",
            description = "Wrong password, unknown user and deactivated account are "
                    + "byte-identical here, and cost the same time to answer.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "invalid-credentials",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/invalid-credentials",
                                      "title": "Invalid credentials",
                                      "status": 401,
                                      "detail": "The username or password is incorrect."
                                    }""")))
    @ApiResponse(
            responseCode = "423",
            description = "Account locked after 5 failures. Reported only once the password "
                    + "is correct — a 423 to a stranger confirms the account exists.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "account-locked",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/account-locked",
                                      "title": "Account locked",
                                      "status": 423,
                                      "detail": "Too many failed sign-in attempts. Try again later, or ask an administrator.",
                                      "lockedUntil": "2026-08-08T16:05:00Z"
                                    }""")))
    ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest request,
            // Optional on purpose. A missing User-Agent is unusual but not a
            // reason to refuse a login, and rejecting it here would turn a
            // header quirk into an outage for whatever client omits it. It
            // fingerprints as the empty string and simply binds weakly.
            //
            // Hidden from the document: the browser sets User-Agent itself and
            // forbids JavaScript from overriding it, so the input box Swagger
            // would render is one the request can never honour.
            @Parameter(hidden = true)
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

    /**
     * A-024 · {@code POST /api/v1/auth/refresh}. Blueprint §10.1.
     *
     * <p><b>No request body and no {@code Authorization} header.</b> The only
     * input is the cookie, which is why {@link SecurityRequirements} opts out of
     * the global bearer requirement here as it does for login: a caller whose
     * access token has expired — the only caller who ever needs this — has no
     * bearer token left to send.
     *
     * <p>The cookie name comes from the same property that
     * {@link RefreshTokenIssuer} writes with, so renaming it cannot leave the
     * endpoint reading a name nothing sets.
     *
     * <p>{@code required = false} rather than letting Spring return 400 on a
     * missing cookie. A caller with no cookie needs to log in, and 400 says
     * "your request was malformed" — the frontend's interceptor branches on 401
     * to redirect, and would treat a 400 as an application bug and surface it.
     */
    @PostMapping(path = "/refresh")
    @SecurityRequirements
    @Operation(
            operationId = "refreshSession",
            summary = "Rotate the refresh token and issue a new access token",
            description = """
                    Reads the refresh cookie; takes no body. The consumed token is revoked \
                    and replaced on every call, and the successor inherits the original \
                    login's deadline rather than restarting it — expiry does not slide.

                    **Reuse of an already-consumed token means the token was stolen.** The \
                    entire token family is revoked and the user must log in again. A silent \
                    re-issue here would let an attacker keep a parallel session alive \
                    indefinitely.

                    The identity and scope in the returned token are re-read from the \
                    database, not carried over — so a deactivation, a role change or a \
                    project reassignment takes effect at the next refresh rather than when \
                    the refresh token finally expires.""")
    @ApiResponse(
            responseCode = "200",
            description = "Rotated. The successor cookie replaces the consumed one.",
            headers = @Header(
                    name = HttpHeaders.SET_COOKIE,
                    description = "`refresh_token=…; Path=/api/v1/auth; HttpOnly; Secure; "
                            + "SameSite=Strict`. `Max-Age` is what remains of the original "
                            + "login's seven days, not a fresh seven.",
                    schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "401",
            description = """
                    Two distinct outcomes, told apart by `type`. Both clear the refresh \
                    cookie so the browser stops replaying a token that no longer works.

                    `invalid-refresh-token` — missing, expired, unknown, wrong device, \
                    session timed out, family already revoked, or the account was \
                    deactivated. Deliberately one body for all of them.

                    `refresh-token-reuse` — an already-consumed token was presented \
                    again, which means it was copied. **The entire token family is \
                    revoked** and every session descending from that login ends. This is \
                    the one auth failure that says what happened, so the victim learns \
                    they were compromised instead of seeing a generic expiry.""",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(
                                    name = "invalid-refresh-token",
                                    summary = "Missing, expired, unknown, wrong device or timed out",
                                    value = """
                                            {
                                              "type": "https://edutrack/errors/invalid-refresh-token",
                                              "title": "Session expired",
                                              "status": 401,
                                              "detail": "This session can no longer be renewed. Please sign in again."
                                            }"""),
                            @ExampleObject(
                                    name = "refresh-token-reuse",
                                    summary = "Consumed token replayed — the whole family is revoked",
                                    value = """
                                            {
                                              "type": "https://edutrack/errors/refresh-token-reuse",
                                              "title": "Session ended for your security",
                                              "status": 401,
                                              "detail": "This session was signed out because its sign-in token was \
                                            used more than once, which can mean it was copied. Please sign in again."
                                            }""")}))
    ResponseEntity<SessionResponse> refresh(
            // Hidden for the reason logout's are: `Cookie` is a forbidden header
            // name in browser JavaScript, so a Swagger input box for it renders
            // and is then dropped from the request. The browser sends the cookie
            // from its own jar; a curl caller sets `-H "Cookie: ..."` directly.
            @Parameter(hidden = true)
            @CookieValue(name = "${edutrack.auth.refresh-token.cookie-name:refresh_token}",
                    required = false) String refreshToken,
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {

        RefreshRotationService.Rotation rotated = rotation.rotate(refreshToken, userAgent);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, rotated.cookie().toString())
                .body(new SessionResponse(rotated.session()));
    }

    /**
     * A-025 · {@code POST /api/v1/auth/logout}. Blueprint §10.1.
     *
     * <p><b>The first route in this application that requires authentication</b>
     * — note the absence of {@link SecurityRequirements} here, unlike login and
     * refresh. The global bearer requirement from {@code OpenApiConfig} applies,
     * matching the contract, which marks every other {@code /auth} operation
     * {@code security: []} and this one not.
     *
     * <p>Because A-032's filter chain does not exist yet, the header is verified
     * inside {@link LogoutService} rather than by the framework. That is
     * temporary scaffolding in placement only — the verification itself is real,
     * through the shared {@code JwtDecoder}, because accepting an unverified
     * token here would be an endpoint for logging <i>other people</i> out.
     */
    @PostMapping(path = "/logout")
    @Operation(
            operationId = "logout",
            summary = "End the session",
            description = """
                    Deletes the refresh token and blacklists the access token's `jti` in \
                    Redis until its natural expiry — otherwise a logged-out access token \
                    stays valid for up to 15 minutes, which on a shared machine is 15 \
                    minutes of someone else's session.

                    Ends **this** session only. The token family is left intact, so signing \
                    out of a laptop does not sign the same user out of their phone; \
                    whole-family revocation is reserved for detected token theft (A-024).

                    The refresh cookie is optional — a session whose cookie has already \
                    expired or rotated must still be able to sign out, or the access token \
                    would be left live by the very request trying to revoke it.""")
    @ApiResponse(
            responseCode = "204",
            description = "Signed out. Idempotent — repeating the call is not an error.",
            headers = @Header(
                    name = HttpHeaders.SET_COOKIE,
                    description = "`refresh_token=; Path=/api/v1/auth; Max-Age=0` — clears the cookie "
                            + "so the browser stops presenting a token that no longer works.",
                    schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "401",
            description = """
                    Missing, malformed, expired or unverifiable access token. One body for \
                    all of them — naming the failed check would tell someone probing with \
                    forged tokens exactly how close they came.

                    **No `Set-Cookie` on this response.** A logout that could not \
                    authenticate has ended nothing, so the refresh cookie is left \
                    untouched rather than half-ending a session the caller was never \
                    proven to own.""",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "invalid-access-token",
                            summary = "No, malformed, forged or expired Bearer token",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/invalid-access-token",
                                      "title": "Not signed in",
                                      "status": 401,
                                      "detail": "A valid access token is required for this request."
                                    }""")))
    ResponseEntity<Void> logout(
            // Both hidden from the generated document, and neither is optional
            // in spirit — they are simply not things a caller fills in by hand.
            //
            // `Authorization` is supplied by the `bearerAuth` security scheme
            // (Swagger UI's Authorize button, or the generated client's token
            // interceptor). Declaring it as a parameter as well makes Swagger UI
            // render an input box that it then refuses to send, because the name
            // collides with the security scheme — the request goes out with no
            // header and the endpoint answers 401, which reads as a broken
            // endpoint rather than a documentation mistake.
            //
            // `Cookie` cannot be set from browser JavaScript at all: it is a
            // forbidden header name, so an input box for it is decorative. The
            // browser attaches the cookie from its own jar.
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(hidden = true)
            @CookieValue(name = "${edutrack.auth.refresh-token.cookie-name:refresh_token}",
                    required = false) String refreshToken) {

        logout.logout(authorization, refreshToken);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokens.clearing().toString())
                .build();
    }
}
