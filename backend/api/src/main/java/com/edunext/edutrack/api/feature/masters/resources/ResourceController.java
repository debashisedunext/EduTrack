package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * B-010 · S-07, the Resource Master list.
 *
 * <p>Served at {@code /api/v1/users} because that is where the contract puts it
 * — the resource directory is not only a master screen, it is what the ticket
 * assignee picker and the reportee tree read. The package says which stream
 * owns the code; the path says what the resource is, and they are allowed to
 * differ.
 *
 * <p>B-010 built the grid, its filters, its bulk status action and its export.
 * B-011 added the S-08 form: {@code GET}, {@code POST} and {@code PATCH} of one
 * resource.
 *
 * <p>B-012 closed the reporting-manager hole: {@code POST} and {@code PATCH}
 * now refuse a cycle at any depth with a 409, not only self-reference.
 *
 * <p>B-014 added {@code PATCH /users/{userId}/status}, which the contract had
 * declared and nothing had served. <b>The wizard those refusals point at is
 * still absent</b> — it is S-24, Stream C's C-063, and lives behind
 * {@code POST /tickets/bulk-reassign}. What this controller owns is the refusal
 * and the route back through it: reassign, then set the flag again and get a
 * 204.
 *
 * <h2>Permissions</h2>
 *
 * A-036's parameterised matrix does not exist yet, so the rule is stated here
 * and enforced when it lands:
 *
 * <ul>
 *   <li><b>{@code GET /users}, {@code GET /users/{id}},
 *       {@code GET /users/export}</b> — all six roles. Admin, PM, Support,
 *       Developer, QA and Deployment all read the directory: the assignee
 *       picker, {@code @mention} resolution and the reportee tree are the same
 *       data, and hiding it would break three features to protect a list of
 *       colleagues' names. No row scoping — a directory scoped to your own row
 *       is not a directory. The detail read carries no credential: the
 *       projection names its columns and {@code password_hash},
 *       {@code failed_attempts}, {@code locked_until} and
 *       {@code password_changed_at} are not among them.</li>
 *   <li><b>{@code POST /users}, {@code PATCH /users/{id}},
 *       {@code PATCH /users/{id}/status}, {@code POST /users/bulk-status}</b> —
 *       Admin only, like every master write. The other five roles are refused
 *       before any id in the body is read.</li>
 * </ul>
 *
 * <p><b>A-033 has landed and the two bullets above are now the annotations
 * below, unchanged in substance.</b> The writes assert {@code resource.manage}
 * rather than {@code hasRole('ADMIN')}: the §2 matrix's cell is a capability, and
 * checking the capability keeps working when S-09 grants it to a seventh role,
 * where a hard-coded role check would quietly keep refusing. Only Admin holds it
 * today, so the effect is identical and the failure mode later is not.
 *
 * <p>Row scope is still A-034's, and the reads are deliberately unscoped —
 * a directory scoped to your own row is not a directory. <b>No filtering is
 * hand-rolled here as a stand-in</b>, per CLAUDE.md.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
class ResourceController {

    private static final String XLSX = "xlsx";
    private static final String CSV = "csv";

    private final ResourceService resources;
    private final ResourceExportWriter exporter;
    private final ResourceWriteService writes;

    /**
     * The authoritative list of role codes, replacing the hardcoded set of six
     * this class carried until B-015.
     *
     * <p>The set was only ever there to turn a typo'd {@code ?role=} into a 400
     * naming the valid codes, instead of an empty grid that looks like an empty
     * organisation — but role codes are master data an Admin extends through
     * S-09, so a compiled copy of them starts drifting the moment that screen
     * exists. It now reads the table. The Role Master is in this feature, but
     * the <em>repository</em> is Stream A's identity domain, which is where
     * every other consumer of {@code roles} reads from too.
     */
    private final RoleRepository roles;

    ResourceController(ResourceService resources, ResourceExportWriter exporter,
                       ResourceWriteService writes, RoleRepository roles) {
        this.resources = resources;
        this.exporter = exporter;
        this.writes = writes;
        this.roles = roles;
    }

    /** A page of the grid. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    // All six roles — the assignee picker, @mention resolution and the reportee
    // tree read this same list. See the class javadoc.
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listUsers", summary = "List resources (S-07)")
    ResourceDtos.ResourceListResponse list(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String role,
                                           @RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) Long managerId,
                                           @RequestParam(required = false) Boolean isActive) {

        return resources.list(filterFrom(q, role, projectId, managerId, isActive), cursor, limit);
    }

    /**
     * The same query as a file.
     *
     * <p><b>A separate path rather than {@code ?export=} on the list.</b> One
     * operation declaring both JSON and a binary body generates a client whose
     * return type is {@code Blob | UserListResponse}, and every existing caller
     * of {@code useListUsers} — the ticket list's assignee filter, the create
     * form's picker — stops compiling until it narrows a union it has no
     * interest in. B-010 shipped it that way for an hour and reverted; the
     * contract note on {@code /users/export} records it, because
     * {@code /reports/{key}} and {@code /audit-logs} carry the same latent
     * break and have no consumer yet to reveal it.
     *
     * <p>The risk a shared path was protecting against — an export that applies
     * a filter differently from the screen above it — is handled by
     * {@link #filterFrom} being the only place either builds one, and by
     * {@code ResourceControllerTest.exportHonoursFilters} holding it there.
     *
     * <p>{@code cursor} and {@code limit} are not parameters. The export is
     * every matching row, not the page you happen to be on.
     */
    @GetMapping("/export")
    // The same rows as the list, in a file. A stricter rule here than on the
    // grid would be theatre: the caller can already read every row on screen.
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "exportUsers", summary = "Download the filtered resource list (S-07)")
    void export(@RequestParam(name = "format") String format,
                @RequestParam(required = false) String q,
                @RequestParam(required = false) String role,
                @RequestParam(required = false) Long projectId,
                @RequestParam(required = false) Long managerId,
                @RequestParam(required = false) Boolean isActive,
                HttpServletResponse response) throws IOException {

        writeExport(filterFrom(q, role, projectId, managerId, isActive),
                format == null ? "" : format.trim().toLowerCase(Locale.ROOT),
                response);
    }

    @PostMapping(path = "/bulk-status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // Refused before any id in the body is read — which is the reason a bulk
    // write needs the check at least as much as a singular one does.
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "setUserStatusBulk", summary = "Activate or deactivate a selection (S-07)")
    ResourceDtos.BulkStatusResponse setStatusBulk(@Valid @RequestBody ResourceDtos.BulkStatusRequest request) {
        return new ResourceDtos.BulkStatusResponse(resources.setStatus(request));
    }

    /**
     * B-014 · activate or deactivate one resource.
     *
     * <p><b>The contract has declared this operation since the first draft, the
     * MSW mock has answered it since B-010, three javadocs in this package
     * describe what it refuses — and until now no server served it.</b> A
     * {@code PATCH} to this path met the {@code /{userId}} mapping's sibling and
     * came back 405. Nothing caught it, because the operation had no caller: the
     * grid uses the bulk route and the form uses {@code PATCH /users/{userId}},
     * and the screen that needed the singular one is the deactivation flow this
     * task builds. {@code ResourceControllerTest.everyContractedRouteIsMounted}
     * is now the thing that would have.
     *
     * <p>No {@code If-Match}, matching the contract and CONVENTIONS.md §5's
     * exemption: this sets a flag to a stated value rather than editing a
     * document, so a replay converges and last-write-wins is the correct
     * semantic. The optimistic-concurrency guard on {@code PATCH /users/{userId}}
     * is there because that route sends the whole form back and would otherwise
     * silently discard a colleague's edit; there is nothing here to discard.
     *
     * <p>204 rather than the updated resource. The caller either already has the
     * row or is about to re-read the list, and returning a body would make this
     * the third shape of a resource in one controller.
     */
    @PatchMapping(path = "/{userId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "setUserStatus", summary = "Activate or deactivate")
    ResponseEntity<Void> setStatus(@PathVariable long userId,
                                   @Valid @RequestBody ResourceDtos.StatusRequest request) {

        resources.setStatus(userId, request.isActive());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // B-011 · S-08, the form
    // ------------------------------------------------------------------

    /**
     * One resource in full, with the {@code ETag} the {@code PATCH} requires.
     *
     * <p><b>This route did not exist before B-011, and its absence was a
     * defect.</b> CONVENTIONS.md §5 pairs every {@code If-Match} write with a
     * detail read that emits the tag; {@code PATCH /users/{userId}} declared the
     * precondition and there was nowhere to obtain it, so the operation could
     * not be called correctly by anybody. {@code /projects/{id}} and
     * {@code /clients/{id}} both have theirs.
     */
    @GetMapping(path = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    // All six roles, per the class javadoc: the projection names its columns and
    // password_hash, failed_attempts, locked_until and password_changed_at are
    // not among them, so there is no credential here to withhold.
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getUser", summary = "One resource, in full (S-08)")
    ResponseEntity<ResourceDtos.ResourceDetailResponse> get(@PathVariable long userId) {
        ResourceDtos.ResourceDetail resource = writes.detail(userId);
        return ResponseEntity.ok()
                .eTag(resource.etag())
                .body(new ResourceDtos.ResourceDetailResponse(resource));
    }

    /**
     * Creates a resource, answering {@code 201} with the one-time password.
     *
     * <p>No {@code If-Match} — there is nothing yet to have changed underneath
     * you. {@code Idempotency-Key} is accepted per CONVENTIONS.md §4 and is
     * <b>not yet honoured</b>: the 24-hour replay store is A-035 and does not
     * exist. Until it does, a retried create is refused by
     * {@code uq_users_username} with a 409 rather than silently creating a
     * second account — which is the failure mode that matters, and is why this
     * ships rather than waiting.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // Creating an account is the most consequential write in this controller —
    // it answers 201 with a one-time password.
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "createUser", summary = "Create a resource (S-08)")
    ResponseEntity<ResourceDtos.ResourceCreatedResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResourceDtos.ResourceWriteRequest request) {

        ResourceWriteService.Created created = writes.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(created.resource().etag())
                .body(new ResourceDtos.ResourceCreatedResponse(
                        created.resource(),
                        new ResourceDtos.CreatedMeta(created.temporaryPassword())));
    }

    /**
     * Applies a partial edit.
     *
     * <p><b>{@code If-Match} is required, not optional</b>, and a write without
     * one is refused with 428 rather than allowed through. {@code CalendarController}
     * states the reason: treating a missing precondition as "no conflict" means
     * the guard protects only the clients that already opted in, which is the
     * set that needed it least.
     *
     * <p>The row is read once and both the precondition and the write reason
     * about that same snapshot, so the deactivation guard cannot be checked
     * against a different version of the resource than the tag was.
     */
    @PatchMapping(path = "/{userId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // Covers the reporting-manager edit, which is a §2 "Create/edit resources,
    // roles, reporting manager" cell and Admin-only there too.
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "updateUser", summary = "Update a resource (S-08)")
    ResponseEntity<ResourceDtos.ResourceDetailResponse> update(
            @PathVariable long userId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ResourceDtos.ResourceWriteRequest request) {

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the resource first and send back its ETag.");
        }

        ResourceDtos.ResourceDetail current = writes.detail(userId);
        if (!etagMatches(ifMatch, current.etag())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This resource changed since you read it. Reload and reapply your edit.");
        }

        ResourceDtos.ResourceDetail saved = writes.update(current, request);
        return ResponseEntity.ok()
                .eTag(saved.etag())
                .body(new ResourceDtos.ResourceDetailResponse(saved));
    }

    /**
     * {@code *} matches anything, per RFC 9110.
     *
     * <p>Same comparison {@code CalendarController} makes, and the weak-validator
     * prefix is stripped for the same reason: some proxies add {@code W/} on the
     * way through, and refusing the edit because of it would be a 412 the caller
     * cannot act on.
     */
    private static boolean etagMatches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    private ResourceFilter filterFrom(String q, String role, Long projectId,
                                      Long managerId, Boolean isActive) {
        return new ResourceFilter(q, normaliseRole(role), projectId, managerId, isActive);
    }

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    /**
     * Streams the file straight to the response.
     *
     * <p>Written synchronously rather than through a {@code StreamingResponseBody}
     * so that a failure mid-write still happens while the response is
     * uncommitted often enough to produce an error the caller can read, rather
     * than a truncated file with a 200 already on it.
     *
     * <p>{@code application/octet-stream} is what the contract declares, and the
     * extension in {@code Content-Disposition} is what tells the browser and the
     * operating system what the file actually is. Announcing the precise
     * spreadsheet MIME type instead would be more informative and would put this
     * endpoint out of step with {@code /reports} and {@code /audit-logs}, which
     * make the same trade.
     *
     * <p>The header is set before the first byte, so a browser that follows this
     * link gets a download rather than a tab full of binary.
     */
    private void writeExport(ResourceFilter filter, String format, HttpServletResponse response)
            throws IOException {

        if (!XLSX.equals(format) && !CSV.equals(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "format must be 'xlsx' or 'csv'");
        }

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename(format) + "\"");

        if (XLSX.equals(format)) {
            exporter.writeXlsx(filter, response.getOutputStream());
        } else {
            exporter.writeCsv(filter, response.getOutputStream());
        }
        response.flushBuffer();
    }

    /** Dated so that two exports a week apart do not overwrite each other in Downloads. */
    private static String filename(String format) {
        return "resources-" + LocalDate.now(ZoneOffset.UTC) + "." + format;
    }

    /**
     * @return the role code upper-cased, or null when not filtering
     * @throws ResponseStatusException 400 when no such role exists
     */
    private String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String code = role.trim().toUpperCase(Locale.ROOT);
        if (!roles.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be one of " + knownRoleCodes());
        }
        return code;
    }

    /** Sorted, so a 400 reads the same twice running. */
    private List<String> knownRoleCodes() {
        return roles.findAll().stream().map(Role::getCode).sorted().toList();
    }
}
