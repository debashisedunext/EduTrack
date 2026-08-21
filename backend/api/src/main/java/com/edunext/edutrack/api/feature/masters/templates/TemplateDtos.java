package com.edunext.edutrack.api.feature.masters.templates;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-041 · the wire shapes of S-13 tab 3, per {@code contracts/openapi.yaml}.
 *
 * <p><b>The declared write request could not be served as written, and that is
 * the shape decision of this task.</b> {@code WorkflowTemplateWriteRequest} has
 * carried scalar {@code projectId} and {@code taskTypeId} since D-001, which
 * reads as though a template belongs to one project × task-type pair. §4A.9's own
 * examples refute it in the same paragraph that introduces them — Standard Dev
 * Flow is named against Production Bug, Change Request <em>and</em> Future
 * Release. Two scalars cannot hold three pairs, so the mapping is its own
 * resource with its own routes, and those two fields leave the request the same
 * way B-040 removed them from the response: rather than be served as something
 * they cannot mean.
 *
 * <p>{@code stages} leaves it too, for a different and smaller reason. B-040
 * already serves {@code POST /workflow-templates/{id}/stages}, and a second way
 * to write a stage — one that skipped the {@code canReturnTo} direction check,
 * the code-uniqueness check and the {@code seq} spacing that route holds — would
 * be a second set of rules to keep true. What replaces it is
 * {@link WorkflowTemplateWriteRequest#copyStagesFromTemplateId()}, which is §7.4's "built
 * by picking stages" done the way A-005's header says a template must be
 * versioned: <b>by copy</b>.
 */
final class TemplateDtos {

    private TemplateDtos() {
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /**
     * One template as tab 3 reads it.
     *
     * <p>The three counts are all here because each one is the answer to a
     * different question the screen asks before it offers a button, and each is a
     * fact about <em>other</em> rows that a client holding only this list could
     * not derive.
     *
     * <ul>
     *   <li>{@code stageCount} — how long the ribbon is. Already on B-040's
     *       shape; the preview needs it before it has fetched the stages.</li>
     *   <li>{@code mappingCount} — how many routing rules point here. What makes
     *       deactivation refusable, and what an Admin reads to tell a template
     *       that is in use from one that was drafted and abandoned.</li>
     *   <li>{@code ticketCount} — how many tickets ever started on this template.
     *       The delete's entire guard, and the reason it is inside
     *       {@link #etagBasis()}.</li>
     * </ul>
     *
     * <p>{@code isDeletable} and {@code isDeactivatable} are computed server-side
     * rather than restated in TypeScript, which is B-040's {@code isCodeEditable}
     * argument and B-042's {@code isDeletable} argument arriving a third time. The
     * failure mode of a second copy is a form that greys out a control the server
     * would have accepted, or — worse — offers one it will refuse.
     */
    record WorkflowTemplateDetail(
            long id,
            String name,
            String description,
            boolean isDefault,
            boolean isActive,
            int stageCount,
            long mappingCount,
            long ticketCount,
            boolean isDeletable,
            boolean isDeactivatable,
            Instant createdAt,
            Instant updatedAt) {

        /**
         * What the {@code ETag} hashes.
         *
         * <p>{@code ticketCount} and {@code mappingCount} are inside it
         * deliberately, on the reasoning B-040 used for the two stage counts: they
         * are what {@code isDeletable} and {@code isDeactivatable} are derived
         * from, so a ticket created on this template — or a rule pointed at it —
         * while the confirmation dialog sits open changes the answer to "may I
         * remove this?". A precondition that ignored them would let the delete
         * through on evidence that had stopped being true a moment earlier.
         *
         * <p>{@code stageCount} is in it too, and that one is closer to a
         * judgement call. A stage added on tab 2 does not change what tab 3 may
         * do — but it does change what the preview draws, and the preview is the
         * thing §7.4 asks this tab to be validated against. A rename saved on top
         * of a ribbon the Admin was no longer looking at is the lost update worth
         * refusing.
         */
        String etagBasis() {
            return String.join("|",
                    String.valueOf(id), name, String.valueOf(description),
                    String.valueOf(isDefault), String.valueOf(isActive),
                    String.valueOf(stageCount), String.valueOf(mappingCount),
                    String.valueOf(ticketCount));
        }
    }

    /**
     * One routing rule, with both ends named.
     *
     * <p>{@code projectCode}/{@code projectName} and
     * {@code taskTypeCode}/{@code taskTypeName} are joined in rather than left to
     * the client to look up. The screen would otherwise hold three lists and do
     * the join itself, and it would be wrong in one specific case that matters —
     * a project deactivated after the rule was written is absent from the picker
     * the client populates from, so the rule would render with a blank where its
     * subject should be. The rule still routes; a row that cannot say what it
     * routes is worse than one that names something retired.
     *
     * <p>Both are {@code null} when the column is, and {@code null} means
     * <b>any</b> rather than unknown. The screen renders the word.
     */
    record TemplateMapping(
            long id,
            Long projectId,
            String projectCode,
            String projectName,
            Integer taskTypeId,
            String taskTypeCode,
            String taskTypeName,
            int specificity) {
    }

    /**
     * What a project × task type resolves to, and which rung answered.
     *
     * <p><b>{@code rung} is the field this route exists for.</b> The template id
     * alone would let a caller route a ticket and would not let the screen
     * distinguish "an Admin wrote this rule" from "nothing matched, so this is the
     * default" — and those two need different treatment on a screen whose job is
     * to make the routing configuration reviewable. A pair silently falling
     * through to the default is the failure mode §4A.9 has no other way to
     * surface.
     *
     * <p>{@code templateId} is nullable, and the case is real rather than
     * defensive: no rule matched <em>and</em> no template carries
     * {@code is_default}. Nothing forbids that state — {@code is_default} is a
     * plain {@code TINYINT} with an index and no constraint — so the honest answer
     * is that the pair resolves to nothing, which the screen can show and a
     * hard-coded fallback would hide.
     */
    record TemplateResolution(
            Long templateId,
            String templateName,
            String rung,
            Long mappingId) {

        /** The four rungs, as the wire spells them. */
        static final String EXACT = "EXACT";
        static final String PROJECT = "PROJECT";
        static final String TASK_TYPE = "TASK_TYPE";
        static final String ANY = "ANY";
        static final String DEFAULT = "DEFAULT";
        static final String NONE = "NONE";
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    /**
     * Create a template — {@code createWorkflowTemplate}, declared since D-001 and
     * served for the first time here.
     *
     * <p>{@code copyStagesFromTemplateId} is how §7.4's "built by picking stages"
     * is honoured without a second stage-writing path. A-005's own header says a
     * template is <b>versioned by copy, never edited in place</b>, and this is
     * that operation: the new template starts as a duplicate of an existing
     * ribbon, and tab 2 edits it from there. Omitting it creates a template with
     * no stages, which is legal and is the empty canvas B-043's designer will fill
     * — a template with no live stage routes no ticket, which is why
     * {@code TemplateService} refuses to make such a template the default.
     */
    record WorkflowTemplateWriteRequest(
            @NotNull @Size(min = 1, max = 80) String name,
            @Size(max = 255) String description,
            Boolean isDefault,
            Long copyStagesFromTemplateId) {
    }

    /**
     * Edit a template.
     *
     * <p>Every field is nullable and {@code null} means "leave it alone" —
     * {@code CONVENTIONS.md}'s patch rule, and the reason B-042 put stage
     * deprecation on its own route rather than adding a boolean here. The same
     * objection does not apply to {@code isActive} or {@code isDefault}: both are
     * ordinary master-row states with the same shape every other master's patch
     * carries, and neither has a consequence for a ticket already in flight, which
     * was the whole of the deprecation argument.
     */
    record WorkflowTemplatePatchRequest(
            @Size(min = 1, max = 80) String name,
            @Size(max = 255) String description,
            Boolean isDefault,
            Boolean isActive) {
    }

    /**
     * One rule in the whole-set replace.
     *
     * <p>No {@code id}. The {@code PUT} replaces the set, so a caller echoing back
     * an id would be describing a row this operation may not keep — and matching
     * on the pair rather than on the id is what lets an unchanged rule keep its
     * row (and its {@code created_at}) without the client having to track which
     * rules it has seen. That is B-039's transition-matrix upsert, one table over.
     */
    record TemplateMappingEntry(
            Long projectId,
            Integer taskTypeId) {
    }

    /**
     * The whole set of one template's rules.
     *
     * <p><b>A replace and not a list of deltas</b>, for the reason B-039's
     * transition matrix gives: the set is what the Admin edited, there is no
     * per-row verb on this screen, and a delta protocol would need the client to
     * describe removals — which is exactly the message that goes missing.
     *
     * <p>An empty list is legal and means "this template is routed to by nothing".
     * It is not the same as deleting the template, and it is the state every
     * template starts in.
     */
    record TemplateMappingReplaceRequest(
            @NotNull @Valid List<TemplateMappingEntry> mappings) {
    }

    // ------------------------------------------------------------------
    // Envelopes
    // ------------------------------------------------------------------

    record WorkflowTemplateDetailResponse(WorkflowTemplateDetail data) {
    }

    record TemplateMappingListResponse(List<TemplateMapping> data) {
    }

    record TemplateResolutionResponse(TemplateResolution data) {
    }
}
