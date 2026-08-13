package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
// A-033 · declared once on the class rather than four times on the methods,
// because it is a property of the whole resource and not a choice made per
// operation: everything under /me acts on the caller's own account, so the
// subject is the principal and there is no capability to hold. None of the four
// is a §2 matrix row — a role that could not change its own password or enrol an
// authenticator would be a role nobody could administer.
//
// Row scope is not deferred here the way it is elsewhere: the id these operate on
// is the token's `sub`, never a path variable, so there is no other row to reach.
// A method-level @PreAuthorize would override this one if some future operation
// under /me does need a permission.
@PreAuthorize("isAuthenticated()")
class MeController {

    private final PasswordChangeService passwordChange;
    private final TwoFactorEnrolmentService twoFactorEnrolment;

    MeController(PasswordChangeService passwordChange,
                 TwoFactorEnrolmentService twoFactorEnrolment) {
        this.passwordChange = passwordChange;
        this.twoFactorEnrolment = twoFactorEnrolment;
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

    // ── A-029 · two-factor enrolment ────────────────────────────────────────
    //
    // CONTRACT DEVIATION, flagged for Debashis rather than made quietly (Stream
    // D owns contracts/openapi.yaml). The contract describes 2FA only as
    // LoginRequest.totpCode — there are no endpoints for enrolling, confirming
    // or disabling, and none for recovery codes. The three below are therefore
    // additions rather than implementations of a reviewed shape, and
    // ContractConformanceTest will report them as served-but-undocumented until
    // the contract catches up. Paths and bodies are documented springdoc-side so
    // the generated client can be regenerated the moment it does.

    @PostMapping(path = "/2fa/setup")
    @Operation(
            operationId = "beginTwoFactorEnrolment",
            summary = "Start two-factor enrolment",
            description = """
                    Generates a shared secret and returns it, along with the `otpauth://` \
                    URI to render as a QR code. **This does not turn 2FA on** — the account \
                    is unchanged until `/2fa/confirm` succeeds.

                    That two-step shape is the whole design. Enabling at the moment the \
                    secret is created locks out anyone whose QR did not scan, whose phone \
                    clock is wrong, or who simply closed the tab — and locks them out of \
                    the account they were in the middle of protecting.

                    Calling this again replaces an unconfirmed secret, which is what makes \
                    "let me scan that again" work. It is refused outright once 2FA is \
                    enabled: re-enrolling requires disabling first, and disabling requires \
                    the password, so a stolen access token cannot swap the second factor \
                    for one of its own.""")
    @ApiResponse(responseCode = "200", description = "Secret and otpauth:// URI. 2FA is not yet enabled.")
    @ApiResponse(responseCode = "401", description = "Missing, forged or expired access token.",
            content = @Content)
    @ApiResponse(
            responseCode = "409",
            description = "Two-factor is already enabled — disable it first.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "two-factor-already-enabled",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/two-factor-already-enabled",
                                      "title": "Two-factor is already enabled",
                                      "status": 409,
                                      "detail": "Disable two-factor authentication before enrolling a new authenticator."
                                    }""")))
    ResponseEntity<TwoFactorRequests.SetupEnvelope> beginTwoFactorEnrolment(
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        TotpService.Enrolment enrolment = twoFactorEnrolment.begin(authorization);

        // CONVENTIONS.md §2 — every 2xx JSON body carries { data }.
        return ResponseEntity.ok(new TwoFactorRequests.SetupEnvelope(
                new TwoFactorRequests.SetupResponse(enrolment.secret(), enrolment.otpauthUri())));
    }

    @PostMapping(path = "/2fa/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "confirmTwoFactorEnrolment",
            summary = "Confirm two-factor enrolment and switch it on",
            description = """
                    Verifies a code from the authenticator, which proves the secret was \
                    added successfully, and only then enables 2FA.

                    **Returns the recovery codes, once.** They are stored hashed and cannot \
                    be shown again — the user has to keep them now or regenerate the set \
                    later. They are issued here rather than at setup because codes handed \
                    out for an enrolment that was never completed would be live credentials \
                    for an account with no second factor.

                    Each recovery code works exactly once and substitutes for the 6-digit \
                    code at login, which is what stops a lost phone becoming a permanently \
                    locked account.""")
    @ApiResponse(responseCode = "200", description = "Two-factor is now enabled. Recovery codes returned once.")
    @ApiResponse(responseCode = "400", description = "The code is not six digits.", content = @Content)
    @ApiResponse(
            responseCode = "401",
            description = "Bad access token, or the code did not verify.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(
                            name = "invalid-totp-code",
                            value = """
                                    {
                                      "type": "https://edutrack/errors/invalid-totp-code",
                                      "title": "Two-factor code is not valid",
                                      "status": 401,
                                      "detail": "That code was not accepted. Codes change every 30 seconds — check your authenticator and try the current one."
                                    }""")))
    @ApiResponse(responseCode = "409", description = "No enrolment in progress, or 2FA is already on.",
            content = @Content)
    ResponseEntity<TwoFactorRequests.RecoveryCodesEnvelope> confirmTwoFactorEnrolment(
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody TwoFactorRequests.ConfirmRequest request) {

        List<String> codes = twoFactorEnrolment.confirm(authorization, request.code());

        // CONVENTIONS.md §2 — every 2xx JSON body carries { data }.
        return ResponseEntity.ok(new TwoFactorRequests.RecoveryCodesEnvelope(
                new TwoFactorRequests.RecoveryCodesResponse(codes)));
    }

    @PostMapping(path = "/2fa/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "disableTwoFactor",
            summary = "Turn two-factor authentication off",
            description = """
                    **Requires the account password**, not just a valid access token, and \
                    that is the security of this endpoint rather than a formality. Removing \
                    the second factor is the first thing somebody holding a stolen \
                    fifteen-minute token would do, and a token must not be enough to strip \
                    the protection it was layered under.

                    Clears the secret and every recovery code, so re-enabling means scanning \
                    a new QR. Leaving them behind would mean a user who disabled 2FA because \
                    their authenticator was compromised silently resurrects that same secret \
                    on re-enrolling.""")
    @ApiResponse(responseCode = "204", description = "Two-factor is off. Secret and recovery codes cleared.")
    @ApiResponse(
            responseCode = "401",
            description = """
                    `invalid-access-token` — bad or missing bearer token. \
                    `invalid-credentials` — the token was fine but the password was wrong.""",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Void> disableTwoFactor(
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody TwoFactorRequests.DisableRequest request) {

        twoFactorEnrolment.disable(authorization, request.password());

        return ResponseEntity.noContent().build();
    }
}
