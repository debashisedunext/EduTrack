package com.edunext.edutrack.api.feature.onboarding.implementationstages;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

/**
 * OB-15 · the Implementation Stage master.
 *
 * <p>Four operations, shaped exactly as {@code /onboarding/products}' four are,
 * because this is the same kind of thing: a short list of organisation-wide
 * values, read by everybody and written by OB Admin, with no delete.
 *
 * <h2>Not paginated, and the exemption is on the record</h2>
 *
 * <p>{@code check-conventions.py} carries it, on the {@code /masters/task-types}
 * argument this module already reused for {@code /onboarding/products}: six
 * seeded rows, and a screen that draws the whole list because a position is
 * only meaningful beside its neighbours.
 *
 * <h2>Authorisation is {@code isAuthenticated()}, deliberately</h2>
 *
 * <p>OB Admin for the writes is a <em>module</em> role —
 * {@code user_module_access.module_role} — which {@code @PreAuthorize} does not
 * speak; it speaks blueprint §2's six platform roles. {@code ObModuleRoleFilter}
 * applies the module rules on every request from {@code ObModuleRoleRules},
 * where these four routes are declared, so writing a second check here would be
 * two enforcement points over one invariant. {@code ObProductController} makes
 * the same call and states the same reason.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObImplementationStageController {

    private final ObImplementationStageService service;

    ObImplementationStageController(ObImplementationStageService service) {
        this.service = service;
    }

    @GetMapping(value = "/implementation-stages", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObImplementationStages",
            summary = "The implementation stage master (OB-15)")
    ObImplementationStageDtos.ObImplementationStageListResponse list(
            @RequestParam(name = "isActive", required = false) Boolean isActive) {

        List<ObImplementationStageDtos.Stage> data = service.list(isActive);
        return new ObImplementationStageDtos.ObImplementationStageListResponse(data);
    }

    /**
     * <p>Exists to carry the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5, and the gap
     * {@code GET /masters/task-types/{taskTypeId}} closed for its own master. A
     * write whose precondition has no read to draw a tag from is uncallable.
     */
    @GetMapping(value = "/implementation-stages/{stageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObImplementationStage", summary = "One implementation stage (OB-15)")
    ResponseEntity<ObImplementationStageDtos.ObImplementationStageResponse> get(@PathVariable long stageId) {
        return service.find(stageId)
                .map(ObImplementationStageController::ok)
                .orElseThrow(ObImplementationStageController::notFound);
    }

    @PostMapping(value = "/implementation-stages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObImplementationStage",
            summary = "Add an implementation stage (OB-15)")
    ResponseEntity<ObImplementationStageDtos.ObImplementationStageResponse> create(
            @Valid @RequestBody ObImplementationStageDtos.WriteRequest request) {

        // createdBy is left null rather than guessed — ObProductController's
        // call, for its reason: the column is nullable, CallerIdentity is the
        // only honest source, and the audit interceptor already records who
        // made this request.
        ObImplementationStageDtos.Stage created = service.create(request, null);
        return ResponseEntity
                .created(URI.create("/api/v1/onboarding/implementation-stages/" + created.id()))
                .eTag(etagOf(created))
                .body(new ObImplementationStageDtos.ObImplementationStageResponse(created));
    }

    /**
     * Rename, retire, or move to a different position.
     *
     * <p><b>A successful move changes rows this response does not describe</b> —
     * everything between the old position and the new one shifts by one. The
     * screen refetches the list rather than patching its cache from this body,
     * which is why the response stays the single edited row rather than growing
     * into the whole master.
     */
    @PatchMapping(value = "/implementation-stages/{stageId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObImplementationStage",
            summary = "Rename, reorder or retire an implementation stage (OB-15)")
    ResponseEntity<ObImplementationStageDtos.ObImplementationStageResponse> update(
            @PathVariable long stageId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObImplementationStageDtos.WriteRequest request) {

        requirePrecondition(stageId, ifMatch);
        return service.update(stageId, request)
                .map(ObImplementationStageController::ok)
                .orElseThrow(ObImplementationStageController::notFound);
    }

    /**
     * <p><b>The 404 comes first.</b> Answering 428 for a stage that does not
     * exist would send the caller to fetch a tag from a URL that will 404 too —
     * the ordering {@code ClientController} settled on and
     * {@code ObProductController} repeats.
     */
    private void requirePrecondition(long id, String ifMatch) {
        ObImplementationStageDtos.Stage current = service.find(id)
                .orElseThrow(ObImplementationStageController::notFound);

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the stage first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This stage changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<ObImplementationStageDtos.ObImplementationStageResponse> ok(
            ObImplementationStageDtos.Stage stage) {

        return ResponseEntity.ok()
                .eTag(etagOf(stage))
                .body(new ObImplementationStageDtos.ObImplementationStageResponse(stage));
    }

    /**
     * Derived from the content, not from {@code updated_at} — a timestamp tag
     * moves when a save rewrites identical values, failing an edit that
     * conflicts with nothing.
     *
     * <p><b>{@code sequence} is inside the tag, and that is the point rather
     * than an accident.</b> Somebody else moving this stage while an edit form
     * is open changes the position the form is showing, so the position it is
     * about to send means something different from what its author intended.
     * That is precisely the save worth refusing with a 412.
     */
    private static String etagOf(ObImplementationStageDtos.Stage stage) {
        return Integer.toHexString(stage.hashCode());
    }

    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /**
     * 404, never 403 — CONVENTIONS.md §7, and the same no-existence-leak rule
     * the module gate applies one level up.
     */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such implementation stage.");
    }
}
