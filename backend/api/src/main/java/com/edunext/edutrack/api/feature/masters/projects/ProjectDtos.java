package com.edunext.edutrack.api.feature.masters.projects;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * B-016 · S-10 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request records is the single source of truth for
 * the field rules — the contract's {@code pattern} and {@code maxLength} and
 * these annotations describe the same constraint, and only one of them is the
 * one that actually runs. Rules that span two fields (a target end date before a
 * start date) or that need a query (a manager who does not exist) are in
 * {@link ProjectService}, because Bean Validation cannot express either.
 */
final class ProjectDtos {

    private ProjectDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The contract's {@code UserRef} — enough to name and link the project
     * manager, no more.
     *
     * <p>{@code NON_NULL} so a project whose manager row predates the mandatory
     * rule serialises without the key rather than with a null object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserRef(
            long id,
            String displayName,
            String role) {
    }

    /**
     * One row of the S-10 grid, and the shape five other screens' pickers have
     * always read.
     *
     * <p><b>{@code isActive} is derived from {@code status}, never stored.</b> It
     * is {@code status <> 'CLOSED'} — see {@link ProjectService#isActive} for why
     * that and not {@code status = 'ACTIVE'}.
     */
    record Project(
            long id,
            String projectCode,
            String name,
            String clientName,
            UserRef projectManager,
            String colourTag,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            boolean isActive,
            String autoAssignRule) {
    }

    record ProjectListResponse(List<Project> data, Meta meta) {
    }

    /** {@code totalCount} is omitted — the grid pages, and the count is not free. */
    record Meta(String nextCursor, boolean hasMore) {
    }

    /**
     * The edit form's read: everything the grid row carries, flattened with the
     * two fields only this screen needs.
     *
     * <p>Flat rather than nested because the contract declares it as
     * {@code allOf: [Project, {description, ticketsIssued}]}, which generates a
     * flat object; a nested {@code project} field would serialise to a shape the
     * generated client has no type for. Same reasoning as B-015's
     * {@code RoleDetail}.
     *
     * @param ticketsIssued {@code projects.ticket_seq}. Non-zero is what makes
     *                      {@code projectCode} immutable, and the form reads it
     *                      to disable the input and say why rather than letting
     *                      an admin type a new code and meet the refusal on save
     */
    record ProjectDetail(
            long id,
            String projectCode,
            String name,
            String clientName,
            String description,
            UserRef projectManager,
            String colourTag,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            boolean isActive,
            String autoAssignRule,
            long ticketsIssued) {
    }

    record ProjectDetailResponse(ProjectDetail data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Create. Every S-10 field, with the three the blueprint asterisks required.
     *
     * <p><b>{@code allowedTaskTypeIds} is deliberately absent.</b> The contract
     * carried it and nothing ever sent it; it is a join table rather than a
     * project column and belongs to B-019's Settings tab. Accepting it here
     * would have meant taking a list and storing nothing.
     *
     * <p>{@code ticketSeq} is absent for a harder reason: it is allocated by
     * {@code LAST_INSERT_ID(expr)} on the ticket write path (PLAN.md §3.2) and
     * nothing outside that statement may set it. See
     * {@link com.edunext.edutrack.domain.identity.Project#getTicketSeq()}.
     */
    record ProjectWrite(
            @NotBlank(message = "projectCode is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{1,9}$",
                    message = "projectCode must start with a letter and use only letters and digits, 2-10 characters")
            String projectCode,

            @NotBlank(message = "name is required")
            @Size(max = 150, message = "name must be at most 150 characters")
            String name,

            @Size(max = 150, message = "clientName must be at most 150 characters")
            String clientName,

            @Size(max = 1000, message = "description must be at most 1000 characters")
            String description,

            @NotNull(message = "projectManagerId is required")
            Long projectManagerId,

            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colourTag must be a #RRGGBB hex colour")
            String colourTag,

            LocalDate startDate,
            LocalDate endDate,
            String status,
            String autoAssignRule) {
    }

    /**
     * Edit. Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>{@code projectCode} is here, and {@code RolePatch.code} is not.</b>
     * That is not an inconsistency between two master screens: a role code is
     * immutable from creation, while a project code is freely editable right up
     * until the project issues its first ticket ID. So it has to be sendable —
     * and once the project is live, sending a <i>different</i> one is a 409
     * while sending the <i>same</i> one is a no-op, which it must be, because
     * S-10 submits the whole form on every save.
     *
     * <p>Boxed types throughout, so "omitted" and "explicitly null" stay
     * distinguishable. {@code LocalDate} and {@code String} are already
     * nullable; {@link ProjectService} reads
     * {@link com.fasterxml.jackson.databind.JsonNode} for neither, because the
     * only field where clearing and omitting differ in effect is one nobody
     * clears — see {@code applyTo}.
     */
    record ProjectPatch(
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{1,9}$",
                    message = "projectCode must start with a letter and use only letters and digits, 2-10 characters")
            String projectCode,

            @Size(min = 1, max = 150, message = "name must be 1-150 characters")
            String name,

            @Size(max = 150, message = "clientName must be at most 150 characters")
            String clientName,

            @Size(max = 1000, message = "description must be at most 1000 characters")
            String description,

            Long projectManagerId,

            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colourTag must be a #RRGGBB hex colour")
            String colourTag,

            LocalDate startDate,
            LocalDate endDate,
            String status,
            String autoAssignRule) {
    }
}
