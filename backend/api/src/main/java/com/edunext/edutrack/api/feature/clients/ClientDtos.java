package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
     * The column is {@code VARCHAR(20)} holding {@code ACTIVE|INACTIVE} and the
     * contract types the wire field as a boolean; the blueprint's Identity group
     * also names a third state, Prospect, which is B-026's field. Widening the
     * wire to a three-value enum would move a field Streams C and D already read,
     * so the mapping stays here and the contract stays as it was declared.
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
            long openTicketCount,
            Contact primaryContact,
            List<ProjectRef> projects,
            Instant lastTicketDate) {
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

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

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
