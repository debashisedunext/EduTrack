package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.CursorPage;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ClientWriteService writes;
    private final ClientContactService contacts;

    ClientController(ClientService service,
                     ClientWriteService writes,
                     ClientContactService contacts) {
        this.service = service;
        this.writes = writes;
        this.contacts = contacts;
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
     * B-026 · the S-33 form's read, and <b>the only source of the {@code ETag}
     * the {@code PATCH} requires</b>.
     *
     * <p>Its absence is what made {@code updateClient} uncallable: the contract
     * has declared {@code If-Match} on that operation since D-001 with no read
     * emitting a tag. Exactly the gap B-011 closed with
     * {@code GET /users/{userId}} and B-016 with {@code GET /projects/{id}} —
     * CONVENTIONS.md §5 pairs the two by hand because
     * {@code check-conventions.py} cannot: its detail-read rule fires on the
     * <em>path shape</em>, and this path had the right shape and the wrong half
     * of the pair.
     *
     * <p>All six roles, like the list beside it. §4B.2's ticket-form dropdown is
     * {@code listClients}; a role that can enumerate every client learns nothing
     * new by reading one.
     */
    @GetMapping(path = "/{clientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getClient", summary = "One client (S-33)")
    ResponseEntity<ClientDtos.ClientDetailResponse> get(@PathVariable long clientId) {
        return ok(service.findDetail(clientId).orElseThrow(ClientController::notFound));
    }

    /**
     * The S-32 row-expand's contacts, S-33's Contacts tab and §4B.2's reporter
     * dropdown.
     *
     * <p>Not paged, and CONVENTIONS.md §6 exempts it: a client has a handful of
     * contacts and every caller renders all of them.
     *
     * <p><b>{@code includeInactive} defaults to false.</b> B-027 removes a
     * contact by deactivating it, so the default is what keeps a departed contact
     * out of the ticket form's reporter dropdown; the grid that administers them
     * asks for the rest. Exactly the split B-021 made on {@code listPriorities}.
     */
    @GetMapping(path = "/{clientId}/contacts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listClientContacts", summary = "List contacts (S-32)")
    ClientDtos.ContactListResponse contacts(
            @PathVariable long clientId,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {

        return new ClientDtos.ContactListResponse(
                contacts.list(clientId, includeInactive).orElseThrow(ClientController::notFound));
    }

    // ------------------------------------------------------------------
    // B-027 · S-33's Contacts tab — the client_contacts child grid
    // ------------------------------------------------------------------

    /**
     * S-33's Contacts tab, adding — and the seventh "declared, mocked, never
     * mounted" operation this stream has found.
     *
     * <p>It has been in the contract, in the MSW mock and in the generated
     * TypeScript client since D-001 with no server behind it, after B-023's nine
     * calendar operations, B-014's {@code PATCH /users/{userId}/status}, B-018's
     * two SLA operations, B-020's {@code listTaskTypes}, B-021's
     * {@code listPriorities} and B-025's six client operations. B-026's own
     * Contacts tab named it as B-027's and shipped read-only rather than pretend
     * otherwise.
     *
     * <p>Admin only, where the read above is every role — the split B-025 and
     * B-026 already make on this resource, and for the same §2 row 51 reason.
     */
    @PostMapping(path = "/{clientId}/contacts",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createClientContact", summary = "Add a contact")
    ResponseEntity<ClientDtos.ContactResponse> addContact(
            @PathVariable long clientId,
            @Valid @RequestBody ClientDtos.ContactWriteRequest request) {

        ClientDtos.Contact created = contacts.add(clientId, request)
                .orElseThrow(ClientController::notFound);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientDtos.ContactResponse(created));
    }

    /**
     * S-33's Contacts tab, editing.
     *
     * <p><b>Without it an edit is remove-and-re-add</b>, which deactivates the
     * row a historical ticket points at and issues a new id: a corrected phone
     * number rendered as a departure and an arrival. The same argument B-017 made
     * for adding {@code PATCH /projects/{id}/members/{userId}}, where the missing
     * verb would have reset {@code added_at}.
     *
     * <p><b>No {@code If-Match}</b>, exempted in {@code check-conventions.py} with
     * its reason: the tag would have to come from {@code listClientContacts}, a
     * collection with no {@code ETag} of its own — the
     * {@code /projects/{id}/members/{userId}} call, unchanged. The client this
     * contact hangs off <em>does</em> have one and a write here moves it, since
     * {@code contactCount} and {@code hasPrimaryContact} are inside it, so the
     * S-33 form's own precondition still catches a stale editor one level up.
     */
    @PatchMapping(path = "/{clientId}/contacts/{contactId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateClientContact", summary = "Edit a contact (S-33)")
    ClientDtos.ContactResponse editContact(
            @PathVariable long clientId,
            @PathVariable long contactId,
            @Valid @RequestBody ClientDtos.ContactWriteRequest request) {

        return new ClientDtos.ContactResponse(
                contacts.edit(clientId, contactId, request)
                        .orElseThrow(ClientController::notFound));
    }

    /**
     * S-33's Contacts tab, removing — which deactivates.
     *
     * <p>{@code tickets.client_contact_id} is a foreign key with no cascade, so a
     * real delete would fail as a constraint violation naming a MySQL index, or —
     * "fixed" with a cascade — destroy the record of who reported a historical
     * ticket. See {@code ClientContactWriteRepository}.
     *
     * <p><b>204 for a contact that was already removed</b>, 404 only for one that
     * is not this client's. A setter's second call is not an error about
     * something that did happen — B-014's {@code UNCHANGED} argument, and B-017's
     * on removing somebody who is not on the team.
     */
    @DeleteMapping(path = "/{clientId}/contacts/{contactId}")
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "removeClientContact", summary = "Remove a contact (S-33)")
    ResponseEntity<Void> removeContact(@PathVariable long clientId,
                                       @PathVariable long contactId) {

        if (!contacts.remove(clientId, contactId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
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

    // ------------------------------------------------------------------
    // B-026 · S-33's create and edit
    // ------------------------------------------------------------------

    /**
     * S-33, creating.
     *
     * <p><b>A created client is not yet selectable on a ticket</b>, and cannot
     * be: B-028's rule is at least one primary contact, and there is no client id
     * to hang a contact off until this returns. {@code hasPrimaryContact} on the
     * response says so and the form's Contacts tab says so on screen; the
     * contacts themselves are {@code POST /clients/{clientId}/contacts}, which is
     * B-027's.
     *
     * <p>The {@code ETag} is emitted on the 201 so the form can go straight from
     * creating a client to editing it without a second read.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createClient", summary = "Create a client (S-33)")
    ResponseEntity<ClientDtos.ClientDetailResponse> create(
            @Valid @RequestBody ClientDtos.ClientWriteRequest request) {

        ClientDtos.ClientDetail created = writes.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(etagOf(created))
                .body(new ClientDtos.ClientDetailResponse(created));
    }

    /**
     * S-33, saving.
     *
     * <p>The body is the whole representation rather than a sparse patch — S-33
     * submits every field on every save — and the verb stays {@code PATCH}
     * because that is what the contract has declared since D-001 and what the
     * generated client emits. See {@code ClientDtos.ClientWriteRequest} for why
     * one shape serves both verbs where the project master needed two.
     */
    @PatchMapping(path = "/{clientId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateClient", summary = "Update a client (S-33)")
    ResponseEntity<ClientDtos.ClientDetailResponse> update(
            @PathVariable long clientId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ClientDtos.ClientWriteRequest request) {

        requirePrecondition(clientId, ifMatch);
        return ok(writes.update(clientId, request).orElseThrow(ClientController::notFound));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is 428 rather than allowed through: treating a
     * missing precondition as "no conflict" means the guard protects only the
     * callers that already opted in, which is the set that needed it least. Same
     * reasoning and the same status as B-011's resource form, B-016's project
     * form and B-023's working week.
     *
     * <p><b>The 404 comes first.</b> Answering 428 for a client that does not
     * exist would send the caller to fetch a tag from a URL that will 404 too.
     */
    private void requirePrecondition(long clientId, String ifMatch) {
        ClientDtos.ClientDetail current = service.findDetail(clientId)
                .orElseThrow(ClientController::notFound);

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the client first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This client changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<ClientDtos.ClientDetailResponse> ok(
            ClientDtos.ClientDetail client) {

        return ResponseEntity.ok()
                .eTag(etagOf(client))
                .body(new ClientDtos.ClientDetailResponse(client));
    }

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp tag moves when a save rewrites identical values, failing an
     * edit that conflicts with nothing. Content-derived, two people who saved the
     * same change do not fight.
     *
     * <p><b>{@code contactCount} and {@code hasPrimaryContact} are part of the
     * record and therefore part of the tag</b>, so a contact added in another tab
     * costs this form a reload. That is right rather than annoying:
     * {@code hasPrimaryContact} is B-028's gate, and a save made against a stale
     * answer to "is this client selectable yet" is exactly the state worth
     * refusing.
     *
     * <p><b>A 32-bit hash, and two states of one client can collide.</b> B-019
     * found this the honest way on {@code ProjectSettings} — two booleans moving
     * in opposite directions at the same multiplier in a record's
     * {@code hashCode} cancel exactly — and {@code ProjectController} and
     * {@code SlaPolicyController} tag the same way. Recorded here rather than
     * fixed on one screen: a stronger tag across all four is a change worth
     * making together.
     */
    private static String etagOf(ClientDtos.ClientDetail client) {
        return Integer.toHexString(client.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /** 404, never 403, for a row that is not there — CLAUDE.md's rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
