package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A-117 · {@code /onboarding/module-access} — OB-08, who can reach a module and
 * as what.
 *
 * <h2>Three routes, and the one that is not a DELETE</h2>
 *
 * <p>Withdrawing access is {@code POST .../{grantId}/revoke}, not
 * {@code DELETE .../{grantId}}, and the contract is emphatic about it. A-109
 * revokes by stamping {@code revoked_at} and {@code revoked_by} so an access
 * audit can say who withdrew access and when — the question asked after an
 * incident, and the one a delete leaves nothing to answer with. A
 * {@code DELETE} verb over a row that survives would describe the wrong act to
 * every reader and every generated client.
 *
 * <h2>Auth: {@code isAuthenticated()} here, OB Admin in the service</h2>
 *
 * <p>The interim position every onboarding controller in this codebase
 * declares — see {@code ObClientController} — with one difference worth
 * stating: this resource is <b>Admin-only on all three operations, reads
 * included</b>, and the refusal is a <b>403 rather than a 404</b>.
 *
 * <p>That is not a departure from CLAUDE.md's no-existence-leak rule. The rule
 * protects rows: an out-of-scope client or ticket must not be shown to exist.
 * Here there is no row whose existence a 404 could protect — the caller is
 * refused organisation-wide administration, not a record — and they have
 * already passed {@code ModuleAccessGuard} to reach the route at all, so
 * "you are not the administrator" tells them nothing the missing screen would
 * not. The contract states the 403 on each of the three.
 *
 * <p>The 404 on this surface belongs to the module gate above it
 * ({@code ObModuleGated}), and to an id that genuinely does not exist.
 *
 * <h2>{@code Idempotency-Key} is accepted and not yet honoured</h2>
 *
 * <p>The note every onboarding write in this codebase carries: the 24-hour
 * replay store does not exist. The exposure is small here and worth naming
 * anyway — a retried grant is caught by {@code uq_user_module_access_live} and
 * answers 409, and a retried revoke is caught by the {@code WHERE revoked_at IS
 * NULL} on the update and answers 422. Both are the wrong status for a replay
 * and neither corrupts anything.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/onboarding/module-access")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObModuleAccessController {

    private final ObModuleAccessService service;

    ObModuleAccessController(ObModuleAccessService service) {
        this.service = service;
    }

    /**
     * OB-08's grid.
     *
     * <p>{@code module} is a query parameter rather than a path segment
     * because the screen shows both modules' grants for one person side by
     * side — a dual-access user is the case it exists for, and a path segment
     * would force two requests to render one row.
     *
     * <p>No {@code ETag}: a keyset page has no single version to tag, and
     * CONVENTIONS.md §5 puts tags on detail reads plus the polled or expensive
     * three. This is none of those.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObModuleAccess", summary = "Who can reach a module, and as what (OB-08)")
    ObModuleAccessDtos.GrantListResponse list(
            Authentication caller,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String moduleRole,
            @RequestParam(required = false, defaultValue = "false") boolean includeRevoked,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(identity(caller), module, userId, moduleRole, includeRevoked, cursor, limit);
    }

    /**
     * OB-08's grant.
     *
     * <p>201 with the created grant. No {@code ETag} on it, unlike
     * {@code createObClient}: there is no {@code PATCH} on this resource for a
     * tag to satisfy — a grant is not edited, it is revoked and replaced — so
     * emitting one would be a precondition nobody can use.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "grantObModuleAccess", summary = "Grant a user access to a module (OB-08)")
    ResponseEntity<ObModuleAccessDtos.GrantResponse> grant(
            Authentication caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObModuleAccessDtos.GrantRequest request) {

        ObModuleAccessDtos.Grant granted = service.grant(identity(caller), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ObModuleAccessDtos.GrantResponse(granted));
    }

    /**
     * OB-08's revoke. 200, and the row it answers with is the revoked grant
     * rather than an empty body — so the screen can render
     * {@code revokedBy}, {@code revokedAt} and {@code tokenLagSeconds} without
     * a second read, and say that the withdrawal takes up to that long to
     * reach a token already in someone's hands.
     */
    @PostMapping(path = "/{grantId}/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeObModuleAccess", summary = "Withdraw a grant (OB-08)")
    ObModuleAccessDtos.GrantResponse revoke(
            Authentication caller,
            @PathVariable long grantId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        return new ObModuleAccessDtos.GrantResponse(service.revoke(identity(caller), grantId));
    }

    /**
     * <p>A token this filter chain accepted but {@link CallerIdentity} cannot
     * read is a server fault, not a client one — the same 500 every other
     * onboarding controller takes for it, rather than a 401 that would tell the
     * caller to log in again for a problem logging in again cannot fix.
     */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Caller identity is unreadable"));
    }
}
