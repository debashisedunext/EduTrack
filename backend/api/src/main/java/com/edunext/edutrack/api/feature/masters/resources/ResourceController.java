package com.edunext.edutrack.api.feature.masters.resources;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Locale;
import java.util.Set;

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
 * <p><b>Reporting-manager cycle detection (B-012) and the bulk reassignment
 * wizard (B-014) are still absent.</b> The former means {@code PATCH} will
 * currently accept A→B→C→A; only self-reference is refused. The latter means
 * deactivating somebody who holds open tickets stops with a count and a URL
 * rather than offering to fix it.
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
 *       {@code POST /users/bulk-status}</b> — Admin only, like every master
 *       write. The other five roles are refused before any id in the body is
 *       read.</li>
 * </ul>
 *
 * <p>Until A-034's {@code ScopeResolver} and A-033's {@code @PreAuthorize} land
 * this runs under {@code dev-noauth}. <b>No filtering is hand-rolled here as a
 * stand-in</b>, per CLAUDE.md — a local workaround becomes the permanent
 * security hole.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users")
class ResourceController {

    /**
     * The contract's {@code RoleCode} enum, as a set rather than a Java enum.
     *
     * <p>Role codes are master data an Admin extends through S-09, so the
     * authoritative list is the {@code roles} table, not a compiled type. This
     * set exists only to turn a typo'd query parameter into a 400 naming the
     * six codes, instead of an empty grid that looks like an empty organisation.
     * B-015 owns the role master; when it lands, this reads from it.
     */
    private static final Set<String> ROLE_CODES =
            Set.of("ADMIN", "PM", "DEVELOPER", "QA", "DEPLOYMENT", "SUPPORT");

    private static final String XLSX = "xlsx";
    private static final String CSV = "csv";

    private final ResourceService resources;
    private final ResourceExportWriter exporter;
    private final ResourceWriteService writes;

    ResourceController(ResourceService resources, ResourceExportWriter exporter,
                       ResourceWriteService writes) {
        this.resources = resources;
        this.exporter = exporter;
        this.writes = writes;
    }

    /** A page of the grid. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
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
    @Operation(operationId = "setUserStatusBulk", summary = "Activate or deactivate a selection (S-07)")
    ResourceDtos.BulkStatusResponse setStatusBulk(@Valid @RequestBody ResourceDtos.BulkStatusRequest request) {
        return new ResourceDtos.BulkStatusResponse(resources.setStatus(request));
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

    private static ResourceFilter filterFrom(String q, String role, Long projectId,
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
     * @throws ResponseStatusException 400 when the code is not one of the six
     */
    private static String normaliseRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String code = role.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_CODES.contains(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be one of " + ROLE_CODES.stream().sorted().toList());
        }
        return code;
    }
}
