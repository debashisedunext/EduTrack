package com.edunext.edutrack.api.feature.masters.templates;

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

import java.util.List;

/**
 * B-041 · S-13 tab 3 — the Workflow Template Master, per
 * {@code contracts/openapi.yaml}.
 *
 * <p><b>Six operations, one of which has been declared since D-001 and served by
 * nobody</b> — {@code createWorkflowTemplate}, the seventh such case this stream
 * has found. B-040 found the sixth, {@code listWorkflowTemplates}, and left this
 * one alone because the shape it was declared with could not be served: it
 * carried {@code projectId} and {@code taskTypeId} as scalars on the template,
 * and there was no table to put a mapping in either way. Both are now fixed, in
 * opposite directions — the table exists, and the fields still leave the request,
 * because §4A.9 maps one template to several pairs.
 *
 * <p>{@code GET /workflow-templates} stays on {@code StageController}. It is tab
 * 2's selector, it has no {@code ETag} on purpose, and moving it here would be a
 * change to a route Stream C's ticket list has read since C-013 in order to tidy
 * a package boundary. This controller serves the per-template read that tab 3
 * needs — with a tag, because it is what the writes precondition on.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>, on templates, mappings and
 *       resolution. B-040's argument for the stage reads carries directly: a
 *       ribbon renders on every ticket page for every role, and the template is
 *       what it renders from. Resolution is included in that rather than
 *       excepted: "which flow will my ticket follow?" is a question a Developer
 *       raising one is better off reading than discovering, and every input to
 *       the answer — the project, the task type, the template list — is already
 *       readable by them.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}. §2's
 *       row reads "Master data (task types, SLA, <b>workflow</b>, holidays)" and
 *       S-13 is titled "Status, Stage &amp; Workflow Template Master".</li>
 * </ul>
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule: a hard-coded role check would go on refusing a seventh role the Role
 * Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not, for the reason
 * {@code StageController} records: master data is not row-scoped, and every
 * template is already public through {@code listWorkflowTemplates}. Registered in
 * {@code check-conventions.py}'s {@code ROWLESS_403}.
 *
 * <p><b>{@code /resolution} is a literal segment sitting beside
 * {@code /{templateId}}</b>, and Spring's pattern comparator prefers the literal,
 * so the two cannot be confused. It is a route rather than a query parameter on
 * the list because it answers about a pair rather than about a template, and
 * hanging it off the collection would have made {@code listWorkflowTemplates}
 * return two different shapes depending on its arguments.
 */
@RestController
@RequestMapping("/api/v1/masters/workflow-templates")
@Tag(name = "masters")
class TemplateController {

    private final TemplateService service;
    private final TemplateResolver resolver;

    TemplateController(TemplateService service, TemplateResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    /**
     * One template, with an {@code ETag} the {@code PATCH} and {@code DELETE}
     * precondition on.
     *
     * <p>The tag covers three counts the caller did not send and cannot see
     * change — see {@link TemplateDtos.WorkflowTemplateDetail#etagBasis()}. That is the
     * whole reason the writes have a precondition at all: the interesting
     * refusals here are about other rows, so a lost update is not two Admins
     * overwriting each other's names, it is one Admin deleting a template on the
     * strength of a zero that stopped being zero while the dialog was open.
     */
    @GetMapping(path = "/{templateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getWorkflowTemplate", summary = "One workflow template (S-13 tab 3)")
    ResponseEntity<TemplateDtos.WorkflowTemplateDetailResponse> get(@PathVariable long templateId) {
        TemplateDtos.WorkflowTemplateDetail view = service.get(templateId)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok()
                .eTag(etagOf(view))
                .body(new TemplateDtos.WorkflowTemplateDetailResponse(view));
    }

    /**
     * Create a template, optionally as a copy of an existing ribbon.
     *
     * <p>No {@code If-Match} — there is nothing to precondition on before a row
     * exists. {@code Idempotency-Key} is accepted per {@code CONVENTIONS.md} and
     * is handled by the shared filter rather than here.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createWorkflowTemplate",
            summary = "Create a workflow template (S-13 tab 3)")
    ResponseEntity<TemplateDtos.WorkflowTemplateDetailResponse> create(
            @Valid @RequestBody TemplateDtos.WorkflowTemplateWriteRequest body) {
        TemplateDtos.WorkflowTemplateDetail created = service.create(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etagOf(created))
                .body(new TemplateDtos.WorkflowTemplateDetailResponse(created));
    }

    /**
     * Rename, re-describe, activate/deactivate, or hand over the default flag.
     *
     * <p>{@code null} means "leave it alone" on every field —
     * {@code CONVENTIONS.md}'s patch rule. B-042 put stage deprecation on its own
     * route rather than accept a boolean under that rule, and the objection does
     * not carry here: {@code isActive} and {@code isDefault} are ordinary master
     * states with no consequence for a ticket already in flight, which was the
     * whole of that argument.
     */
    @PatchMapping(path = "/{templateId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateWorkflowTemplate",
            summary = "Edit a workflow template (S-13 tab 3)")
    ResponseEntity<TemplateDtos.WorkflowTemplateDetailResponse> update(
            @PathVariable long templateId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody TemplateDtos.WorkflowTemplatePatchRequest body) {

        TemplateDtos.WorkflowTemplateDetail current = service.get(templateId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOf(current),
                "If-Match is required. Read the template first, then send its ETag.");

        TemplateDtos.WorkflowTemplateDetail updated = service.update(templateId, body)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok()
                .eTag(etagOf(updated))
                .body(new TemplateDtos.WorkflowTemplateDetailResponse(updated));
    }

    /**
     * Delete a template nothing has ever run on.
     *
     * <p>{@code If-Match} is required, and the asymmetry with the {@code PATCH}'s
     * optional one is deliberate — B-042 drew the same line on the stage delete.
     * The entire guard is that two counts are zero, and both are inside the tag,
     * so a ticket created or a rule pointed at the template while the confirmation
     * dialog sits open moves the tag and the request is refused with 412 rather
     * than performed on evidence that stopped being true.
     */
    @DeleteMapping(path = "/{templateId}")
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "deleteWorkflowTemplate",
            summary = "Delete an unused workflow template (S-13 tab 3)")
    ResponseEntity<Void> delete(@PathVariable long templateId,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        TemplateDtos.WorkflowTemplateDetail current = service.get(templateId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOf(current),
                "If-Match is required on this delete. Read the template first, then send its ETag.");

        service.delete(templateId).orElseThrow(() -> notFound(templateId));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // Mappings
    // ------------------------------------------------------------------

    /**
     * One template's routing rules, with an {@code ETag}.
     *
     * <p>The third collection read in the product to carry a tag, after B-039's
     * transition matrix and B-040's stage list, and for the identical reason: the
     * set <em>is</em> the unit of edit, {@link #replaceMappings} replaces it
     * whole, and there is no per-row verb to precondition on. Without a tag here
     * that write would need a {@code NO_IF_MATCH} exemption on exactly the shape
     * of write where a lost update is least visible — the loser's rules vanish
     * with nothing to indicate they were ever there.
     */
    @GetMapping(path = "/{templateId}/mappings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listTemplateMappings",
            summary = "One template's project x task-type rules (S-13 tab 3)")
    ResponseEntity<TemplateDtos.TemplateMappingListResponse> mappings(@PathVariable long templateId) {
        List<TemplateDtos.TemplateMapping> data = service.listMappings(templateId)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok()
                .eTag(etagOfMappings(data))
                .body(new TemplateDtos.TemplateMappingListResponse(data));
    }

    /**
     * Replace one template's routing rules.
     *
     * <p>A {@code PUT} of the whole set rather than a {@code POST} and
     * {@code DELETE} per rule, on B-039's reasoning: the set is what the Admin
     * edited, and a delta protocol needs the client to describe removals, which is
     * the message that goes missing.
     */
    @PutMapping(path = "/{templateId}/mappings", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "replaceTemplateMappings",
            summary = "Replace a template's project x task-type rules (S-13 tab 3)")
    ResponseEntity<TemplateDtos.TemplateMappingListResponse> replaceMappings(
            @PathVariable long templateId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody TemplateDtos.TemplateMappingReplaceRequest body) {

        List<TemplateDtos.TemplateMapping> current = service.listMappings(templateId)
                .orElseThrow(() -> notFound(templateId));
        requirePrecondition(ifMatch, etagOfMappings(current),
                "If-Match is required. Read the rules first, then send their ETag.");

        List<TemplateDtos.TemplateMapping> saved = service.replaceMappings(templateId, body)
                .orElseThrow(() -> notFound(templateId));
        return ResponseEntity.ok()
                .eTag(etagOfMappings(saved))
                .body(new TemplateDtos.TemplateMappingListResponse(saved));
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Which template a project × task type resolves to, and which rung answered.
     *
     * <p>Both parameters are optional, and omitting one is not the same as
     * sending a bad value — it asks "what does this task type resolve to on a
     * project with no rule of its own?", which is precisely the question an Admin
     * checking their configuration wants. A pair of nulls is legal too and returns
     * the catch-all rule or the default.
     *
     * <p>No {@code ETag}. There is no row here to precondition a write on: this is
     * a computed answer over three tables, and a tag would move whenever any of
     * them did while meaning nothing to any operation.
     */
    @GetMapping(path = "/resolution", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "resolveWorkflowTemplate",
            summary = "Which template a project x task type routes to (S-13 tab 3)")
    TemplateDtos.TemplateResolutionResponse resolve(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Integer taskTypeId) {
        return new TemplateDtos.TemplateResolutionResponse(resolver.explain(projectId, taskTypeId));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * {@code 428} for an absent {@code If-Match}, {@code 412} for a stale one.
     *
     * <p>Required rather than opt-in on all three writes, copied verbatim from
     * {@code StageController} because the two controllers serve one screen and an
     * Admin should not find that two tabs of it disagree about whether a
     * precondition is needed. Its reason holds here too: a precondition a caller
     * can skip is a precondition that protects nothing on the day it matters.
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

    /**
     * A weak prefix and a comma list are both tolerated — RFC 9110 §13.1.1 rather
     * than leniency. A proxy is entitled to weaken a tag in transit, and a client
     * is entitled to send several.
     */
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

    private static String etagOf(TemplateDtos.WorkflowTemplateDetail view) {
        return Integer.toHexString(view.etagBasis().hashCode());
    }

    /**
     * One tag over the whole rule set.
     *
     * <p>The count leads the basis, so that removing one rule and adding another
     * cannot leave the tag unmoved by coincidence of ordering.
     */
    private static String etagOfMappings(List<TemplateDtos.TemplateMapping> data) {
        StringBuilder basis = new StringBuilder().append(data.size());
        for (TemplateDtos.TemplateMapping m : data) {
            basis.append('|').append(m.id())
                    .append(':').append(m.projectId())
                    .append(':').append(m.taskTypeId()).append('#');
        }
        return Integer.toHexString(basis.toString().hashCode());
    }

    private static ResponseStatusException notFound(long templateId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No workflow template with id " + templateId + ".");
    }
}
