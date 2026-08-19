package com.edunext.edutrack.api.feature.masters.statuses;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * B-039 · S-13 tab 1 — the Status Master and its transition matrix, per
 * {@code contracts/openapi.yaml}.
 *
 * <p><b>Six operations, and none of them existed in any form before this task.</b>
 * B-021's priorities were "declared, mocked, never mounted" — the fifth such case
 * this stream found. {@code statuses} and {@code workflow_transitions} are the
 * opposite failure and a quieter one: two seeded masters, eighty-two rows, no
 * contract path, no mock, no client, no screen. Nothing was broken, because
 * nothing could reach them. {@code MasterRoutesTest} pins these mount points so
 * that stops being true silently.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>, on both the statuses and the matrix.
 *       Every role may raise a ticket (§2 row 3) and every ticket carries a
 *       status, so a role that could not list statuses could not render its own
 *       ticket's chip. The matrix read is open for a stronger reason: a ticket
 *       detail page has to know which moves to <em>offer</em>, and that is this
 *       route. Concealing it would not conceal the policy — a user discovers it
 *       by pressing a button and being refused.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}.</li>
 * </ul>
 *
 * <p><b>{@code master.write}, and §2 names this one explicitly</b> — unlike
 * B-021's priorities, which had to be argued. The row reads "Master data (task
 * types, SLA, <b>workflow</b>, holidays)", and S-13 is titled "Status, Stage &
 * Workflow Template Master". The transition matrix is the workflow policy itself.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's rule
 * and B-015's reason: a hard-coded role check would go on refusing a seventh role
 * the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: master data is not row-scoped,
 * and every active status is already public through {@code listStatuses}.
 * Recorded in {@code check-conventions.py}'s {@code ROWLESS_403} with that reason,
 * beside the role, task type and priority masters.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class StatusController {

    private final StatusService service;
    private final StatusTransitionService matrix;

    StatusController(StatusService service, StatusTransitionService matrix) {
        this.service = service;
        this.matrix = matrix;
    }

    // ------------------------------------------------------------------
    // Statuses
    // ------------------------------------------------------------------

    /**
     * Active statuses by default; {@code ?includeInactive=true} for the S-13 grid.
     *
     * <p>The narrow default follows B-021 rather than B-020, and
     * {@code StatusService.list} carries the argument: nothing filters this list
     * downstream.
     */
    @GetMapping(path = "/statuses", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listStatuses", summary = "Ticket statuses (S-13 tab 1)")
    StatusDtos.StatusListResponse statuses(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {

        return new StatusDtos.StatusListResponse(service.list(includeInactive));
    }

    /**
     * Exists to emit the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5. B-011, B-016, B-020 and B-021 added
     * the same route for users, projects, task types and levels; without it the
     * write is uncallable and the contract describes an operation nobody can
     * reach.
     */
    @GetMapping(path = "/statuses/{statusId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getStatus", summary = "One status (S-13 tab 1)")
    ResponseEntity<StatusDtos.StatusResponse> status(@PathVariable int statusId) {
        return ok(service.find(statusId).orElseThrow(StatusController::notFound));
    }

    @PostMapping(path = "/statuses",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createStatus", summary = "Create a status (S-13 tab 1)")
    ResponseEntity<StatusDtos.StatusResponse> create(
            @Valid @RequestBody StatusDtos.StatusWrite write) {

        StatusDtos.StatusView created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new StatusDtos.StatusResponse(created));
    }

    /**
     * There is no {@code DELETE} mapping on this controller, and its absence is
     * the design — {@code StatusService}'s javadoc carries the argument.
     *
     * <p>The stake is the highest of the three masters that omit one. A task type
     * is pointed at by three foreign keys, so a delete would at least <em>fail</em>
     * loudly. A level has none, so a delete would succeed and leave old tickets
     * rendering a code nothing resolves — bad, but only cosmetic. A status has
     * none either <em>and</em> is the left-hand side of every transition lookup:
     * deleting one strands every ticket in it with no move offered anywhere.
     * Retiring is {@code isActive: false}, and even that refuses while tickets are
     * still there.
     */
    @PatchMapping(path = "/statuses/{statusId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateStatus", summary = "Edit a status, or retire it (S-13 tab 1)")
    ResponseEntity<StatusDtos.StatusResponse> update(
            @PathVariable int statusId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody StatusDtos.StatusPatch patch) {

        requirePrecondition(statusId, ifMatch);
        StatusDtos.StatusView updated =
                service.update(statusId, patch).orElseThrow(StatusController::notFound);

        // The tag is taken over the row as it now reads, which deliberately
        // excludes deactivatedTransitions — see StatusDtos.StatusView. A tag that
        // depended on how the row was last written would fail the next edit for
        // no reason.
        return ResponseEntity.ok().eTag(etagOf(updated))
                .body(new StatusDtos.StatusResponse(updated));
    }

    // ------------------------------------------------------------------
    // The transition matrix
    // ------------------------------------------------------------------

    /**
     * The whole matrix, or one role's column — and it carries its own
     * {@code ETag}.
     *
     * <p><b>The only collection read in this contract that emits one</b>, and the
     * reason is that it is the only collection that is itself the unit of edit.
     * Everywhere else a collection is a view over rows written one at a time, so
     * the precondition belongs on the row route; there is no row route here
     * because there is no single-cell verb. Without this tag the {@code PUT} would
     * have had to be exempted from {@code If-Match}, and a whole-matrix replace is
     * exactly the write where a lost update is worst: the loser's cells vanish
     * with nothing to indicate they were ever there.
     */
    @GetMapping(path = "/status-transitions", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listStatusTransitions",
            summary = "The allowed-transition matrix, per role (S-13 tab 1)")
    ResponseEntity<StatusDtos.TransitionMatrixResponse> transitions(
            @RequestParam(name = "roleCode", required = false) String roleCode) {

        List<StatusDtos.TransitionView> rows = matrix.list(roleCode);
        return ResponseEntity.ok().eTag(etagOfMatrix()).
                body(new StatusDtos.TransitionMatrixResponse(rows));
    }

    /**
     * {@code PUT}, because the matrix is authored as a whole and the one invariant
     * worth having — at least one on-create row survives — is uncheckable against
     * a single cell.
     */
    @PutMapping(path = "/status-transitions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "replaceStatusTransitions",
            summary = "Replace the transition matrix (S-13 tab 1)")
    ResponseEntity<StatusDtos.TransitionMatrixResponse> replaceTransitions(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody StatusDtos.TransitionMatrixWrite write) {

        requireMatrixPrecondition(ifMatch);
        List<StatusDtos.TransitionView> rows = matrix.replace(write);
        return ResponseEntity.ok().eTag(etagOfMatrix())
                .body(new StatusDtos.TransitionMatrixResponse(rows));
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
     * B-020's task types, B-021's levels and B-023's working week.
     *
     * <p>The 404 comes first. Answering 428 for a status that does not exist would
     * send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(int statusId, String ifMatch) {
        StatusDtos.StatusView current =
                service.find(statusId).orElseThrow(StatusController::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the status first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This status changed since you read it. Reload and reapply your edit.");
        }
    }

    /**
     * The matrix has no 404 to order in front of the 428 — the resource is the
     * whole table and it always exists. So this one is the plain two-step.
     */
    private void requireMatrixPrecondition(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the matrix first and send back its ETag. "
                            + "A replace without one would silently discard whatever another "
                            + "Admin saved while this screen was open.");
        }
        if (!matches(ifMatch, etagOfMatrix())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "The matrix changed since you read it. Reload and reapply your edit — "
                            + "saving now would delete cells somebody else has just added.");
        }
    }

    private static ResponseEntity<StatusDtos.StatusResponse> ok(StatusDtos.StatusView view) {
        return ResponseEntity.ok().eTag(etagOf(view))
                .body(new StatusDtos.StatusResponse(view));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp tag moves when a save rewrites identical values, failing an
     * edit that conflicts with nothing. Both usage counts are in the hash and are
     * the components somebody else's action can change — a ticket moving into this
     * status while its dialog is open costs a reload, which is correct: that count
     * is what the retire decision was made against.
     *
     * <p><b>This is a 32-bit hash and two states of one row can collide</b>, the
     * weakness B-019 found honestly on {@code ProjectSettingsController} and B-021
     * recorded on {@code PriorityController}. Here the cancelling pair is
     * {@code isOpen} and {@code isTerminal} — two booleans at the same multiplier
     * in a record's {@code hashCode} moving in opposite directions — and they are
     * exactly the two {@code guardTerminalAndOpen} already prevents moving
     * together into the dangerous combination. Recorded rather than fixed here: a
     * stronger tag across all five controllers is a change worth making together,
     * not one screen at a time.
     */
    private static String etagOf(StatusDtos.StatusView view) {
        return Integer.toHexString(view.etagBasis().hashCode());
    }

    /**
     * Taken over the whole matrix, always — including when the read was filtered
     * by {@code roleCode}.
     *
     * <p>A tag over one role's column would let two Admins editing different
     * columns each save over the other while both preconditions passed, which is
     * the failure the header exists to prevent. The {@code PUT} replaces
     * everything, so the precondition has to cover everything.
     */
    private String etagOfMatrix() {
        return Integer.toHexString(matrix.list(null).hashCode());
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
