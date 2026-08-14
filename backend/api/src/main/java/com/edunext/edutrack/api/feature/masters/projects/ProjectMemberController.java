package com.edunext.edutrack.api.feature.masters.projects;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-017 · S-10's Team tab, per {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>The read</b> ({@code listProjectMembers}) — <b>all six roles</b>. The
 *       roster is who a ticket on this project can be assigned to; a Developer
 *       who cannot see it cannot choose a handoff target, and every name on it
 *       is already visible through {@code listUsers}.</li>
 *   <li><b>The three writes</b> — {@code project.manage}, which B-001 grants to
 *       <b>Admin and PM</b>. Blueprint §2 gives PM "Create/edit projects;
 *       <i>map resources to a project</i>", and that second clause is this
 *       screen.</li>
 * </ul>
 *
 * <p>Asserting the capability rather than {@code hasAnyRole('ADMIN','PM')} is
 * A-033's rule and B-015's account of it applies unchanged: a hardcoded role
 * check keeps refusing the seventh role the Role Master has just granted the
 * capability to.
 *
 * <h2>Why this is a second controller and not four more methods on
 * {@link ProjectController}</h2>
 *
 * <p>It is a separate resource with its own service, its own refusals and its
 * own exception handler — and both handlers are {@code assignableTypes}-scoped,
 * so merging the controllers would merge the handlers and put the Team tab's
 * 409s in the path of the project form's. {@code MasterRoutesTest} asserts both
 * mount points, which is the check B-023 shipped nine operations without.
 *
 * <p>The {@code /api/v1} prefix is spelled out for {@link ProjectController}'s
 * reason: nothing declares it globally, and B-023's calendar answered on a path
 * no client called for exactly that gap.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
@Tag(name = "projects")
class ProjectMemberController {

    private final ProjectMemberService service;

    ProjectMemberController(ProjectMemberService service) {
        this.service = service;
    }

    /**
     * The roster.
     *
     * <p>No {@code meta}, and no cursor. CONVENTIONS.md §6's exemption is
     * registered with its reason in {@code check-conventions.py}: a project team
     * is tens of people, and the tab totals their allocations, so it reads the
     * whole set on every render whatever the page size would have been.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listProjectMembers", summary = "The project team (S-10)")
    ProjectMemberDtos.TeamListResponse list(@PathVariable long projectId) {
        return new ProjectMemberDtos.TeamListResponse(service.roster(projectId));
    }

    /**
     * Add a member, or bring back one who was removed.
     *
     * <p>201 in both cases. The alternative — 200 for a reactivation — would
     * make a client branch on whether the organisation happened to have removed
     * this person from this project at some point in the past, which is not a
     * distinction the caller asked about or can act on.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "addProjectMember", summary = "Add a team member (S-10)")
    ResponseEntity<ProjectMemberDtos.TeamMemberResponse> add(
            @PathVariable long projectId,
            @Valid @RequestBody ProjectMemberDtos.TeamMemberWrite write) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ProjectMemberDtos.TeamMemberResponse(service.add(projectId, write)));
    }

    /**
     * Change a member's project role or allocation.
     *
     * <p><b>No {@code If-Match}</b>, which is an exemption registered in
     * {@code check-conventions.py} rather than an omission. The tag would have
     * to come from {@code listProjectMembers}, a collection that carries no
     * {@code ETag} of its own — so honouring the precondition would mean minting
     * a per-member tag on a read nothing else preconditions, to guard a race
     * between two people setting one percentage, whose loser typed a number a
     * moment later and meant it.
     */
    @PatchMapping(path = "/{userId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "updateProjectMember", summary = "Change a member's role or allocation (S-10)")
    ProjectMemberDtos.TeamMemberResponse update(
            @PathVariable long projectId,
            @PathVariable long userId,
            @Valid @RequestBody ProjectMemberDtos.TeamMemberPatch patch) {

        return new ProjectMemberDtos.TeamMemberResponse(
                service.update(projectId, userId, patch).orElseThrow(ProjectMemberController::notFound));
    }

    /**
     * Remove a member. 204, including when they were not on the team.
     *
     * <p>It is a setter, so it has to converge: a client retrying after a
     * dropped response, or the second half of a double-click, must not be told
     * an error about a thing that did happen. B-014 made the same call for
     * {@code UNCHANGED} on the resource status route, and for the same reason.
     *
     * <p>404 is still reachable, and only for the <i>project</i> — the one id in
     * the path whose absence means the caller is asking about something that is
     * not there.
     */
    @DeleteMapping(path = "/{userId}")
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "removeProjectMember", summary = "Remove a team member (S-10)")
    ResponseEntity<Void> remove(@PathVariable long projectId, @PathVariable long userId) {
        service.remove(projectId, userId);
        return ResponseEntity.noContent().build();
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
