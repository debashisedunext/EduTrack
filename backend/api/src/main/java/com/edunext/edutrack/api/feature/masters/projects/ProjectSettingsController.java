package com.edunext.edutrack.api.feature.masters.projects;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-019 · S-10's Settings tab, per {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions — and why they differ from the SLA tab next door</h2>
 *
 * <ul>
 *   <li><b>The read</b> — <b>all six roles</b>. Every role can raise a ticket,
 *       and the create form cannot mark a field mandatory or filter its task
 *       type picker without this.</li>
 *   <li><b>The write</b> — {@code project.manage}, which B-001 grants to
 *       <b>Admin and PM</b>.</li>
 * </ul>
 *
 * <p><b>{@link SlaPolicyController} beside it is {@code master.write} and
 * reaches Admin alone, and this one is deliberately wider.</b> Blueprint §2 has
 * two separate rows and this screen sits on the first: row 2, "Create/edit
 * projects, map resources to a project", is ✅ for PM and is the General, Team
 * and Settings tabs; row 5, "Master data (task types, <i>SLA</i>, workflow,
 * holidays)", is Admin's alone and is the SLA tab. Choosing which task types a
 * project accepts and which fields its form requires is configuring one project;
 * setting the response target a client is contractually held to is master data.
 *
 * <p>The decisive half is that <b>narrowing it would take away a capability PMs
 * hold today</b>: {@code auto_assign_rule} is one of the three settings here and
 * has been PM-writable through {@code PATCH /projects/{projectId}} since B-016.
 * A tab that could only be opened read-only by the role that can already change
 * one of its three fields elsewhere would be a regression wearing a consistency
 * argument.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule: a hardcoded role check keeps refusing the seventh role the Role Master
 * has just granted the capability to.
 *
 * <h2>One writer for {@code auto_assign_rule}</h2>
 *
 * <p>The column is still <i>accepted</i> by {@code POST /projects} and
 * {@code PATCH /projects/{projectId}} — removing it would be a breaking contract
 * change for no gain, and a project may reasonably be created with a rule. But
 * S-10 puts the field on this tab, the General form no longer renders it, and
 * this is the operation that edits it. Two screens writing one column is the
 * shape of the {@code project_members} hazard B-011 and B-017 had to pin apart
 * with two named regression tests; not creating it is cheaper than documenting
 * it.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings")
@Tag(name = "projects")
class ProjectSettingsController {

    private final ProjectSettingsService service;

    ProjectSettingsController(ProjectSettingsService service) {
        this.service = service;
    }

    /**
     * This project's settings, resolved.
     *
     * <p>No {@code meta}, and no cursor — {@code data} is an object rather than
     * a collection, so CONVENTIONS.md §6 does not apply and needs no exemption.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getProjectSettings", summary = "Project settings (S-10)")
    ResponseEntity<ProjectSettingsDtos.ProjectSettingsResponse> settings(@PathVariable long projectId) {
        return ok(service.settings(projectId));
    }

    /**
     * Replace all three settings.
     *
     * <p>A {@code PUT} rather than a {@code PATCH} because the tab has one Save
     * button and holds the whole document: patching one field would need an
     * {@code ETag} per field to be safe against a second tab, which is
     * machinery for a screen that never sends one field.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('project.manage')")
    @Operation(operationId = "replaceProjectSettings", summary = "Replace project settings (S-10)")
    ResponseEntity<ProjectSettingsDtos.ProjectSettingsResponse> replace(
            @PathVariable long projectId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ProjectSettingsDtos.ProjectSettingsWrite write) {

        requirePrecondition(projectId, ifMatch);
        return ok(service.replace(projectId, write));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>428 rather than letting it through, for {@link ProjectController}'s
     * reason: treating a missing precondition as "no conflict" means the guard
     * protects only the clients that already opted in, which is the set that
     * needed it least.
     *
     * <p>The 404 comes first. Answering 428 for a project that does not exist
     * would send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(long projectId, String ifMatch) {
        String current = etagOf(service.settings(projectId));

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the settings first and send back its ETag.");
        }
        if (!matches(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "These settings changed since you read them. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<ProjectSettingsDtos.ProjectSettingsResponse> ok(
            ProjectSettingsDtos.ProjectSettings settings) {

        return ResponseEntity.ok().eTag(etagOf(settings))
                .body(new ProjectSettingsDtos.ProjectSettingsResponse(settings));
    }

    /**
     * Content-derived, not from {@code projects.updated_at}.
     *
     * <p>{@link ProjectController#etagOf}'s reason — a timestamp tag moves when
     * a save rewrites identical values and fails an edit that conflicts with
     * nothing. It is taken over the <b>resolved</b> document, so retiring a task
     * type in the Task Type Master moves this project's tag: correct, because
     * the administrator was shown a list that no longer says what it said.
     *
     * <p><b>It is a 32-bit hash, so two states of one resource can share a
     * tag</b> and a stale write against the second is then let through. That is
     * not only theoretical here: settings differing <i>only</i> in "restricted,
     * with one type excluded" versus "unrestricted" collide exactly, because
     * {@code restrictsTaskTypes} and the {@code isAllowed} it implies move in
     * opposite directions at the same multiplier in a record's
     * {@code hashCode}. {@code ProjectSettingsControllerTest} documents the
     * mechanism.
     *
     * <p>Kept as is rather than replaced with a digest: {@link SlaPolicyController}
     * and {@link ProjectController} both tag this way, and diverging here would
     * leave one feature with two schemes for one guarantee. Recorded so the
     * property is known rather than assumed away — a stronger tag across all
     * three is a change worth making together, not one screen at a time.
     */
    private static String etagOf(ProjectSettingsDtos.ProjectSettings settings) {
        return Integer.toHexString(settings.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
