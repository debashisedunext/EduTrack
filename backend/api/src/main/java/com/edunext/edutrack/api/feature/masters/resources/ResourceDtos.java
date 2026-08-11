package com.edunext.edutrack.api.feature.masters.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * B-010 · the wire shapes for S-07, matching {@code contracts/openapi.yaml}
 * §users.
 *
 * <p>Grouped in one file for the reason {@code CalendarDtos} gives: they are one
 * contract read together.
 *
 * <p><b>{@code @JsonInclude(NON_NULL)} on every response record is load-bearing,
 * not tidiness.</b> The generated Zod types most of these fields
 * {@code .optional()} rather than {@code .nullish()} — {@code role},
 * {@code username}, {@code isActive}, {@code projects} among them — so an
 * explicit {@code null} on the wire is a value the frontend's own schema
 * rejects, and the grid would fail to parse a response the backend considers
 * perfectly valid. Omitting the key is what {@code .optional()} means.
 */
public final class ResourceDtos {

    private ResourceDtos() {
    }

    // ------------------------------------------------------------------
    // The grid
    // ------------------------------------------------------------------

    /**
     * {@code ProjectRef} — the label half of a project.
     *
     * <p>Names, not ids. S-07's Projects column would otherwise need a second
     * request per row, or a client-side join against a project list the grid has
     * no other reason to fetch.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProjectRef(
            long id,
            String projectCode,
            String name,
            String colourTag) {
    }

    /**
     * {@code UserRef} — enough to name and link a person, no more.
     *
     * <p>Used here for {@code reportingManager}. {@code avatarUrl} and
     * {@code handle} are left null and dropped by {@code NON_NULL}: neither is
     * stored on {@code users} today, and inventing a value would be worse than
     * omitting an optional field.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRef(
            long id,
            String displayName,
            String role) {
    }

    /**
     * {@code User} — one row of the S-07 grid.
     *
     * <p>Carries every column blueprint §7.4 lists: emp code, name, email, role,
     * department, reporting manager, projects, status, last login. {@code id} and
     * {@code displayName} come from the contract's {@code UserRef} base, which is
     * why they lead.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(
            long id,
            String displayName,
            String role,
            String username,
            String email,
            String employeeCode,
            String department,
            String designation,
            UserRef reportingManager,
            List<Long> projectIds,
            List<ProjectRef> projects,
            Boolean isActive,
            Integer openTicketCount,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    /** {@code Meta} — cursor pagination, per CONVENTIONS.md §6. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String nextCursor, boolean hasMore, Long totalCount) {
    }

    public record ResourceListResponse(List<Resource> data, Meta meta) {
    }

    // ------------------------------------------------------------------
    // Bulk activate / deactivate
    // ------------------------------------------------------------------

    /**
     * {@code BulkUserStatusRequest}.
     *
     * <p>{@code isActive} is a boxed {@link Boolean} so that omitting it is a 400
     * naming the field rather than a silent {@code false} — which would
     * deactivate the whole selection on a malformed request.
     */
    public record BulkStatusRequest(
            @NotEmpty(message = "select at least one resource")
            @Size(max = 200, message = "a selection is made on a page; 200 is the page maximum")
            List<@NotNull Long> userIds,

            @NotNull(message = "say which way to set them")
            Boolean isActive,

            @Size(max = 500)
            String reason) {
    }

    /**
     * What happened to one resource.
     *
     * <p>Four outcomes rather than a boolean because the caller renders all four
     * differently, and collapsing {@code UNCHANGED} into {@code CHANGED} would
     * make the summary line overstate the work done.
     */
    public enum BulkStatusOutcomeCode {

        /** The flag moved. */
        CHANGED,

        /** Already in the requested state. Not an error, and not a change. */
        UNCHANGED,

        /**
         * Holds open tickets and was being deactivated. Left active — the
         * reassignment wizard (B-014) runs first, through
         * {@code POST /tickets/bulk-reassign}.
         */
        BLOCKED_OPEN_TICKETS,

        /** No such resource. Deleted between the grid rendering and the click. */
        NOT_FOUND
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkStatusOutcome(
            long userId,
            String displayName,
            BulkStatusOutcomeCode outcome,
            Integer openTicketCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BulkStatusData(
            List<BulkStatusOutcome> results,
            int changed,
            int unchanged,
            int blocked,
            int notFound,
            String reassignUrl) {
    }

    public record BulkStatusResponse(BulkStatusData data) {
    }
}
