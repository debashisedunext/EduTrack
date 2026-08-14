package com.edunext.edutrack.api.feature.masters.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * B-018 · S-10 SLA tab wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on {@link SlaPolicyWrite} is the source of truth for the
 * field rules, per the note on {@link ProjectDtos}. The rules that need a query
 * — is this a real task type, is this a real level, is the same cell written
 * twice — are in {@link SlaMatrixService}, because none of them can be decided
 * from one field in isolation.
 */
final class SlaPolicyDtos {

    private SlaPolicyDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Which rung of the §6 ladder answered a cell.
     *
     * <p><b>The names are {@code SlaResolution.Source}'s and must stay that
     * way.</b> That enum is Stream C's, in {@code api.feature.tickets}, and it
     * is already on the wire through the planned-close-date preview: the create
     * form says "16 working hours, ORG_DEFAULT" and this screen has to say the
     * same words about the same cell, or an administrator comparing the two
     * screens is reading two vocabularies for one fact.
     *
     * <p>It is <b>copied rather than imported</b>, which is a real cost and the
     * cheaper of two. Importing it would make a masters DTO depend on a tickets
     * type — a stream boundary, for an enum — and {@code LayeringRulesTest}
     * exists to notice that sort of thing. Copying it means two lists that can
     * drift, so {@link SlaMatrixService} resolves nothing itself and
     * {@code SlaLadderAgreementTest} asserts every cell of a real grid against
     * {@code PlannedCloseDateService.resolve}, name included. A seventh rung
     * added to one and not the other fails that test rather than shipping.
     */
    enum Source {
        PROJECT_TASK_TYPE,
        PROJECT_LEVEL,
        ORG_DEFAULT,
        PRIORITY_DEFAULT,
        TASK_TYPE_DEFAULT,
        NONE,
    }

    /**
     * One cell of the resolved grid.
     *
     * <p>Every active task type × every priority level, whether or not this
     * project has a row for it. A response carrying only this project's
     * overrides would render as a nearly empty matrix for a project whose
     * tickets all get perfectly good planned close dates — which is not a
     * screen an administrator can act on.
     *
     * @param taskTypeName  denormalised so the grid can label its rows. There is
     *                      no mounted {@code /masters/task-types} yet (B-020),
     *                      and a matrix that renders "task type 7" is not a
     *                      screen. Same call B-017 made for
     *                      {@code TeamMember.displayName}
     * @param resolutionHrs null only when {@code source} is {@link Source#NONE}
     * @param escalateToL1  a <b>flag</b>: whether breach reaches the assignee's
     *                      reporting manager, not who that is. Blueprint §6
     *                      fixes who each level means and
     *                      {@code worker.sla.SlaEscalation} derives them from
     *                      the reporting chain
     * @param isOverride    {@code source == PROJECT_TASK_TYPE}. Derived, and on
     *                      the wire anyway: it is the one thing every client
     *                      branches on, and the {@code PUT} body is defined in
     *                      terms of it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SlaCell(
            int taskTypeId,
            String taskTypeCode,
            String taskTypeName,
            String level,
            BigDecimal responseHrs,
            BigDecimal resolutionHrs,
            boolean escalateToL1,
            boolean escalateToL2,
            Source source,
            boolean isOverride) {

        /** The cell's identity in the grid, and the key the {@code PUT} dedupes on. */
        record Key(int taskTypeId, String level) {
        }

        Key key() {
            return new Key(taskTypeId, level);
        }
    }

    /**
     * No {@code meta}, and that is the signal.
     *
     * <p>CONVENTIONS.md §6: an unpaginated collection returns {@code data} with
     * no {@code meta}, which is how a client knows the list is complete. The
     * exemption has been registered in {@code check-conventions.py} since D-002
     * with its reason — "task types x 4 levels" — and this is the operation that
     * finally makes it load-bearing rather than hypothetical.
     */
    record SlaMatrixResponse(List<SlaCell> data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * One project-level override. The {@code PUT} takes an array of these.
     *
     * <p><b>This is the contract corrected, not the contract followed.</b> Its
     * first draft carried {@code l1EscalationUserId} and
     * {@code l2EscalationUserId} — user ids the table has no column for, and
     * that {@code worker.sla.EscalationPolicies} explicitly does not want:
     * {@code escalate_to_l1} and {@code escalate_to_l2} are flags, because §6
     * fixes who each level means and storing a recipient here would put the
     * reporting chain in two places that can disagree. Nothing had ever called
     * the operation, so nothing caught it — the same "declared, mocked, never
     * mounted" gap B-017 found on {@code projectRole}.
     *
     * <p>{@code taskTypeId} is an {@code Integer}: {@code task_types.id} is an
     * {@code INT} and {@link com.edunext.edutrack.domain.masters.TaskType}
     * types it that way. The contract said {@code int64}, which would have
     * generated a TypeScript {@code number} either way and cost nothing until
     * somebody wrote a join.
     *
     * <p>{@code level} is a {@code String} and not an enum for
     * {@code PlannedCloseDateService}'s reason: S-12 lets an administrator add a
     * level without a release, and a Java enum makes that a code change. It is
     * checked against the priority master in {@link SlaMatrixService}.
     *
     * <p><b>{@code resolutionHrs} is {@code @Positive}, not
     * {@code @PositiveOrZero}.</b> {@code SlaResolution.hasTarget()} treats a
     * non-positive figure as no target at all, so a stored zero is a policy that
     * reads as configured on this screen and behaves as absent on every other —
     * the ticket drops out of the breach sweep, the pre-breach warning and the
     * delayed KPI with nothing saying so.
     *
     * <p>{@code escalateToL1} defaults to true and {@code escalateToL2} to
     * false, matching A-007's column defaults rather than inventing a pair here.
     * They are boxed so an omitted field can take the default instead of
     * arriving as Java's {@code false} — which for L1 is the opposite of what
     * the schema says and would quietly switch escalation off for every cell a
     * client sent without the field.
     */
    record SlaPolicyWrite(
            @NotNull(message = "taskTypeId is required")
            Integer taskTypeId,

            @NotBlank(message = "level is required")
            String level,

            @Positive(message = "responseHrs must be greater than zero")
            @DecimalMax(value = "9999.99", message = "responseHrs is out of range")
            BigDecimal responseHrs,

            @NotNull(message = "resolutionHrs is required")
            @Positive(message = "resolutionHrs must be greater than zero")
            @DecimalMax(value = "9999.99", message = "resolutionHrs is out of range")
            BigDecimal resolutionHrs,

            Boolean escalateToL1,
            Boolean escalateToL2) {

        boolean l1() {
            return escalateToL1 == null || escalateToL1;
        }

        boolean l2() {
            return escalateToL2 != null && escalateToL2;
        }

        SlaCell.Key key() {
            return new SlaCell.Key(taskTypeId == null ? 0 : taskTypeId, level);
        }
    }
}
