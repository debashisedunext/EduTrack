package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * B-125 · {@code /onboarding/prereq-tasks/{prereqTaskId}} — CP-04's page, the
 * OB-05 task row, the four transitions, the thread and the chain.
 *
 * <h2>One shape for both principals</h2>
 *
 * <p>A prerequisite is the one object in this module a client and a staff
 * member genuinely share. Plan §11's never-visible list — owners, internal
 * comms, block reasons, TAT internals — is about <em>journeys</em>, and none
 * of it is on this row. There is nothing here to hide, so there is no second
 * serializer to drift.
 *
 * <h2>Where the four transitions differ on who may call them</h2>
 *
 * <ul>
 *   <li>{@code submit} — both principals. Plan §4 records
 *       {@code submitted_via} precisely because a SPOC frequently emails a
 *       document instead of using the portal; without a staff path the
 *       implementor would log in as the client, which is how shared
 *       credentials start.</li>
 *   <li>{@code verify} and {@code return} — staff. A client cannot verify
 *       their own work, which is the entire point of the state.</li>
 *   <li>{@code skip} — Admin and Manager only, and <b>403 rather than
 *       404</b>: the caller has already read this task, so denying its
 *       existence would refuse a row they are looking at. Out-of-scope tasks
 *       are still 404 — that is the scope guard, not this gate.</li>
 * </ul>
 *
 * <p><b>The client principal's own path is not wired here.</b>
 * {@code ClientPrincipal} (A-125) and its resolver (A-126) serve
 * {@code /api/v1/portal/**}, and CP-04's routes are C-121's. Every route
 * below therefore resolves a staff caller; {@code submit} is written to take
 * a contact id so the portal path is one caller away rather than a redesign.
 */
@RestController
@RequestMapping("/api/v1/onboarding/prereq-tasks")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObPrereqTaskController {

    private final ObPrereqTaskService tasks;
    private final ObClientPrereqAssembler assembler;
    private final ObPrereqThreadRepository thread;
    private final ObJourneyGateReader journeys;
    private final ObPrereqClientVisibility visibility;

    ObPrereqTaskController(ObPrereqTaskService tasks,
                           ObClientPrereqAssembler assembler,
                           ObPrereqThreadRepository thread,
                           ObJourneyGateReader journeys,
                           ObPrereqClientVisibility visibility) {
        this.tasks = tasks;
        this.assembler = assembler;
        this.thread = thread;
        this.journeys = journeys;
        this.visibility = visibility;
    }

    @GetMapping(path = "/{prereqTaskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObClientPrereqTask", summary = "One prerequisite task in full (CP-04)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqTaskDetailResponse> get(
            @PathVariable long prereqTaskId, Authentication caller) {

        ObClientPrereqTask task = requireVisible(prereqTaskId, caller);
        ObClientPrereqDtos.ObClientPrereqTaskDetail detail =
                ObClientPrereqDtos.ObClientPrereqTaskDetail.of(
                        assembler.task(task),
                        assembler.referenceDocsOf(task.getTemplateTaskId()),
                        assembler.submissionsOf(prereqTaskId));

        return ResponseEntity.ok()
                .eTag(Integer.toHexString(detail.hashCode()))
                .body(new ObClientPrereqDtos.ObClientPrereqTaskDetailResponse(detail));
    }

    @PatchMapping(path = "/{prereqTaskId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObClientPrereqTask",
            summary = "Edit a task's wording or due date (OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqTaskResponse> update(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObClientPrereqDtos.ObClientPrereqTaskUpdateRequest request,
            Authentication caller) {

        ObClientPrereqTask task = requireWritable(prereqTaskId, caller);
        requirePrecondition(task, ifMatch);

        ObClientPrereqTask updated = tasks.update(prereqTaskId,
                request.title(), request.description(), request.tatDays());
        return ResponseEntity.ok(
                new ObClientPrereqDtos.ObClientPrereqTaskResponse(assembler.task(updated)));
    }

    @PostMapping(path = "/{prereqTaskId}/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitObClientPrereqTask",
            summary = "Mark a task done and send it for verification (CP-04)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqTaskResponse> submit(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) @Valid ObClientPrereqDtos.ObPrereqSubmitRequest request,
            Authentication caller) {

        requireVisible(prereqTaskId, caller);
        String note = request == null ? null : request.note();

        // Staff path only, for now: the portal principal reaches this
        // operation through C-121's own routes. `contactId` null therefore
        // means STAFF, which is what `submitted_via` records.
        ObClientPrereqTask updated =
                tasks.submit(prereqTaskId, identity(caller).userId(), null, note);
        return ResponseEntity.ok(
                new ObClientPrereqDtos.ObClientPrereqTaskResponse(assembler.task(updated)));
    }

    @PostMapping(path = "/{prereqTaskId}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "verifyObClientPrereqTask",
            summary = "Accept a submission — and, on the last one, open the gate (OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObPrereqGateResultResponse> verify(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) @Valid ObClientPrereqDtos.ObPrereqVerifyRequest request,
            Authentication caller) {

        requireWritable(prereqTaskId, caller);
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(gateResult(
                tasks.verify(prereqTaskId, identity(caller).userId(), note)));
    }

    @PostMapping(path = "/{prereqTaskId}/return",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "returnObClientPrereqTask",
            summary = "Send a submission back to the client (OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqTaskResponse> returnToClient(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientPrereqDtos.ObPrereqReturnRequest request,
            Authentication caller) {

        requireWritable(prereqTaskId, caller);
        ObClientPrereqTask updated = tasks.returnToClient(
                prereqTaskId, identity(caller).userId(), request.comment());
        return ResponseEntity.ok(
                new ObClientPrereqDtos.ObClientPrereqTaskResponse(assembler.task(updated)));
    }

    /**
     * The gate's only valve. Admin and Manager only — and the 403 is
     * deliberate where every other refusal in this module is a 404: the
     * caller has already read this task, so denying its existence would
     * refuse a row they are looking at.
     */
    @PostMapping(path = "/{prereqTaskId}/skip",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "skipObClientPrereqTask",
            summary = "Waive a non-mandatory task — the gate's only valve (OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObPrereqGateResultResponse> skip(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientPrereqDtos.ObPrereqSkipRequest request,
            Authentication caller) {

        ObClientPrereqTask task = requireVisible(prereqTaskId, caller);
        if (!ObClientScope.of(identity(caller)).isModerator()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Waiving a prerequisite is an Onboarding Admin or Manager action.");
        }

        return ResponseEntity.ok(gateResult(
                tasks.skip(task.getId(), identity(caller).userId(), request.reason())));
    }

    // ── the thread and the chain ──────────────────────────────────────

    @GetMapping(path = "/{prereqTaskId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObPrereqComments",
            summary = "The task's comment thread, oldest first (CP-04, OB-05)")
    ObClientPrereqDtos.ObPrereqCommentListResponse comments(
            @PathVariable long prereqTaskId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            Authentication caller) {

        requireVisible(prereqTaskId, caller);
        return thread.comments(prereqTaskId, cursor, limit);
    }

    @PostMapping(path = "/{prereqTaskId}/comments",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObPrereqComment", summary = "Say something about this task (CP-04, OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObPrereqCommentResponse> addComment(
            @PathVariable long prereqTaskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ObClientPrereqDtos.ObPrereqCommentCreateRequest request,
            Authentication caller) {

        requireVisible(prereqTaskId, caller);
        // The author comes from the token and is never in the body — a client
        // who could name their own author id could write as a member of staff.
        long id = tasks.addComment(prereqTaskId, ObPrereqActorType.STAFF,
                identity(caller).userId(), null, request.body(), false);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ObClientPrereqDtos.ObPrereqCommentResponse(thread.comment(id)));
    }

    @GetMapping(path = "/{prereqTaskId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObPrereqHistory",
            summary = "Every state change on this task, oldest first (OB-05)")
    ObClientPrereqDtos.ObPrereqHistoryListResponse history(
            @PathVariable long prereqTaskId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit,
            Authentication caller) {

        requireVisible(prereqTaskId, caller);
        return thread.history(prereqTaskId, cursor, limit);
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private ObClientPrereqDtos.ObPrereqGateResultResponse gateResult(ObPrereqTaskService.Settled settled) {
        return new ObClientPrereqDtos.ObPrereqGateResultResponse(
                new ObClientPrereqDtos.ObPrereqGateResult(
                        assembler.task(settled.task()),
                        settled.gate().gateStatus(),
                        settled.gate().gateOpened(),
                        settled.gate().openedJourneyIds(),
                        settled.progress().mandatoryTotal(),
                        settled.progress().mandatoryVerified()));
    }

    /**
     * The scope guard: a task on a client this caller cannot see answers 404,
     * exactly as a task that does not exist does.
     */
    private ObClientPrereqTask requireVisible(long prereqTaskId, Authentication caller) {
        ObClientPrereqTask task = tasks.require(prereqTaskId);
        if (!visibility.isVisible(ObClientScope.of(identity(caller)), task.getObClientId())) {
            throw new PrereqTaskNotFoundException(prereqTaskId);
        }
        return task;
    }

    /** Visible, and this caller may write to the client — Viewer and Step Owner may not. */
    private ObClientPrereqTask requireWritable(long prereqTaskId, Authentication caller) {
        ObClientPrereqTask task = requireVisible(prereqTaskId, caller);
        if (!ObClientScope.of(identity(caller)).mayWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your onboarding role is read-only on this client's checklist.");
        }
        return task;
    }

    private void requirePrecondition(ObClientPrereqTask task, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the task first and send back its ETag.");
        }
        ObClientPrereqDtos.ObClientPrereqTaskDetail current =
                ObClientPrereqDtos.ObClientPrereqTaskDetail.of(
                        assembler.task(task),
                        assembler.referenceDocsOf(task.getTemplateTaskId()),
                        assembler.submissionsOf(task.getId()));

        String candidate = ifMatch.trim();
        boolean ok = "*".equals(candidate)
                || candidate.replace("W/", "").replace("\"", "")
                        .equals(Integer.toHexString(current.hashCode()));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This task changed since you read it. Reload and reapply your edit.");
        }
    }

    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-prereqs route reached with no resolvable "
                                + "caller identity"));
    }
}
