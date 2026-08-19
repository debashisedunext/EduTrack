package com.edunext.edutrack.api.feature.masters.stages;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * B-040 · the wire shapes of S-13 tab 2, per {@code contracts/openapi.yaml}.
 *
 * <p><b>{@code seq} appears on the view and on no request.</b> A caller who could
 * choose it would be choosing a value {@code uq_workflow_stages_seq
 * (template_id, seq)} may already hold, and the collision is one the screen has
 * no way to anticipate — it would arrive as a 409 on a field the Admin never
 * filled in. A create appends; {@link StageDtos.StageOrder} is the only way order
 * changes. That asymmetry is the reason the write records are not simply the view
 * with fields made optional.
 */
final class StageDtos {

    private StageDtos() {
    }

    /**
     * One stage as the screen reads it.
     *
     * <p><b>{@code seq} and {@code position} are both here and they are not the
     * same number.</b> {@code seq} is B-004's stored 10, 20, 30 — spaced so a
     * stage can be dropped between two others later without renumbering the
     * table. {@code position} is the 1-based index an Admin sees. A client that
     * derived {@code position} from array order would be right until somebody
     * sorted the list by name, and wrong silently after that.
     *
     * <p><b>{@code isCodeEditable} is computed here rather than restated in
     * TypeScript.</b> It is the same rule {@link StageService} enforces on the
     * write, and a second copy of it in the form would be a second thing to keep
     * true — the failure mode being a form that greys out a field the server
     * would have accepted, or worse, offers one it will refuse.
     */
    record StageView(
            long id,
            long templateId,
            String stageCode,
            String displayName,
            String ownerRole,
            BigDecimal slaHours,
            boolean isOptional,
            List<String> canReturnTo,
            String icon,
            short seq,
            int position,
            long transitionCount,
            long openTicketCount,
            boolean isCodeEditable) {

        /**
         * What the {@code ETag} hashes.
         *
         * <p>The two counts are inside it deliberately. They are what
         * {@code isCodeEditable} is derived from, so a ticket entering this stage
         * while the edit dialog sits open changes the answer to "may I rename
         * this?" — and a precondition that ignored them would let the rename
         * through on a stage that had stopped being safe to rename a moment ago.
         */
        String etagBasis() {
            return String.join("|",
                    String.valueOf(id), stageCode, displayName, ownerRole,
                    String.valueOf(slaHours), String.valueOf(isOptional),
                    String.valueOf(canReturnTo), String.valueOf(icon),
                    String.valueOf(seq), String.valueOf(transitionCount),
                    String.valueOf(openTicketCount));
        }
    }

    /** One template, as tab 2's selector reads it. */
    record WorkflowTemplateView(
            long id,
            String name,
            String description,
            boolean isDefault,
            boolean isActive,
            int stageCount,
            List<InlineStageView> stages) {
    }

    /**
     * The ribbon as a <em>vocabulary</em>, inline on the template — the shape the
     * contract has declared since D-001 and the one S-25's ticket list reads.
     *
     * <p><b>It is not {@link StageView} with fields removed, and B-040 nearly
     * deleted it on that assumption.</b> The drafting had this array taken off
     * {@code WorkflowTemplate} as redundant beside {@code listStages} — and
     * {@code TicketListPage} has been building its stage filter from it since
     * C-013, deduplicating by {@code stageCode} across every template. Removing
     * it would have emptied a shipped filter to tidy a response.
     *
     * <p>The two shapes answer different questions and neither is a subset worth
     * collapsing. This one has <b>no identity</b>, because a filter matches on the
     * code and several templates legitimately share one; {@code StageView} has
     * {@code id}, {@code templateId} and the two usage counts, which are four
     * fields with no meaning to a filter in a response every ticket list reads.
     *
     * <p>{@code isDeprecated} is {@code false} on every row until <b>B-042</b>
     * adds the column — which is exactly what it has been since D-001, with
     * nothing behind it. Serving it as a constant is not new drift; leaving the
     * field out would have been.
     */
    record InlineStageView(
            String stageCode,
            String displayName,
            int sequence,
            String ownerRole,
            String icon,
            BigDecimal stageSlaHrs,
            boolean isOptional,
            List<String> canReturnTo,
            boolean isDeprecated) {
    }

    record StageListResponse(List<StageView> data) {
    }

    record StageResponse(StageView data) {
    }

    record WorkflowTemplateListResponse(List<WorkflowTemplateView> data) {
    }

    /**
     * A create.
     *
     * <p>{@code slaHours} has a floor of {@code 0.01} rather than {@code 0},
     * because a zero-hour stage SLA is not "no SLA" — it is a stage that breaches
     * the instant it is entered, and every ticket that passes through it raises an
     * alert Stream D's scanner cannot suppress. <b>Absent means no SLA</b>, which
     * is what B-004 seeds on {@code DEV}: §4A.1 gives Development as "per SLA
     * policy", resolved from the project × task-type × level matrix instead.
     */
    record StageWrite(
            @NotBlank @Size(max = 20)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                    message = "Upper case, starting with a letter — DEV, QA, SIGNOFF.")
            String stageCode,

            @NotBlank @Size(max = 50) String displayName,
            @NotBlank @Size(max = 20) String ownerRole,

            @DecimalMin("0.01") @DecimalMax("9999.99") BigDecimal slaHours,

            Boolean isOptional,
            List<String> canReturnTo,
            @Size(max = 30) String icon) {
    }

    /**
     * A partial edit. Every field is nullable and null means "leave it alone".
     *
     * <p><b>{@code canReturnTo} is the one field where that convention has a
     * cost</b>, and it is paid rather than worked around: an empty list clears
     * every return target, and {@code null} leaves them, so a client that sends
     * {@code null} meaning "none" silently keeps them. The generated TypeScript
     * omits the key entirely when the form has not touched it, and
     * {@code stageForm.ts} sends {@code []} for a cleared control, so the two
     * cases stay distinguishable on the wire. There is no third state to encode.
     *
     * <p>{@code stageCode} is here <em>only</em> so that a caller sending a
     * different one gets 409 rather than silence. See {@link StageService#update}.
     */
    record StagePatch(
            @Size(max = 20)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                    message = "Upper case, starting with a letter — DEV, QA, SIGNOFF.")
            String stageCode,

            @Size(max = 50) String displayName,
            @Size(max = 20) String ownerRole,
            @DecimalMin("0.01") @DecimalMax("9999.99") BigDecimal slaHours,
            Boolean isOptional,
            List<String> canReturnTo,
            @Size(max = 30) String icon) {
    }

    /**
     * The whole ribbon, in order.
     *
     * <p>Every stage of the template exactly once. A partial list is refused
     * rather than interpreted — see {@link StageService#reorder}.
     */
    record StageOrder(List<Long> stageIds) {
    }
}
