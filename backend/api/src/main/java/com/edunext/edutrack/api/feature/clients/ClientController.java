package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * B-025 · S-32 — the Client Master list, per {@code contracts/openapi.yaml}.
 *
 * <h2>Permissions</h2>
 *
 * <ul>
 *   <li><b>Reads</b> — <b>all six roles</b>. Blueprint §4B.2's client dropdown on
 *       the ticket create form <em>is</em> {@code listClients}, every role may
 *       raise a ticket, and a role that could not list clients could not raise
 *       one against a client at all. {@code listClientContacts} carries the same
 *       argument: it is the dependent contact dropdown beside it.</li>
 *   <li><b>Writes</b> — <b>Admin only</b>, asserting {@code master.write}.</li>
 * </ul>
 *
 * <p><b>{@code master.write} rather than {@code project.manage}.</b> Blueprint §2
 * line 51 puts "Master data" at ✅ Admin and ❌ for the other five, and §7.4 heads
 * the module "Master data module (Admin only)" with the Client Master inside it.
 * B-018 had to argue this against {@code project.manage} because an SLA policy
 * hangs off a project; a client does not.
 *
 * <p>Asserting the capability rather than {@code hasRole('ADMIN')} is A-033's
 * rule and B-015's reason: a hard-coded role check would go on refusing a seventh
 * role that the Role Master had just granted the capability to.
 *
 * <p><b>403 and not 404 on the writes</b>, which looks like a breach of
 * CLAUDE.md's no-existence-leak rule and is not: clients are not row-scoped, and
 * every client is already public through {@code listClients} — which the ticket
 * form makes readable by all six roles. Recorded in {@code check-conventions.py}'s
 * {@code ROWLESS_403} with that reason.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally —
 * the mistake {@code MasterRoutesTest} exists to catch, and {@code ClientRoutesTest}
 * pins this controller for the same reason.
 */
@RestController
@RequestMapping("/api/v1/clients")
@Tag(name = "clients")
class ClientController {

    private final ClientService service;

    ClientController(ClientService service) {
        this.service = service;
    }

    /**
     * S-32's grid, and the §4B.2 ticket dropdown.
     *
     * <p>Every filter is optional and an absent one is not a filter — notably
     * {@code isActive}, whose absence returns retired clients too. The grid
     * cannot hide them: a ticket raised against a since-deactivated client still
     * has to render that client's name, which is the same rule B-020 states for
     * task types and B-064 for modules. The picker filters client-side.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listClients", summary = "List clients (S-32)")
    ClientDtos.ClientListResponse clients(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String supportPlan,
            @RequestParam(required = false) Long accountManagerId) {

        CursorPage<ClientDtos.Client> page = service.list(
                new ClientQueryRepository.Filter(q, isActive, projectId, supportPlan,
                        accountManagerId),
                cursor, limit);

        return new ClientDtos.ClientListResponse(page.data(), page.meta());
    }

    /**
     * The S-32 row-expand's contacts.
     *
     * <p>Not paged, and CONVENTIONS.md §6 exempts it: a client has a handful of
     * contacts and the expand renders all of them.
     */
    @GetMapping(path = "/{clientId}/contacts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listClientContacts", summary = "List contacts (S-32)")
    ClientDtos.ContactListResponse contacts(@PathVariable long clientId) {
        return new ClientDtos.ContactListResponse(
                service.contactsOf(clientId).orElseThrow(ClientController::notFound));
    }

    /**
     * S-32's bulk activate/deactivate.
     *
     * <p><b>Mapped ahead of {@code /{clientId}/status} in this file for
     * readability only.</b> Spring's {@code PathPatternParser} ranks the literal
     * segment above the variable regardless of declaration order — the same
     * ranking {@code /tickets/new} and {@code /masters/resources/new} already
     * depend on in {@code App.tsx}. And {@code clientId} binds as a {@code long},
     * so if a router ever did prefer the variable the request fails as a loud 400
     * rather than dispatching somewhere unintended.
     *
     * <p>No {@code If-Match}: an idempotent setter, like the single-client route
     * it batches, and one precondition tag cannot speak for two hundred rows.
     * Exempted in {@code check-conventions.py} with that reason.
     */
    @PatchMapping(path = "/bulk-status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "setClientStatusBulk",
            summary = "Activate or deactivate several clients at once (S-32)")
    ClientDtos.ClientListResponse bulkStatus(
            @Valid @RequestBody ClientDtos.BulkStatusRequest request) {

        // A complete list, not a page — the caller named every row in the body,
        // so there is nothing to resume. Null meta is the signal (PageMeta).
        return new ClientDtos.ClientListResponse(
                service.setStatusBulk(request.clientIds(), request.isActive()), null);
    }

    /**
     * Deactivating blocks <em>new</em> tickets and never hides historical ones —
     * blueprint §4B.2. The response carries {@code openTicketCount} so the caller
     * can warn with a number rather than in the abstract; enforcing the block is
     * B-029's, on the ticket create path.
     */
    @PatchMapping(path = "/{clientId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "setClientStatus", summary = "Activate or deactivate a client")
    ClientDtos.ClientResponse status(@PathVariable long clientId,
                                     @Valid @RequestBody ClientDtos.StatusRequest request) {

        return new ClientDtos.ClientResponse(
                service.setStatus(clientId, request.isActive())
                        .orElseThrow(ClientController::notFound));
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
