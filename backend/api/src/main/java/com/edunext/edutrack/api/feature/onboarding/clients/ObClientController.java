package com.edunext.edutrack.api.feature.onboarding.clients;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-102 · {@code /onboarding/clients} per {@code contracts/openapi.yaml} —
 * OB-03's list, OB-04's create and OB-05's read and edit.
 *
 * <h2>Auth: {@code isAuthenticated()} only — the interim state every onboarding
 * controller in this codebase declares</h2>
 *
 * <p>{@code ModuleAccessGuard} (A-111) is written and not yet wired into
 * {@code SecurityConfig}; {@code ObJourneyTemplateController}'s class javadoc
 * states the position first and this is the sixth controller to repeat it. The
 * onboarding module has its own role vocabulary — OB Admin, OB Manager, OB
 * Viewer, OB Sales, OB Step Owner, in {@code user_module_access} — which is not
 * blueprint §2's six and is not what {@code @PreAuthorize} or
 * {@code RolePermissions} can speak. Encoding a §2 platform-role restriction
 * here would assert a rule nobody decided.
 *
 * <p>What is enforced, and enforced inside the service rather than here:
 * <b>row scope</b> ({@link ObClientScope}, A-112's rule) on every read, so a
 * caller with no onboarding standing gets an empty list and 404 by id; and the
 * <b>write roles</b>, so the same caller gets 404 on the create and a Viewer
 * gets 403 on the edit. See {@code NotAnOnboardingClientWriterException} and
 * {@code ObClientReadOnlyException} for why those two statuses differ.
 *
 * <h2>{@code Idempotency-Key} is accepted and not yet honoured</h2>
 *
 * <p>The header every onboarding route in this codebase carries with the same
 * note: the 24-hour replay store does not exist yet. Worth more here than on
 * most, because this create is the module's widest side effect — a retried
 * wizard submission after a network timeout currently produces a second client,
 * caught by the PAN guard where a PAN was given and by the similar-name warning
 * where one was not.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/onboarding/clients")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObClientController {

    private final ObClientService service;
    private final ObClientWriteService writes;

    ObClientController(ObClientService service, ObClientWriteService writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * OB-03's grid.
     *
     * <p>No {@code ETag}: a keyset page has no single version to tag, and
     * CONVENTIONS.md §5 puts tags on detail reads plus the three that are
     * polled or expensive. This is none of those.
     *
     * <p>B-108 · {@code ownerId} is the screen's "my clients" filter and is the
     * only parameter here that is not a column on the row it filters. See the
     * contract's note on it, and {@code ObClientReadRepository.LIST_FILTERS}
     * for why it is written out rather than delegated to {@link ObClientScope}.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObClients", summary = "Onboarding client list (OB-03)")
    ObClientDtos.ObClientListResponse list(
            Authentication caller,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String rag,
            @RequestParam(required = false) String gateStatus,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long salesPersonId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(scopeOf(caller), q, status, rag, gateStatus, productId, salesPersonId,
                ownerId, cursor, limit);
    }

    /**
     * OB-05's page, and <b>the only source of the {@code ETag} the
     * {@code PATCH} requires</b>.
     *
     * <p>CONVENTIONS.md §5's standing warning: a {@code PATCH} whose
     * {@code If-Match} has no read to come from is not a strict endpoint, it is
     * a broken one. B-011, B-016 and B-026 each closed that gap after the fact;
     * this one ships paired.
     */
    @GetMapping(path = "/{obClientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObClient", summary = "Client detail (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> get(
            Authentication caller, @PathVariable long obClientId) {

        return ok(service.findDetail(scopeOf(caller), obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId)));
    }

    /**
     * OB-04, committing all four wizard steps in one request.
     *
     * <p>The {@code ETag} is emitted on the 201 so the wizard's confirmation
     * step can go straight to editing the client it just created without a
     * second read — {@code ClientController}'s own reason for tagging a create.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObClient", summary = "Board a client (OB-04)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> create(
            Authentication caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientDtos.ObClientCreateRequest request) {

        ObClientDtos.ObClientDetail created =
                writes.create(scopeOf(caller), userId(caller), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ObClientETag.of(created))
                .body(new ObClientDtos.ObClientDetailResponse(created));
    }

    /**
     * OB-05's Client info card.
     *
     * <p>Partial by field — see {@link ObClientUpdateRequest} for why the body
     * is a class with setters where everything else in this package is a
     * record.
     */
    @PatchMapping(path = "/{obClientId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObClient", summary = "Edit client info (OB-05)")
    ResponseEntity<ObClientDtos.ObClientDetailResponse> update(
            Authentication caller,
            @PathVariable long obClientId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObClientUpdateRequest request) {

        requirePrecondition(scopeOf(caller), obClientId, ifMatch);
        return ok(writes.update(scopeOf(caller), obClientId, request));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The 404 comes first, then the precondition.
     *
     * <p>Answering 428 for a client that does not exist — or that this caller
     * cannot see — would send them to fetch a tag from a URL that will 404 too.
     * The rules themselves are {@link ObClientETag}, shared with
     * {@link ObContactController} since B-103: two controllers writing one
     * resource have to derive its tag identically or produce a 412 that appears
     * only when both screens are used together.
     */
    private void requirePrecondition(ObClientScope scope, long obClientId, String ifMatch) {
        ObClientDtos.ObClientDetail current = service.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
        ObClientETag.require(ifMatch, current);
    }

    private static ResponseEntity<ObClientDtos.ObClientDetailResponse> ok(
            ObClientDtos.ObClientDetail client) {

        return ResponseEntity.ok()
                .eTag(ObClientETag.of(client))
                .body(new ObClientDtos.ObClientDetailResponse(client));
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identity(caller));
    }

    private static long userId(Authentication caller) {
        return identity(caller).userId();
    }

    /**
     * {@link IllegalStateException} rather than a silent fallback, on
     * {@code CallerIdentityAccess}'s own reasoning: every route here sits
     * behind {@code authenticated()}, so an unreadable identity means the chain
     * accepted a token this class cannot read — a bug worth a loud 500, not a
     * client quietly attributed to nobody.
     */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-clients route reached with no resolvable "
                                + "caller identity"));
    }
}
