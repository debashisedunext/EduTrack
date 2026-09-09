package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-126 · the client-account panel on OB-05 and OB-08.
 *
 * <h2>Four routes, one resource</h2>
 *
 * <p>The account is addressed as a sub-resource of the client rather than by its
 * own id, and that is not cosmetic: {@code uq_client_accounts_ob_client} makes
 * it one-per-client, so the client id <em>is</em> the address. An
 * {@code /client-accounts/{id}} tree would invite a second account per client to
 * become expressible in the URL before it became expressible in the schema.
 *
 * <h2>Where the authorisation lives, and what needs confirming</h2>
 *
 * <p>{@code @PreAuthorize("isAuthenticated()")} and then
 * {@link ObClientScope} in the service, which is the pattern every onboarding
 * route follows: the module role decides what a caller may do and the scope
 * predicate decides which clients they may do it to, and neither is something
 * {@code @PreAuthorize} can say.
 *
 * <p>The split applied is <b>{@code mayWrite()} for create, {@code isModerator()}
 * for reset and disable</b>. The reasoning: creating a login is part of boarding
 * a client, which Sales does — B-109's OB-04 wizard has the "create client
 * login" checkbox on exactly that screen — whereas reissuing a credential or
 * revoking access is override-grade, and {@code isModerator()} is the
 * vocabulary §3 already uses for that. <b>Blueprint §2's permission matrix is
 * the authority here and does not yet name these three operations</b>; this is
 * the reading that fits the neighbouring rules, and it is flagged on the PR
 * rather than presented as settled.
 *
 * <h2>No {@code If-Match}</h2>
 *
 * <p>Create and reset are actions rather than edits — there is no prior state a
 * concurrent caller could be overwriting — and {@code PATCH} here sets a flag to
 * a stated value, which is the same case {@code setUserStatus} makes for its own
 * exemption: two callers who both say {@code isActive: false} agree.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients/{obClientId}/account")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ClientAccountAdminController {

    private final ClientAccountAdminService accounts;

    ClientAccountAdminController(ClientAccountAdminService accounts) {
        this.accounts = accounts;
    }

    /**
     * <p>404 when the client has no login <em>and</em> when the client is
     * outside this caller's scope — see
     * {@link ClientAccountNotFoundException} for why the two are one answer.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObClientAccount",
            summary = "The client's portal login, if it has one (OB-05)")
    ResponseEntity<ClientAccountAdminDtos.AccountResponse> get(
            Authentication caller,
            @PathVariable long obClientId) {

        return accounts.find(scopeOf(caller), obClientId)
                .map(account -> ResponseEntity.ok(new ClientAccountAdminDtos.AccountResponse(account)))
                .orElseThrow(() -> new ClientAccountNotFoundException(obClientId));
    }

    /**
     * <p>201, and the body is the account rather than the credential. The link
     * goes to the client's mailbox and appears on no response — see
     * {@link ClientAccountAdminDtos}.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObClientAccount",
            summary = "Create the portal login and mail the primary SPOC a link (OB-05)")
    ResponseEntity<ClientAccountAdminDtos.AccountResponse> create(
            Authentication caller,
            @PathVariable long obClientId) {

        ObClientScope scope = requireWrite(scopeOf(caller), obClientId);
        ClientAccountAdminDtos.Account account =
                accounts.create(scope, obClientId, userId(caller));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientAccountAdminDtos.AccountResponse(account));
    }

    @PostMapping(path = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resetObClientAccountPassword",
            summary = "Issue a fresh credential link and retire the last one (OB-05)")
    ResponseEntity<ClientAccountAdminDtos.AccountResponse> reset(
            Authentication caller,
            @PathVariable long obClientId) {

        ObClientScope scope = requireModerator(scopeOf(caller), obClientId);
        ClientAccountAdminDtos.Account account =
                accounts.resetPassword(scope, obClientId, userId(caller));
        return ResponseEntity.ok(new ClientAccountAdminDtos.AccountResponse(account));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setObClientAccountStatus",
            summary = "Enable or disable the portal login (OB-05, OB-08)")
    ResponseEntity<ClientAccountAdminDtos.AccountResponse> setStatus(
            Authentication caller,
            @PathVariable long obClientId,
            @Valid @RequestBody ClientAccountAdminDtos.StatusRequest request) {

        ObClientScope scope = requireModerator(scopeOf(caller), obClientId);
        ClientAccountAdminDtos.Account account =
                accounts.setActive(scope, obClientId, request.isActive());
        return ResponseEntity.ok(new ClientAccountAdminDtos.AccountResponse(account));
    }

    /**
     * <p>Refused as 404 rather than 403, on the same rule the scope predicate
     * applies: a caller who may not write to this client learns nothing about
     * whether it exists. {@code ObClientScope} has already decided the role
     * question; this only turns the answer into the module's standard refusal.
     */
    private static ObClientScope requireWrite(ObClientScope scope, long obClientId) {
        if (!scope.mayWrite()) {
            throw new ClientAccountNotFoundException(obClientId);
        }
        return scope;
    }

    private static ObClientScope requireModerator(ObClientScope scope, long obClientId) {
        if (!scope.isModerator()) {
            throw new ClientAccountNotFoundException(obClientId);
        }
        return scope;
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identity(caller));
    }

    private static long userId(Authentication caller) {
        return identity(caller).userId();
    }

    /** {@code ObContactController.identity}'s reasoning, unchanged: a loud 500 beats a silent null. */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated client-account route reached with no resolvable "
                                + "caller identity"));
    }
}
