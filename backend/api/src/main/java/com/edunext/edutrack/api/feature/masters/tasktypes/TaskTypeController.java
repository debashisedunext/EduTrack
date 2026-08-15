package com.edunext.edutrack.api.feature.masters.tasktypes;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-020 · S-11 — the Task Type Master, per {@code contracts/openapi.yaml}.
 *
 * <p><b>{@code listTaskTypes} has been in the contract, in the MSW mock and in
 * the generated TypeScript client since D-001 with no server behind it</b> —
 * the fourth instance of "declared, mocked, never mounted" this stream has
 * found, after B-023's nine calendar operations, B-014's
 * {@code PATCH /users/{userId}/status} and B-018's two SLA operations. Nothing
 * failed, because the three screens that call it have only ever run against the
 * mock. {@code MasterRoutesTest} asserts this mount point too.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>. Every role may raise a ticket (§2),
 *       a ticket must name a task type, and the create form's type picker is
 *       this route; a role that could not list task types could not raise a
 *       ticket at all. The detail read carries nothing the list does not.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}.</li>
 * </ul>
 *
 * <p><b>{@code master.write}, and this is the row §2 names it in.</b> "Master
 * data (task types, SLA, workflow, holidays)" is ✅ for Admin and ❌ for the
 * other five, with task types listed first. B-018 had to argue this against the
 * adjacent {@code project.manage}; here there is nothing to argue — B-001's own
 * seed description of the capability opens with the words "task types".
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule and B-015's reason: a hard-coded role check would go on refusing a
 * seventh role that the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: master data is not row-scoped,
 * and every task type is already public through {@code listTaskTypes}. Recorded
 * in {@code check-conventions.py}'s {@code ROWLESS_403} with that reason,
 * beside the role master's three entries.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class TaskTypeController {

    private final TaskTypeService service;

    TaskTypeController(TaskTypeService service) {
        this.service = service;
    }

    @GetMapping(path = "/task-types", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listTaskTypes", summary = "Task types (S-11)")
    TaskTypeDtos.TaskTypeListResponse taskTypes() {
        return new TaskTypeDtos.TaskTypeListResponse(service.list());
    }

    /**
     * Exists to emit the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5. B-011 added
     * {@code GET /users/{userId}} for this reason and B-016 added
     * {@code GET /projects/{projectId}}; without it the write is uncallable and
     * the contract describes an operation nobody can reach.
     */
    @GetMapping(path = "/task-types/{taskTypeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getTaskType", summary = "One task type (S-11)")
    ResponseEntity<TaskTypeDtos.TaskTypeResponse> taskType(@PathVariable int taskTypeId) {
        return ok(service.find(taskTypeId).orElseThrow(TaskTypeController::notFound));
    }

    @PostMapping(path = "/task-types",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createTaskType", summary = "Create a task type (S-11)")
    ResponseEntity<TaskTypeDtos.TaskTypeResponse> create(
            @Valid @RequestBody TaskTypeDtos.TaskTypeWrite write) {

        TaskTypeDtos.TaskTypeView created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new TaskTypeDtos.TaskTypeResponse(created));
    }

    /**
     * There is no {@code DELETE} mapping on this controller, and its absence is
     * the design rather than an omission — {@code TaskTypeService}'s javadoc
     * carries the argument. Retiring a type is {@code isActive: false} through
     * this route.
     */
    @PatchMapping(path = "/task-types/{taskTypeId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateTaskType", summary = "Edit a task type, or retire it (S-11)")
    ResponseEntity<TaskTypeDtos.TaskTypeResponse> update(
            @PathVariable int taskTypeId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody TaskTypeDtos.TaskTypePatch patch) {

        requirePrecondition(taskTypeId, ifMatch);
        return ok(service.update(taskTypeId, patch).orElseThrow(TaskTypeController::notFound));
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
     * needed it least. Same status and same reasoning as B-015's role writes and
     * B-023's working week.
     *
     * <p>The 404 comes first. Answering 428 for a type that does not exist would
     * send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(int taskTypeId, String ifMatch) {
        TaskTypeDtos.TaskTypeView current =
                service.find(taskTypeId).orElseThrow(TaskTypeController::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the task type first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This task type changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<TaskTypeDtos.TaskTypeResponse> ok(TaskTypeDtos.TaskTypeView view) {
        return ResponseEntity.ok().eTag(etagOf(view))
                .body(new TaskTypeDtos.TaskTypeResponse(view));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp tag moves when a save rewrites identical values, failing an
     * edit that conflicts with nothing. {@code ticketCount} is in the hash and
     * is the one component somebody else's action can change — a ticket raised
     * against the type while its dialog is open costs a reload, which is
     * correct: that count is what the deactivate decision was made against.
     */
    private static String etagOf(TaskTypeDtos.TaskTypeView view) {
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
