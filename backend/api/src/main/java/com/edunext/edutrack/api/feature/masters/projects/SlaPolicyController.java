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

import java.util.List;

/**
 * B-018 · S-10's SLA tab, per {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions — and why they differ from the Team tab next door</h2>
 *
 * <ul>
 *   <li><b>The read</b> — <b>all six roles</b>. This grid is what gives every
 *       ticket its planned close date; a Developer who cannot see it cannot find
 *       out why their ticket is due Thursday, and the figures are already
 *       visible one at a time through C-012's preview.</li>
 *   <li><b>The write</b> — {@code master.write}, which B-001 grants to
 *       <b>Admin alone</b>. Blueprint §2 lists "Master data (task types,
 *       <i>SLA</i>, workflow, holidays)" as Admin ✅ and every other role ❌, and
 *       B-001's own description of the capability names SLA in it.</li>
 * </ul>
 *
 * <p><b>{@link ProjectMemberController} beside it is {@code project.manage} and
 * reaches PM, and the difference is deliberate.</b> Two tabs of one screen with
 * two different capabilities looks like an oversight and is not: §2 gives PM
 * "map resources to a project", which is the Team tab, and withholds master
 * data, which is this one. A PM setting the response target a client is
 * contractually held to is a different act from a PM staffing their own project,
 * and the escalation flags here decide whose manager gets woken. Flagged rather
 * than smoothed over, because the obvious "consistency" fix is to widen this one
 * and it would be widening the wrong one.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule: a hardcoded role check keeps refusing the seventh role the Role Master
 * has just granted the capability to. B-023's calendar — the other master under
 * this feature — is annotated exactly this way.
 *
 * <h2>The {@code ETag} is on the read because the write needs one</h2>
 *
 * <p>The contract required {@code If-Match} on the {@code PUT} and declared no
 * {@code ETag} anywhere, so there was nowhere to obtain the tag and the
 * operation was uncallable — the same gap B-016 closed by adding
 * {@code GET /projects/{projectId}}. Both operations emit one here.
 *
 * <p>{@code check-conventions.py} would not have caught it: its §5 detail-read
 * rule fires on paths ending in {@code }}, and this one ends in a collection
 * segment. Left as is rather than widened — a rule that demanded an {@code ETag}
 * on every collection read would fire on a dozen paginated lists that
 * legitimately have none.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/sla-policies")
@Tag(name = "projects")
class SlaPolicyController {

    private final SlaMatrixService service;

    SlaPolicyController(SlaMatrixService service) {
        this.service = service;
    }

    /**
     * The resolved matrix — every active task type × level, with its source.
     *
     * <p>No {@code meta}, and no cursor. CONVENTIONS.md §6's exemption has been
     * registered since D-002 with its reason ("task types x 4 levels"); this is
     * the operation that makes it real.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getSlaPolicies", summary = "SLA matrix — task type × level (S-10)")
    ResponseEntity<SlaPolicyDtos.SlaMatrixResponse> matrix(@PathVariable long projectId) {
        return ok(service.matrix(projectId));
    }

    /**
     * Replace this project's overrides.
     *
     * <p>The body is the cells that are <b>overrides</b>, never the resolved
     * grid — {@link SlaMatrixService} explains at length what sending the grid
     * back would silently do to inheritance.
     *
     * <p>{@code @Valid} on the list validates its elements: Bean Validation
     * cascades into a {@code @RequestBody List<T>} because the parameter itself
     * is the cascade root. It does <b>not</b> cascade into a field holding a
     * list without {@code @Valid} on that field, which is the shape this would
     * have had if the contract had wrapped the array in an envelope — it does
     * not, deliberately: CONVENTIONS.md §2's {@code { data }} rule is about
     * <i>responses</i>, and every other request body in the contract is a bare
     * object.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "replaceSlaPolicies", summary = "Replace the SLA matrix (S-10)")
    ResponseEntity<SlaPolicyDtos.SlaMatrixResponse> replace(
            @PathVariable long projectId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody List<SlaPolicyDtos.SlaPolicyWrite> overrides) {

        requirePrecondition(projectId, ifMatch);
        return ok(service.replace(projectId, overrides));
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
     * needed it least. It matters more here than anywhere else in the feature —
     * this is a wholesale replace, so a stale tab's save does not merge with
     * another administrator's, it erases it. CONVENTIONS.md §5 calls that the
     * worst case for a lost update and names this operation in the entry for
     * {@code PUT /masters/roles/{roleId}/permissions}.
     *
     * <p>The 404 comes first. Answering 428 for a project that does not exist
     * would send the caller to fetch a tag from a URL that will 404 as well.
     */
    private void requirePrecondition(long projectId, String ifMatch) {
        String current = etagOf(service.matrix(projectId));

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the SLA matrix first and send back its ETag.");
        }
        if (!matches(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This matrix changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<SlaPolicyDtos.SlaMatrixResponse> ok(List<SlaPolicyDtos.SlaCell> cells) {
        return ResponseEntity.ok().eTag(etagOf(cells))
                .body(new SlaPolicyDtos.SlaMatrixResponse(cells));
    }

    /**
     * Derived from the resolved grid, not from any row's {@code updated_at}.
     *
     * <p>Content-based for {@link ProjectController#etagOf}'s reason — a
     * timestamp tag moves when a save rewrites identical values and fails an
     * edit that conflicts with nothing.
     *
     * <p>It is taken over the <b>resolved</b> grid rather than over this
     * project's own rows, which is the part worth stating: a change to the
     * org-wide default moves this project's tag even though nothing on the
     * project changed. That is correct. The administrator was shown inherited
     * figures and is deciding which of them to override; if the inherited
     * figures moved underneath them, the decision was made against numbers that
     * are no longer true, and a reload is exactly the right cost.
     */
    private static String etagOf(List<SlaPolicyDtos.SlaCell> cells) {
        return Integer.toHexString(cells.hashCode());
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
