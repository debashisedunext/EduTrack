package com.edunext.edutrack.api.feature.masters.priorities;

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

/**
 * B-021 · S-12 — the Priority / Level Master, per {@code contracts/openapi.yaml}.
 *
 * <p><b>{@code listPriorities} has been in the contract, in the MSW mock and in
 * the generated TypeScript client since D-001 with no server behind it</b> —
 * the fifth instance of "declared, mocked, never mounted" this stream has found,
 * after B-023's nine calendar operations, B-014's
 * {@code PATCH /users/{userId}/status}, B-018's two SLA operations and B-020's
 * {@code listTaskTypes}. Nothing failed, because the two screens that call it
 * have only ever run against the mock: {@code CreateTicketPage} builds its
 * {@code LevelPicker} from this route and {@code TicketListPage} builds its
 * level filter from it. {@code MasterRoutesTest} asserts this mount point.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>. Every role may raise a ticket (§2
 *       row 3), a ticket must carry a level, and the create form's level picker
 *       is this route; a role that could not list levels could not raise a
 *       ticket at all. The detail read carries nothing the list does not.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}.</li>
 * </ul>
 *
 * <p><b>{@code master.write}, and §2 does not spell this row out.</b> The row
 * reads "Master data (task types, SLA, workflow, holidays)" — priorities are not
 * in the parenthetical, and B-018 had to make the same argument for the SLA tab.
 * The list is illustrative rather than exhaustive: S-11 and S-12 are consecutive
 * screens in the same Masters section of §7, a level's {@code defaultSlaHrs} is
 * rung 4 of the same §6 ladder the "SLA" in that row refers to, and there is no
 * other capability it could belong to — {@code project.manage} is B-018's
 * discarded alternative and is about a project, not the org's vocabulary.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule and B-015's reason: a hard-coded role check would go on refusing a
 * seventh role that the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: master data is not row-scoped,
 * and every active level is already public through {@code listPriorities}.
 * Recorded in {@code check-conventions.py}'s {@code ROWLESS_403} with that
 * reason, beside the role and task type masters.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class PriorityController {

    private final PriorityService service;

    PriorityController(PriorityService service) {
        this.service = service;
    }

    /**
     * Active levels by default; {@code ?includeInactive=true} for the S-12 grid.
     *
     * <p><b>The default is narrow on purpose and departs from how B-020 and
     * B-064 read their masters</b>, both of which hand retired rows to every
     * caller. {@code PriorityService.list} carries the argument: the two shipped
     * consumers of this route do not filter, because until this task the
     * endpoint could not return a retired row, and both files are Stream C's.
     */
    @GetMapping(path = "/priorities", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listPriorities", summary = "Priority levels (S-12)")
    PriorityDtos.PriorityListResponse priorities(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {

        return new PriorityDtos.PriorityListResponse(service.list(includeInactive));
    }

    /**
     * Exists to emit the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5. B-011 added
     * {@code GET /users/{userId}} for this reason, B-016
     * {@code GET /projects/{projectId}} and B-020
     * {@code GET /masters/task-types/{taskTypeId}}; without it the write is
     * uncallable and the contract describes an operation nobody can reach.
     */
    @GetMapping(path = "/priorities/{priorityId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getPriority", summary = "One priority level (S-12)")
    ResponseEntity<PriorityDtos.PriorityResponse> priority(@PathVariable int priorityId) {
        return ok(service.find(priorityId).orElseThrow(PriorityController::notFound));
    }

    @PostMapping(path = "/priorities",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createPriority", summary = "Create a priority level (S-12)")
    ResponseEntity<PriorityDtos.PriorityResponse> create(
            @Valid @RequestBody PriorityDtos.PriorityWrite write) {

        PriorityDtos.PriorityView created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new PriorityDtos.PriorityResponse(created));
    }

    /**
     * There is no {@code DELETE} mapping on this controller, and its absence is
     * the design rather than an omission — {@code PriorityService}'s javadoc
     * carries the argument. Retiring a level is {@code isActive: false} through
     * this route.
     *
     * <p>The stake here is higher than on the sibling screen. A task type is
     * pointed at by three foreign keys, so a delete would at least <em>fail</em>
     * loudly at the database. Nothing has a foreign key to {@code priorities} —
     * a delete would succeed, and every ticket ever raised at that level would
     * be left rendering a code nothing resolves.
     */
    @PatchMapping(path = "/priorities/{priorityId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updatePriority",
            summary = "Edit a priority level, or retire it (S-12)")
    ResponseEntity<PriorityDtos.PriorityResponse> update(
            @PathVariable int priorityId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody PriorityDtos.PriorityPatch patch) {

        requirePrecondition(priorityId, ifMatch);
        return ok(service.update(priorityId, patch).orElseThrow(PriorityController::notFound));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is refused with 428 rather than allowed through:
     * treating a missing precondition as "no conflict" would mean the guard
     * protects only the clients that already opted in, which is the set that
     * needed it least. Same status and same reasoning as B-015's role writes,
     * B-020's task types and B-023's working week.
     *
     * <p>The 404 comes first. Answering 428 for a level that does not exist
     * would send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(int priorityId, String ifMatch) {
        PriorityDtos.PriorityView current =
                service.find(priorityId).orElseThrow(PriorityController::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the level first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This level changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<PriorityDtos.PriorityResponse> ok(PriorityDtos.PriorityView view) {
        return ResponseEntity.ok().eTag(etagOf(view))
                .body(new PriorityDtos.PriorityResponse(view));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp tag moves when a save rewrites identical values, failing an
     * edit that conflicts with nothing. The three usage counts are in the hash
     * and are the components somebody else's action can change — a ticket raised
     * at this level while its dialog is open costs a reload, which is correct:
     * those counts are what the retire decision was made against.
     *
     * <p><b>This is a 32-bit hash and two states of one row can collide</b>,
     * the same weakness B-019 found the honest way on
     * {@code ProjectSettingsController} and recorded on {@code ProjectController}
     * and {@code SlaPolicyController}. Two booleans moving in opposite
     * directions at the same multiplier in a record's {@code hashCode} cancel
     * exactly — here {@code autoEscalates} and {@code isActive} are the pair, and
     * they are the two the escalation-target rule already prevents moving
     * together. Recorded rather than fixed here: a stronger tag across all four
     * controllers is a change worth making together, not one screen at a time.
     */
    private static String etagOf(PriorityDtos.PriorityView view) {
        return Integer.toHexString(view.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
