package com.edunext.edutrack.api.feature.onboarding.projects;

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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * {@code /onboarding/projects} — the Projects grid, the New Project create and
 * the project header.
 *
 * <h2>Auth: {@code isAuthenticated()} only — the interim state every onboarding
 * controller in this codebase declares</h2>
 *
 * <p>{@code ModuleAccessGuard} is written and not yet wired into
 * {@code SecurityConfig}, and the onboarding module's roles — OB Admin, OB
 * Manager, OB Viewer, OB Sales, OB Step Owner — are not blueprint §2's six and
 * are not what {@code @PreAuthorize} can speak. Encoding a platform-role
 * restriction here would assert a rule nobody decided.
 *
 * <p>What is enforced, and enforced inside the services rather than here:
 * <b>row scope</b> ({@code ObClientScope}, applied to the project's client) on
 * every read, so a caller with no onboarding standing gets an empty list and a
 * 404 by id; and the <b>write roles</b>, so the same caller gets 404 on the
 * create and a Viewer gets 403 on the edit. {@link NotAnOnboardingProjectWriterException}
 * and {@link ObProjectReadOnlyException} carry the argument for why those two
 * statuses differ.
 *
 * <h2>{@code DELETE} removes only what never ran</h2>
 *
 * <p>A project owns journeys, and those journeys own hash-chained
 * {@code ob_step_history} rows, so deleting one that has been worked is not a
 * tidy-up — it is the removal of an audit trail. {@code ObProjectDeletionGuard}
 * asks the nine tables that point at a project's steps before anything is
 * removed, and refuses with {@code 409} naming what is in the way.
 *
 * <p>What is left deletable is the case the button is for: a project created
 * against the wrong client or the wrong product, before anybody touched it.
 * Everything else is {@code DROPPED} with a reason — which keeps the record,
 * stops the delay clock (see {@code ObProjectStatus.accruesDelay}) and leaves
 * the ribbon readable.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/onboarding/projects")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObProjectController {

    private final ObProjectService service;
    private final ObProjectWriteService writes;

    ObProjectController(ObProjectService service, ObProjectWriteService writes) {
        this.service = service;
        this.writes = writes;
    }

    /**
     * The Projects grid.
     *
     * <p>No {@code ETag}: a keyset page has no single version to tag, and
     * CONVENTIONS.md §5 puts tags on detail reads plus the three that are polled
     * or expensive. This is none of those.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObProjects", summary = "Onboarding project list")
    ObProjectDtos.ObProjectListResponse list(
            Authentication caller,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long implementorId,
            @RequestParam(required = false) Long salesPersonId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(scopeOf(caller), q, clientId, productId, status, implementorId,
                salesPersonId, cursor, limit);
    }

    /**
     * The project header, and <b>the only source of the {@code ETag} the
     * {@code PATCH} requires</b>.
     *
     * <p>CONVENTIONS.md §5's standing warning: a {@code PATCH} whose
     * {@code If-Match} has no read to come from is not a strict endpoint, it is
     * a broken one. This one ships paired.
     */
    @GetMapping(path = "/{obProjectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObProject", summary = "Project header")
    ResponseEntity<ObProjectDtos.ObProjectDetailResponse> get(
            Authentication caller, @PathVariable long obProjectId) {

        return ok(service.findDetail(scopeOf(caller), obProjectId)
                .orElseThrow(() -> new ObProjectNotFoundException(obProjectId)));
    }

    /**
     * The New Project form, committing the purchase, the project, the
     * checklist and the journeys in one request.
     *
     * <p>{@code Idempotency-Key} is accepted and not yet honoured — the header
     * every onboarding route in this codebase carries with the same note, and
     * it is worth more here than on most: this create is the module's widest
     * side effect, and a retried submission after a network timeout currently
     * answers {@code 409} from the duplicate guard rather than producing a
     * second project. That the guard catches it is luck of the right kind, not
     * a substitute for the replay store.
     *
     * <p>The {@code ETag} is emitted on the 201 so the form can go straight to
     * the project's own page without a second read.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObProject", summary = "Create a project")
    ResponseEntity<ObProjectDtos.ObProjectDetailResponse> create(
            Authentication caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObProjectDtos.ObProjectCreateRequest request) {

        ObProjectDtos.ObProjectDetail created = writes.create(scopeOf(caller), request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ObProjectETag.of(created))
                .body(new ObProjectDtos.ObProjectDetailResponse(created));
    }

    @PatchMapping(path = "/{obProjectId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObProject", summary = "Edit the project header")
    ResponseEntity<ObProjectDtos.ObProjectDetailResponse> update(
            Authentication caller,
            @PathVariable long obProjectId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObProjectDtos.ObProjectUpdateRequest request) {

        requirePrecondition(scopeOf(caller), obProjectId, ifMatch);
        return ok(writes.update(scopeOf(caller), obProjectId, request));
    }

    /**
     * Remove a project nothing records having run.
     *
     * <p><b>No {@code If-Match}.</b> A precondition protects a lost update —
     * two people editing one record — and a delete has no such failure: the
     * guard re-asks, inside the transaction, whether anything now points at
     * this project, so a sign-off recorded a second ago refuses the delete
     * whatever tag the caller is holding.
     *
     * <p>204, with no body. There is nothing left to return.
     */
    @DeleteMapping(path = "/{obProjectId}")
    @Operation(operationId = "deleteObProject", summary = "Delete a project that never ran")
    ResponseEntity<Void> delete(Authentication caller, @PathVariable long obProjectId) {
        writes.delete(scopeOf(caller), obProjectId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The 404 comes first, then the precondition.
     *
     * <p>Answering 428 for a project that does not exist — or that this caller
     * cannot see — would send them to fetch a tag from a URL that will 404 too.
     */
    private void requirePrecondition(ObClientScope scope, long obProjectId, String ifMatch) {
        ObProjectDtos.ObProjectDetail current = service.findDetail(scope, obProjectId)
                .orElseThrow(() -> new ObProjectNotFoundException(obProjectId));
        ObProjectETag.require(ifMatch, current);
    }

    private static ResponseEntity<ObProjectDtos.ObProjectDetailResponse> ok(
            ObProjectDtos.ObProjectDetail project) {

        return ResponseEntity.ok()
                .eTag(ObProjectETag.of(project))
                .body(new ObProjectDtos.ObProjectDetailResponse(project));
    }

    private static ObClientScope scopeOf(Authentication caller) {
        return ObClientScope.of(identity(caller));
    }

    /**
     * {@link IllegalStateException} rather than a silent fallback: every route
     * here sits behind {@code authenticated()}, so an unreadable identity means
     * the chain accepted a token this class cannot read — a bug worth a loud
     * 500, not a project quietly attributed to nobody.
     */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-projects route reached with no resolvable "
                                + "caller identity"));
    }
}
