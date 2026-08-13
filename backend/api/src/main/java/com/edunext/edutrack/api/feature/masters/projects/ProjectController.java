package com.edunext.edutrack.api.feature.masters.projects;

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

import java.util.List;

/**
 * B-016 · S-10 — the Project Master, per {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> ({@code listProjects}, {@code getProject}) — <b>all six
 *       roles</b>. This is not a courtesy: {@code listProjects} is the picker
 *       behind the project switcher, the ticket list's filter, the create-ticket
 *       form and both resource screens. A Developer who cannot read it cannot
 *       raise a ticket.</li>
 *   <li><b>Writes</b> ({@code createProject}, {@code updateProject}) —
 *       {@code project.manage}, which B-001 grants to <b>Admin and PM</b>.
 *       Developer, QA, Deployment and Support get 403.</li>
 * </ul>
 *
 * <p>PM holds the write capability and that is B-001's grant, not a decision
 * taken here — blueprint §2 gives PM "Create/edit projects; map resources to a
 * project". Asserting the capability rather than {@code hasAnyRole('ADMIN','PM')}
 * is A-033's rule, and B-015's account of it applies unchanged: a hardcoded role
 * check keeps refusing the seventh role that the Role Master has just granted the
 * capability to.
 *
 * <p><b>The list is not row-scoped, and does not need to be.</b> {@code
 * ScopeResolver} scopes <i>tickets</i>; the project list is the vocabulary a
 * ticket is described in, and a PM who could not see a project name could not
 * read the tickets they do own. Every project is already visible through the
 * pickers on four other screens.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally, and
 * B-023 shipped nine calendar operations at a path no client called;
 * {@code MasterRoutesTest} pins it for every controller in this feature and this
 * one is in that list. <b>The path is {@code /api/v1/projects}, not
 * {@code /api/v1/masters/projects}</b> — the contract has served projects at the
 * top level since D-001 and five screens already call it there. Moving it to sit
 * tidily beside the other master screens would break all five to make a URL
 * prettier.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "projects")
class ProjectController {

    /** CONVENTIONS.md §6. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ProjectService service;

    ProjectController(ProjectService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The S-10 grid, and the picker five other screens fill from.
     *
     * <p>One row over the requested limit is fetched and dropped, which is how
     * {@code hasMore} is answered without a second {@code COUNT(*)} over a table
     * that is being written to — the count and the page would disagree anyway.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listProjects", summary = "List projects (S-10)")
    ProjectDtos.ProjectListResponse list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long managerId,
            @RequestParam(required = false) String q) {

        int pageSize = pageSize(limit);
        List<ProjectDtos.Project> rows = service.page(
                new ProjectMasterRepository.ProjectFilter(isActive, normaliseStatus(status), managerId, q),
                ProjectCursor.decode(cursor),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<ProjectDtos.Project> page = hasMore ? rows.subList(0, pageSize) : rows;

        String nextCursor = null;
        if (hasMore) {
            ProjectDtos.Project last = page.get(page.size() - 1);
            nextCursor = new ProjectCursor(last.name(), last.id()).encode();
        }
        return new ProjectDtos.ProjectListResponse(page, new ProjectDtos.Meta(nextCursor, hasMore));
    }

    /**
     * The edit form's read, and the only source of the {@code ETag} the
     * {@code PATCH} requires.
     */
    @GetMapping(path = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getProject", summary = "One project (S-10)")
    ResponseEntity<ProjectDtos.ProjectDetailResponse> get(@PathVariable long projectId) {
        return ok(service.find(projectId).orElseThrow(ProjectController::notFound));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "createProject", summary = "Create a project (S-10)")
    ResponseEntity<ProjectDtos.ProjectDetailResponse> create(
            @Valid @RequestBody ProjectDtos.ProjectWrite write) {

        ProjectDtos.ProjectDetail created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new ProjectDtos.ProjectDetailResponse(created));
    }

    @PatchMapping(path = "/{projectId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "updateProject", summary = "Update a project (S-10)")
    ResponseEntity<ProjectDtos.ProjectDetailResponse> update(
            @PathVariable long projectId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ProjectDtos.ProjectPatch patch) {

        requirePrecondition(projectId, ifMatch);
        return ok(service.update(projectId, patch).orElseThrow(ProjectController::notFound));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is refused with 428 rather than allowed through:
     * treating a missing precondition as "no conflict" means the guard protects
     * only the clients that already opted in, which is the set that needed it
     * least. Same reasoning and the same status as B-011's resource form and
     * B-023's working week.
     *
     * <p>The 404 comes first. Answering 428 for a project that does not exist
     * would send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(long projectId, String ifMatch) {
        ProjectDtos.ProjectDetail current = service.find(projectId)
                .orElseThrow(ProjectController::notFound);

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the project first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This project changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<ProjectDtos.ProjectDetailResponse> ok(ProjectDtos.ProjectDetail project) {
        return ResponseEntity.ok().eTag(etagOf(project))
                .body(new ProjectDtos.ProjectDetailResponse(project));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp-based tag moves when a save rewrites identical values,
     * failing an edit that conflicts with nothing. Content-based, two people who
     * saved the same change do not fight.
     *
     * <p><b>{@code ticketsIssued} is part of the record and therefore part of the
     * tag</b>, which means a ticket being created while somebody has the form
     * open costs them a reload. That is the right trade rather than a defect:
     * that number crossing zero is precisely the event that makes
     * {@code projectCode} immutable, and a save carrying a new code that was
     * legal when the form loaded must not land afterwards.
     */
    private static String etagOf(ProjectDtos.ProjectDetail project) {
        return Integer.toHexString(project.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /**
     * An unknown {@code ?status=} is left to the repository as a value that
     * matches nothing, rather than refused.
     *
     * <p>A filter is a question, and "no projects are ON_HOLDD" is a true answer
     * to a mistyped one. 400 belongs on a write, where the caller is changing
     * state and needs to know it did not happen.
     */
    private static String normaliseStatus(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static int pageSize(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
