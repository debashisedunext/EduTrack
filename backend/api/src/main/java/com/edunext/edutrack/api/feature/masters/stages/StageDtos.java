package com.edunext.edutrack.api.feature.masters.stages;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
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
     *
     * <p><b>{@code isDeletable} is the second such field, and B-042 added it for
     * the identical reason.</b> The rule behind it is not one count but four
     * conditions — nothing has ever entered the stage, nothing stands in it now,
     * no live sibling returns to it, and it is not the template's last live stage
     * — and three of those are facts about <em>other rows</em>. A client deriving
     * it from the array it happens to hold would be right until it held a filtered
     * one.
     *
     * <p>{@code isDeprecated} carries no such computation: it is the column.
     * {@code deprecatedAt} is beside it because "when did we stop using this?" has
     * no other answer — the last hop into a stage records when it was last
     * <em>used</em>, which is a different date.
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
            boolean isCodeEditable,
            boolean isDeprecated,
            Instant deprecatedAt,
            boolean isDeletable) {

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
                    String.valueOf(openTicketCount),
                    // B-042. The DELETE preconditions on this tag and its whole
                    // guard is that the two counts above are zero — so a stage
                    // retired, restored or entered while the dialog sits open has
                    // to lose the race rather than be removed on evidence that
                    // stopped being true.
                    String.valueOf(isDeprecated));
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
     * <p><b>{@code isDeprecated} is served from the column as of B-042</b>, and it
     * had been a hard-coded {@code false} since D-001 with nothing behind it. The
     * field mattered before the column did: {@code TicketListPage} has skipped
     * deprecated codes when building S-25's stage filter since C-013, so the
     * branch was written, shipped and unreachable. This is the task that makes it
     * do something.
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

    /**
     * Retire a stage, or bring it back — B-042.
     *
     * <p><b>Its own route rather than a field on {@link StagePatch}</b>, and the
     * reason is the patch's own convention: null means "leave it alone" there, so
     * a boolean would have three states on the wire for a column that has two, and
     * the one write in this package with a consequence for live tickets would
     * arrive indistinguishable from a display-name edit. §4B.2's client status and
     * S-08's user status are both separate setters for the same reason.
     *
     * <p>{@code Boolean} rather than {@code boolean} so that an absent field is a
     * 400 rather than a silent restore. {@code @NotNull} says which.
     */
    record StageDeprecation(@NotNull Boolean isDeprecated) {
    }
}
