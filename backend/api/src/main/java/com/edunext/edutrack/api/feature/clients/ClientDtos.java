package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * B-025 · S-32 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Read-only but for the bulk status setter. The create/edit shapes are B-026's
 * and the contact writes are B-027's — this task is the grid.
 */
final class ClientDtos {

    private ClientDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The contract's {@code UserRef} — enough to name and link the account
     * manager, no more, exactly as {@code ProjectDtos.UserRef} carries a project
     * manager.
     *
     * <p>{@code NON_NULL} so a client with no account manager serialises without
     * the key rather than with a null object. The column is nullable and four of
     * the seeded clients predate anyone being assigned.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserRef(
            long id,
            String displayName,
            String role) {
    }

    /**
     * The contract's {@code ProjectRef}, for S-32's Projects column.
     *
     * <p>Three fields, not the whole project: this is inlined once per client
     * per page, so anything more is weight on every row of the grid.
     */
    record ProjectRef(
            long id,
            String projectCode,
            String name) {
    }

    /**
     * The contract's {@code Contact} — the shape the row-expand renders and the
     * §4B.2 reporter dropdown reads.
     */
    record Contact(
            long id,
            String name,
            String email,
            String phone,
            boolean isPrimary,
            boolean notificationOptIn,
            boolean portalAccess) {
    }

    /**
     * One row of the S-32 grid — blueprint line 946's nine columns.
     *
     * <p><b>{@code isActive} is derived from {@code status}, never stored.</b>
     * B-025 derived it as {@code status = 'ACTIVE'} while the column carried two
     * values; <b>B-026 added the third and had to widen it to
     * {@code status <> 'INACTIVE'}</b> — see {@link ClientStatus#isActive()}.
     * {@code status} itself is now on the wire beside it, additively and not
     * required, because a boolean cannot render S-32's status chip once Prospect
     * exists: a prospect and a contracted client are both active by that
     * projection and the grid would label them identically.
     *
     * @param openTicketCount tickets against this client whose status is not
     *                        terminal — the figure that makes a bulk deactivate
     *                        an informed decision rather than one whose size is
     *                        discovered afterwards, the call B-015 made with
     *                        {@code userCount} and B-020 with {@code ticketCount}
     * @param lastTicketDate  null for a client nothing has ever been raised
     *                        against, which is a real state and not a zero
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Client(
            long id,
            String clientCode,
            String name,
            String domain,
            UserRef accountManager,
            String supportPlan,
            Long slaPolicyId,
            String timezone,
            boolean isActive,
            String status,
            long openTicketCount,
            Contact primaryContact,
            List<ProjectRef> projects,
            Instant lastTicketDate) {
    }

    /**
     * B-026 · S-33's read — everything on {@link Client} plus the §4B.2 field
     * groups the grid has no column for.
     *
     * <p><b>A separate shape rather than more fields on {@code Client}.</b> The
     * list inlines one of those per client per page; twenty-five more fields on
     * it would be weight paid for by the grid, by §4B.2's ticket-form dropdown
     * and by {@code Client360Response}, none of which render them. The same split
     * {@code UserDetail}/{@code User} and {@code ProjectDetail}/{@code Project}
     * already make.
     *
     * <p><b>The {@code ETag} is taken over this record's {@code hashCode}</b>, so
     * every field below is part of the precondition — including
     * {@code contactCount}, which means a contact added in another tab costs the
     * form a reload. Correct rather than annoying: {@code hasPrimaryContact} is
     * B-028's gate, and a save made against a stale answer to "is this client
     * selectable yet" is exactly the state worth refusing.
     *
     * @param defaultProjectId  the {@code client_projects} row flagged
     *                          {@code is_default}, null where the client is
     *                          mapped but none is marked — the state every
     *                          imported client starts in
     * @param hasPrimaryContact B-028's gate, <b>reported and not enforced</b>.
     *                          Enforcing it lives on the ticket create path, the
     *                          way B-029's block on new tickets does
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ClientDetail(
            long id,
            String clientCode,
            String name,
            String domain,
            UserRef accountManager,
            String supportPlan,
            Long slaPolicyId,
            String timezone,
            boolean isActive,
            String status,
            long openTicketCount,
            Contact primaryContact,
            List<ProjectRef> projects,
            Instant lastTicketDate,
            // ── the S-33 fields the grid has no column for ────────────────
            String shortName,
            String logoUrl,
            String industry,
            String primaryEmail,
            String supportEmail,
            String phone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String country,
            String postalCode,
            LocalDate contractStart,
            LocalDate contractEnd,
            String billingReference,
            String billingEmail,
            String notes,
            List<String> tags,
            Long defaultProjectId,
            int contactCount,
            boolean hasPrimaryContact) {
    }

    /**
     * {@code meta} is null on a complete list and populated on a page — A-053's
     * {@link PageMeta} deliberately carries no {@code totalCount}, so nothing
     * here counts the whole predicate to fill one in.
     */
    record ClientListResponse(List<Client> data, PageMeta meta) {
    }

    record ContactListResponse(List<Contact> data) {
    }

    record ClientResponse(Client data) {
    }

    record ClientDetailResponse(ClientDetail data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * B-026 · S-33's four tabs, in one body.
     *
     * <p><b>One shape for {@code POST} and {@code PATCH}.</b> S-33 submits every
     * field on every save, and <b>no client field is immutable</b> — which is the
     * whole reason this differs from the project master next door, where B-016
     * had to introduce a separate {@code ProjectPatchRequest} because requiring
     * an immutable {@code projectCode} on a patch would have made every edit to a
     * live project a 409. A client code is freely editable; there is nothing to
     * keep off the patch shape.
     *
     * <p><b>It is a record, and B-017's and B-020's argument for a POJO does not
     * apply.</b> Those two needed absent and explicitly-null to differ, because a
     * sparse patch had to be able to clear one field without wiping another. This
     * body is the whole representation: a field the form did not send is a field
     * the admin cleared, and "absent means clear" is the correct reading rather
     * than an accident of Jackson's record binding.
     *
     * <p><b>{@code projectIds} is the one exception</b>, and it is a
     * {@code List} rather than an {@code Optional<List>} for the same reason:
     * null means "leave the mapping alone", an empty list means "unmap
     * everything". Both are things a caller means, and B-035's import will send
     * neither. Stated in the contract and pinned by a test rather than left to be
     * inferred from a null check.
     */
    record ClientWriteRequest(
            // ── Identity ──────────────────────────────────────────────────
            @NotBlank(message = "clientCode is required")
            @Size(min = 2, max = 20, message = "clientCode must be 2–20 characters")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
                    message = "clientCode may contain letters, digits, hyphens and underscores, "
                            + "and must start with a letter or digit")
            String clientCode,

            @NotBlank(message = "name is required")
            @Size(max = 150, message = "name cannot exceed 150 characters")
            String name,

            @Size(max = 60, message = "shortName cannot exceed 60 characters")
            String shortName,

            @Size(max = 500, message = "logoUrl cannot exceed 500 characters")
            String logoUrl,

            @Size(max = 80, message = "industry cannot exceed 80 characters")
            String industry,

            /*
             * Not @Pattern'd here. The vocabulary lives in ClientStatus so one
             * enum answers for the contract, the CHECK constraint and the
             * service — B-017's ProjectRoles argument, one feature over: a
             * @Pattern is a fifth copy of a list nothing re-checks against the
             * other four.
             */
            String status,

            @Size(max = 120, message = "domain cannot exceed 120 characters")
            String domain,

            @Email(message = "primaryEmail must be a well-formed email address")
            @Size(max = 150, message = "primaryEmail cannot exceed 150 characters")
            String primaryEmail,

            @Email(message = "supportEmail must be a well-formed email address")
            @Size(max = 150, message = "supportEmail cannot exceed 150 characters")
            String supportEmail,

            @Size(max = 30, message = "phone cannot exceed 30 characters")
            String phone,

            // ── Address ───────────────────────────────────────────────────
            @Size(max = 150, message = "addressLine1 cannot exceed 150 characters")
            String addressLine1,

            @Size(max = 150, message = "addressLine2 cannot exceed 150 characters")
            String addressLine2,

            @Size(max = 80, message = "city cannot exceed 80 characters")
            String city,

            @Size(max = 80, message = "state cannot exceed 80 characters")
            String state,

            @Size(max = 80, message = "country cannot exceed 80 characters")
            String country,

            @Size(max = 20, message = "postalCode cannot exceed 20 characters")
            String postalCode,

            @Size(max = 50, message = "timezone cannot exceed 50 characters")
            String timezone,

            // ── Commercial ────────────────────────────────────────────────
            Long accountManagerId,
            LocalDate contractStart,
            LocalDate contractEnd,
            String supportPlan,

            @Size(max = 60, message = "billingReference cannot exceed 60 characters")
            String billingReference,

            @Email(message = "billingEmail must be a well-formed email address")
            @Size(max = 150, message = "billingEmail cannot exceed 150 characters")
            String billingEmail,

            // ── Notes ─────────────────────────────────────────────────────
            String notes,

            @Size(max = 20, message = "tags cannot exceed 20 entries")
            List<@NotBlank(message = "a tag cannot be blank")
                 @Size(max = 40, message = "a tag cannot exceed 40 characters") String> tags,

            // ── Projects & SLA ────────────────────────────────────────────
            List<Long> projectIds,
            Long defaultProjectId,
            Long slaPolicyId) {
    }

    /** The single-client setter's body. Idempotent — CONVENTIONS.md §5. */
    record StatusRequest(
            @NotNull(message = "isActive is required")
            Boolean isActive) {
    }

    /**
     * S-32's bulk activate/deactivate.
     *
     * <p>Bounded at 200, the same ceiling {@code Limit} puts on a page, so a
     * selection cannot exceed what one page could have offered. An unbounded id
     * list is an unbounded {@code IN (…)}.
     */
    record BulkStatusRequest(
            @NotEmpty(message = "clientIds must name at least one client")
            @Size(max = 200, message = "clientIds cannot exceed 200 clients in one request")
            List<Long> clientIds,

            @NotNull(message = "isActive is required")
            Boolean isActive) {
    }
}
