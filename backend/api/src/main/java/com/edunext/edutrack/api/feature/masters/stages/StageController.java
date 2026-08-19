package com.edunext.edutrack.api.feature.masters.stages;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * B-040 · S-13 tab 2 — the Stage Master, per {@code contracts/openapi.yaml}.
 *
 * <p><b>Eight operations.</b> Six arrived with B-040, and B-042 added the two §7.4
 * asks for by name: the deprecation setter, and the narrow delete its clause
 * leaves room for.
 *
 * <p>One of the six <b>had been declared since D-001 and served by nobody</b> —
 * {@code listWorkflowTemplates}, the sixth case this stream has found of a route
 * that existed in the contract, the MSW mock and the generated client with no
 * controller behind it. What made it different from B-021's priorities is that the
 * declared <em>shape</em> had drifted from A-005's table as well: {@code version},
 * {@code projectId} and {@code taskTypeId} name no column and no mapping table, so
 * B-040 removed them rather than emit a hard-coded {@code 1} and two nulls. B-041
 * brings the mapping back when it has somewhere to store it.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>, on templates and on stages. Stream
 *       C's ribbon renders {@code displayName}, {@code icon} and {@code slaHours}
 *       on every ticket page for every role; a Developer who could not read the
 *       stage list could not see the segment they are standing in. And
 *       {@code ownerRole} is the answer to "why can I not move this ticket?",
 *       which is a question a user is better off reading than discovering.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}. §2's
 *       row reads "Master data (task types, SLA, <b>workflow</b>, holidays)" and
 *       S-13 is titled "Status, Stage &amp; Workflow Template Master", so this one
 *       needs no argument — unlike B-021's levels, which did.</li>
 * </ul>
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's rule
 * and B-015's reason: a hard-coded role check would go on refusing a seventh role
 * the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: master data is not row-scoped,
 * and every stage is already public through {@code listStages}. Recorded in
 * {@code check-conventions.py}'s {@code ROWLESS_403} with that reason, beside the
 * role, task type, priority and status masters.
 *
 * <p><b>There is a {@code DELETE} mapping and it refuses most of the stages it
 * could be pointed at</b> — B-042. §7.4 permits removal only where its clause does
 * not reach: a stage nothing has ever entered, nothing stands in, nothing live
 * returns to, and which is not the template's last live one. Everything else is
 * {@code /deprecation}, and the 409 says so rather than only saying no. See
 * {@link StageService#delete}.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class StageController {

    private final StageService service;

    StageController(StageService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // Templates — tab 2's selector
    // ------------------------------------------------------------------

    @GetMapping(path = "/workflow-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listWorkflowTemplates", summary = "Workflow templates (S-13 tab 2)")
    StageDtos.WorkflowTemplateListResponse templates() {
        return new StageDtos.WorkflowTemplateListResponse(service.templates());
    }

    // ------------------------------------------------------------------
    // Stages
    // ------------------------------------------------------------------

    /**
     * The whole ribbon of one template, with an {@code ETag}.
     *
     * <p>The tag is on a <em>collection</em> read, which every other master takes
     * from a single-row route. B-039's transition matrix was the first exception
     * and this is the second, for the identical reason: {@code reorderStages} is a
     * whole-set replace with no per-row verb to precondition on, so without a tag
     * here that write would have needed a {@code NO_IF_MATCH} exemption — on
     * exactly the operation where a lost update is least visible, since the losing
     * order simply reappears.
     */
    @GetMapping(path = "/workflow-templates/{templateId}/stages",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listStages", summary = "One template's stages (S-13 tab 2)")
    ResponseEntity<StageDtos.StageListResponse> stages(@PathVariable long templateId) {
        List<StageDtos.StageView> data = service.list(templateId)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok().eTag(etagOfList(data))
                .body(new StageDtos.StageListResponse(data));
    }

    /**
     * Exists to emit the {@code ETag} the {@code PATCH} requires as
     * {@code If-Match} — CONVENTIONS.md §5. B-011, B-016, B-020, B-021 and B-039
     * added the same route for users, projects, task types, levels and statuses;
     * without it the write is uncallable and the contract describes an operation
     * nobody can reach.
     */
    @GetMapping(path = "/workflow-templates/{templateId}/stages/{stageId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getStage", summary = "One stage (S-13 tab 2)")
    ResponseEntity<StageDtos.StageResponse> stage(@PathVariable long templateId,
                                                  @PathVariable long stageId) {
        StageDtos.StageView view = service.find(templateId, stageId)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok().eTag(etagOf(view)).body(new StageDtos.StageResponse(view));
    }

    @PostMapping(path = "/workflow-templates/{templateId}/stages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createStage", summary = "Add a stage (S-13 tab 2)")
    ResponseEntity<StageDtos.StageResponse> create(
            @PathVariable long templateId,
            @Valid @RequestBody StageDtos.StageWrite write) {

        StageDtos.StageView created = service.create(templateId, write);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(created))
                .body(new StageDtos.StageResponse(created));
    }

    @PatchMapping(path = "/workflow-templates/{templateId}/stages/{stageId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateStage", summary = "Edit a stage (S-13 tab 2)")
    ResponseEntity<StageDtos.StageResponse> update(
            @PathVariable long templateId,
            @PathVariable long stageId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody StageDtos.StagePatch patch) {

        StageDtos.StageView current = service.find(templateId, stageId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOf(current),
                "If-Match is required. GET the stage first and send back its ETag.");

        StageDtos.StageView updated = service.update(templateId, stageId, patch);
        return ResponseEntity.ok().eTag(etagOf(updated))
                .body(new StageDtos.StageResponse(updated));
    }

    /**
     * §7.4's "drag to reorder".
     *
     * <p>{@code PUT} rather than {@code PATCH} because the body is the whole
     * ordered set, not a change to part of one — the same call
     * {@code putStatusTransitions} makes about the matrix. The precondition comes
     * from {@link #stages}, which is the read the screen already has open.
     */
    @PutMapping(path = "/workflow-templates/{templateId}/stages/order",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "reorderStages", summary = "Drag to reorder the ribbon (S-13 tab 2)")
    ResponseEntity<StageDtos.StageListResponse> reorder(
            @PathVariable long templateId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody StageDtos.StageOrder order) {

        List<StageDtos.StageView> current = service.list(templateId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOfList(current),
                "If-Match is required. GET the stage list first and send back its ETag.");

        if (order.stageIds() == null || order.stageIds().isEmpty()) {
            throw new StageService.StageValidationException("stageIds",
                    "Send every stage of this template, in the order they should appear.");
        }

        List<StageDtos.StageView> reordered = service.reorder(templateId, order.stageIds());
        return ResponseEntity.ok().eTag(etagOfList(reordered))
                .body(new StageDtos.StageListResponse(reordered));
    }

    /**
     * Retire a stage, or bring it back — §7.4's "deprecated, never deleted".
     *
     * <p><b>{@code PUT} to a sub-resource rather than a field on the
     * {@code PATCH}</b>, which is the shape {@code /users/{userId}/status} and
     * {@code /clients/{clientId}/status} both take. The patch's convention is that
     * null means "leave it alone", so a boolean there would carry three wire states
     * for a column with two — and the one write in this package with a consequence
     * for live tickets would arrive indistinguishable from a display-name edit.
     *
     * <p><b>No {@code If-Match}</b>, and it is the only write here without one.
     * This is an idempotent setter: the body names the state it wants rather than
     * a delta, so two Admins racing produce the state whoever clicked last asked
     * for, which is the correct answer rather than a lost update. Recorded in
     * {@code check-conventions.py}'s {@code NO_IF_MATCH} beside the two status
     * setters it copies. The refusals that do depend on other rows — the last live
     * stage, an arrow still pointing here — are re-read inside the transaction, so
     * a stale screen cannot talk the server past them.
     */
    @PutMapping(path = "/workflow-templates/{templateId}/stages/{stageId}/deprecation",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "setStageDeprecation",
            summary = "Deprecate or restore a stage (S-13 tab 2)")
    ResponseEntity<StageDtos.StageResponse> deprecation(
            @PathVariable long templateId,
            @PathVariable long stageId,
            @Valid @RequestBody StageDtos.StageDeprecation body) {

        service.find(templateId, stageId).orElseThrow(() -> notFound(templateId));
        StageDtos.StageView updated =
                service.setDeprecated(templateId, stageId, body.isDeprecated());
        return ResponseEntity.ok().eTag(etagOf(updated))
                .body(new StageDtos.StageResponse(updated));
    }

    /**
     * Remove a stage — only where §7.4's clause does not reach.
     *
     * <p><b>{@code If-Match} required, which is unusual on a {@code DELETE} and is
     * the point of it here.</b> The entire guard is that both usage counts are
     * zero, and those two counts are inside the tag {@link #stage} emits. So a
     * ticket entering the stage while the confirmation dialog sits open moves the
     * tag, and the delete is refused with 412 rather than performed on evidence
     * that stopped being true a moment ago. A destructive verb whose precondition
     * is a fact about other tables is exactly where a lost update is worst — the
     * row is gone and there is nothing left to notice it by.
     *
     * <p>{@code 204}, and no body. The screen refetches the ribbon, which it has to
     * anyway: {@code position} is a fact about neighbours and every row after this
     * one has just changed.
     */
    @DeleteMapping(path = "/workflow-templates/{templateId}/stages/{stageId}")
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "deleteStage", summary = "Delete an unused stage (S-13 tab 2)")
    ResponseEntity<Void> delete(
            @PathVariable long templateId,
            @PathVariable long stageId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {

        StageDtos.StageView current = service.find(templateId, stageId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOf(current),
                "If-Match is required. GET the stage first and send back its ETag.");

        service.delete(templateId, stageId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Preconditions and tags
    // ------------------------------------------------------------------

    /**
     * {@code 428} for an absent {@code If-Match}, {@code 412} for a stale one.
     *
     * <p>Required rather than opt-in, as on every other master write here. A
     * precondition a caller can skip is a precondition that protects nothing on
     * the day it matters.
     */
    private static void requirePrecondition(String ifMatch, String expected, String absentMessage) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, absentMessage);
        }
        if (!matches(ifMatch, expected)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Somebody else changed this since you read it. Reload and try again.");
        }
    }

    private static boolean matches(String ifMatch, String expected) {
        for (String candidate : ifMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(expected) || tag.equals("\"" + expected + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static String etagOf(StageDtos.StageView view) {
        return Integer.toHexString(view.etagBasis().hashCode());
    }

    /**
     * One tag over the whole ribbon.
     *
     * <p>Built from every row's own basis, so it moves when any stage is edited
     * and not only when the order changes. Two Admins working on different rows of
     * the same template would otherwise both hold a valid tag for a reorder that
     * was about to discard one of their edits.
     */
    private static String etagOfList(List<StageDtos.StageView> views) {
        StringBuilder basis = new StringBuilder();
        for (StageDtos.StageView view : views) {
            basis.append(view.etagBasis()).append('#');
        }
        return Integer.toHexString(basis.toString().hashCode());
    }

    private static ResponseStatusException notFound(long templateId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No workflow template %d.".formatted(templateId));
    }
}
