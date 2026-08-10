package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-026 · {@code PATCH /api/v1/me/password}. Blueprint §10.1, screen S-03.
 *
 * <p><b>A separate controller from {@link AuthController}, because the path is
 * separate.</b> The contract puts this operation under {@code /me}, not
 * {@code /auth} — it is something you do to yourself, not something you do to a
 * session — and {@code ContractConformanceTest} compares path and verb, so
 * hanging it off the {@code /api/v1/auth} mapping would serve a route the
 * reviewed contract does not contain and fail the build. It keeps the {@code auth}
 * OpenAPI tag, matching the contract, so the generated client puts
 * {@code changeOwnPassword} alongside login and logout where callers look for it.
 *
 * <p>This is the first route under {@code /me}. {@code GET /me} (getMe) is
 * A-032/A-033's, and belongs with the filter chain that resolves the principal
 * rather than here.
 *
 * <p>Like {@link AuthController#logout}, this route is authenticated and — until
 * A-032's chain exists — authenticates its own caller, inside
 * {@link PasswordChangeService} via {@link AccessTokenVerifier}. Note the absence
 * of {@code @SecurityRequirements}: the global bearer requirement applies, which
 * is what the contract says.
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "auth", description = "Login, tokens, password lifecycle.")
class MeController {

    private final PasswordChangeService passwordChange;

    MeController(PasswordChangeService passwordChange) {
        this.passwordChange = passwordChange;
    }

    @PatchMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "changeOwnPassword",
            summary = "Change own password",
            description = """
                    Clears `mustChangePassword`, which is the only way that flag is ever \
                    cleared — an account handed a temporary password stays flagged until \
                    the person holding it sets a new one here.

                    The replacement must differ from the current password. Submitting the \
                    temporary password back would flip the flag while leaving live the \
                    credential an administrator generated and emailed, and the account \
                    would read as remediated in every report.

                    **The access token used to make this call is revoked on success.** It \
                    still carries the old `mustChangePassword` claim, and claims do not \
                    mutate — so the client refreshes once and continues with a token that \
                    reflects the change. Other devices are not signed out; whole-session \
                    revocation belongs to the reset-password flow.

                    Composition rules (upper, lower, digit, symbol) and the no-reuse-of-the- \
                    last-three rule described on the `Password` schema are not enforced \
                    yet — only its 8–128 length bounds are. That is A-028.""")
    @ApiResponse(responseCode = "204", description = "Changed. The access token used to make the call is now revoked.")
    @ApiResponse(
            responseCode = "400",
            description = "The body failed validation, or the new password is the current one.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "password-unchanged",
                            summary = "The replacement is the password being replaced",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/password-unchanged",
                                      "title": "Password unchanged",
                                      "status": 400,
                                      "detail": "The new password must be different from your current one."
                                    }""")))
    @ApiResponse(
            responseCode = "401",
            description = """
                    Two outcomes, told apart by `type`.

                    `invalid-access-token` — no, malformed, forged or expired bearer token, \
                    or the account has since been deactivated.

                    `invalid-credentials` — the bearer token was fine but `currentPassword` \
                    was wrong. Being specific is safe here: the caller has already proved \
                    who they are, so there is no account to enumerate and nothing to learn \
                    that they do not already know about themselves.""",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(
                                    name = "invalid-access-token",
                                    value = """
                                            {
                                              "type": "https://edutrack/errors/invalid-access-token",
                                              "title": "Not signed in",
                                              "status": 401,
                                              "detail": "A valid access token is required for this request."
                                            }"""),
                            @ExampleObject(
                                    name = "invalid-credentials",
                                    value = """
                                            {
                                              "type": "https://edutrack/errors/invalid-credentials",
                                              "title": "Invalid credentials",
                                              "status": 401,
                                              "detail": "The current password is incorrect."
                                            }""")}))
    ResponseEntity<Void> changeOwnPassword(
            // Hidden for the reason AuthController#logout documents: the header
            // is supplied by the `bearerAuth` security scheme, and declaring it
            // as a parameter as well makes Swagger UI render an input box it
            // then refuses to send, which reads as a broken endpoint.
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest request) {

        passwordChange.change(authorization, request);

        return ResponseEntity.noContent().build();
    }
}
