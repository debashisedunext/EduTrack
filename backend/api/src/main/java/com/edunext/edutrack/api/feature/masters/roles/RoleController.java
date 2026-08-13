package com.edunext.edutrack.api.feature.masters.roles;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-015 · S-09 — the Role &amp; Permission Master, per
 * {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> ({@code listPermissions}, {@code listRoles},
 *       {@code getRole}) — <b>all six roles</b>. Role names are rendered on
 *       every ticket card and every resource row, and the capability catalogue
 *       is blueprint documentation; {@code GET /me} already returns the
 *       caller's own grants.</li>
 *   <li><b>Writes</b> ({@code createRole}, {@code updateRole},
 *       {@code deleteRole}, {@code replaceRolePermissions}) — <b>Admin
 *       only</b>. PM, Support, Developer, QA and Deployment get 403.</li>
 * </ul>
 *
 * <p><b>The writes assert {@code resource.manage}, not {@code master.write}.</b>
 * The two are easy to confuse here, because this is a master screen — but B-001
 * seeds {@code resource.manage} as "Manage resources, <em>roles</em>, reporting
 * manager", and {@code master.write} as task types, SLA, workflow and holidays.
 * Blueprint §2 puts roles with resources, so this follows the seed rather than
 * the URL. Only Admin holds either today, so the two are indistinguishable now
 * and would not be the moment somebody is given master data without being given
 * the roles that govern who can touch it.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule, and this controller is the reason it exists: <b>a hard-coded role check
 * would keep refusing a seventh role that this very screen had just granted the
 * capability to.</b>
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: master data is not row-scoped.
 * Every role is already public through {@code listRoles}, so a 403 here
 * conceals nothing a 404 would have hidden — the §7 carve-out for failures that
 * do not depend on a row. Recorded in {@code check-conventions.py}'s
 * {@code ROWLESS_403} with that reason, beside {@code /audit-logs}.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally,
 * and B-023 shipped nine calendar operations at a path no client called;
 * {@code MasterRoutesTest} now pins it for every controller in this feature and
 * this one is in that list.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class RoleController {

    private final RoleService service;

    RoleController(RoleService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // The permission catalogue
    // ------------------------------------------------------------------

    /**
     * Reference data, so read-only: there is no create, edit or delete. A
     * capability exists because code checks for it, so a row an admin could add
     * would grant nothing and a row they could delete would silently un-guard a
     * route.
     */
    @GetMapping(path = "/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listPermissions", summary = "The permission catalogue (S-09)")
    RoleDtos.PermissionListResponse permissions() {
        return new RoleDtos.PermissionListResponse(service.permissions());
    }

    // ------------------------------------------------------------------
    // Roles
    // ------------------------------------------------------------------

    @GetMapping(path = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listRoles", summary = "Roles (S-09)")
    RoleDtos.RoleListResponse roles(@RequestParam(required = false) Boolean isActive) {
        return new RoleDtos.RoleListResponse(service.list(isActive));
    }

    @GetMapping(path = "/roles/{roleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getRole", summary = "One role, with its grants (S-09)")
    ResponseEntity<RoleDtos.RoleDetailResponse> role(@PathVariable int roleId) {
        RoleDtos.RoleDetail role = service.find(roleId).orElseThrow(RoleController::notFound);
        return ok(role);
    }

    @PostMapping(path = "/roles",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "createRole", summary = "Create a role")
    ResponseEntity<RoleDtos.RoleDetailResponse> create(@Valid @RequestBody RoleDtos.RoleWrite write) {
        RoleDtos.RoleDetail created = service.create(write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new RoleDtos.RoleDetailResponse(created));
    }

    @PatchMapping(path = "/roles/{roleId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "updateRole", summary = "Rename a role or change its status")
    ResponseEntity<RoleDtos.RoleDetailResponse> update(
            @PathVariable int roleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RoleDtos.RolePatch patch) {

        requirePrecondition(roleId, ifMatch);
        return ok(service.update(roleId, patch).orElseThrow(RoleController::notFound));
    }

    @DeleteMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "deleteRole", summary = "Delete a role")
    ResponseEntity<Void> delete(@PathVariable int roleId) {
        if (!service.delete(roleId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // The matrix
    // ------------------------------------------------------------------

    /**
     * Replace-all. The screen submits every checkbox's state at once, so the
     * body is the complete set the role should hold afterwards rather than a
     * delta — a partial save of a permission matrix is an ambiguous thing to
     * have to reason about later.
     */
    @PutMapping(path = "/roles/{roleId}/permissions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('resource.manage')")
    @Operation(operationId = "replaceRolePermissions",
            summary = "Save the permission matrix for a role (S-09)")
    ResponseEntity<RoleDtos.RoleDetailResponse> replacePermissions(
            @PathVariable int roleId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RoleDtos.RolePermissionsWrite write) {

        requirePrecondition(roleId, ifMatch);
        return ok(service.replacePermissions(roleId, write.permissionCodes())
                .orElseThrow(RoleController::notFound));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required on both writes, not optional.
     *
     * <p>A write without one is refused with 428 rather than allowed through:
     * treating a missing precondition as "no conflict" would mean the guard
     * protects only the clients that already opted in, which is the set that
     * needed it least. Same reasoning, and the same status, as B-023's working
     * week.
     *
     * <p>The 404 comes first. Answering 428 for a role that does not exist would
     * make the caller go and fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(int roleId, String ifMatch) {
        RoleDtos.RoleDetail current = service.find(roleId).orElseThrow(RoleController::notFound);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the role first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This role changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<RoleDtos.RoleDetailResponse> ok(RoleDtos.RoleDetail role) {
        return ResponseEntity.ok().eTag(etagOf(role))
                .body(new RoleDtos.RoleDetailResponse(role));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>Because {@code RoleDetail} carries {@code permissionCodes}, the tag
     * covers the grants as well as the fields — so a concurrent matrix save and
     * a concurrent rename conflict with each other, which is correct: they are
     * two people editing the same screen. A timestamp-based tag would also move
     * when a save rewrote identical values, failing an edit that conflicts with
     * nothing. {@code userCount} is in the hash too and is the one component
     * somebody else's action can change — a resource being reassigned while the
     * matrix is open costs a reload, which is the cheaper error of the two.
     */
    private static String etagOf(RoleDtos.RoleDetail role) {
        return Integer.toHexString(role.hashCode());
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
