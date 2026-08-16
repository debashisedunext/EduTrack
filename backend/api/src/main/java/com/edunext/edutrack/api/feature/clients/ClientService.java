package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-025 · S-32, the Client Master list.
 *
 * <p>The data layer was already here — the {@code clients}, {@code client_contacts}
 * and {@code client_projects} tables came with
 * {@code V20260805_1530__baseline_clients_content.sql} and B-005 mapped them.
 * The six client operations have been in {@code contracts/openapi.yaml}, in the
 * MSW mock and in the generated TypeScript client since D-001 <b>with no server
 * behind them</b> — the sixth instance of "declared, mocked, never mounted" this
 * stream has found, after B-023's nine calendar operations, B-014's
 * {@code PATCH /users/{userId}/status}, B-018's two SLA operations, B-020's
 * {@code listTaskTypes} and B-021's {@code listPriorities}. Nothing failed,
 * because every screen that calls them has only ever run against the mock.
 *
 * <h2>There is no delete, and that is the design</h2>
 *
 * <p>{@code tickets.client_id}, {@code client_contacts.client_id} and
 * {@code client_projects.client_id} all point here. Deleting a client would
 * either fail on a foreign key or, with a cascade, silently destroy the record of
 * work done for them. Blueprint §4B.2 states the rule directly: deactivating
 * warns and blocks <em>new</em> tickets, and <b>never hides historical ones</b>.
 * Going away is {@code status = 'INACTIVE'}, which is the same call B-020 made on
 * a retired task type and B-042 will make on a stage in use.
 *
 * <p>This class therefore exposes no {@code delete}. The one write it has flips a
 * status, and the ticket-blocking half of B-029's rule belongs to B-029 — it
 * lives on the ticket create path, not here, because this screen is not what a
 * ticket is raised through. What this task owes that decision is the number it is
 * made against, which is why {@code openTicketCount} is on every row.
 */
@Service
public class ClientService {

    /**
     * The two values B-025 wrote. The vocabulary itself is {@link ClientStatus},
     * which B-026 widened with {@code PROSPECT} — these stay as the two the
     * status setter can produce, and they are its names for them.
     */
    static final String ACTIVE = ClientStatus.ACTIVE.name();

    static final String INACTIVE = ClientStatus.INACTIVE.name();

    private final ClientRepository clients;
    private final ClientQueryRepository query;

    ClientService(ClientRepository clients, ClientQueryRepository query) {
        this.clients = clients;
        this.query = query;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One keyset page of the S-32 grid, under the four filters blueprint line
     * 946 names plus the contract's free-text {@code q}.
     *
     * <p>Ordered by name then id. The page is fetched one row longer than asked
     * for and {@link CursorPage#of} consumes the extra — the boundary is A-053's
     * to get right, not this method's.
     */
    @Transactional(readOnly = true)
    public CursorPage<ClientDtos.Client> list(ClientQueryRepository.Filter filter,
                                              String cursor,
                                              Integer requestedLimit) {

        int limit = PageLimit.clamp(requestedLimit);
        List<ClientQueryRepository.Row> rows =
                query.page(filter, Cursor.decode(cursor), PageLimit.fetchSize(limit));

        // Aggregates for the whole page, including the extra row — cheaper than
        // deciding which rows survive first and then issuing a second round.
        List<ClientDtos.Client> views = attachAggregates(rows);
        return CursorPage.of(views, limit, c -> new Cursor(c.name(), c.id()));
    }

    /** One client, for the status setter's response and the row-expand's header. */
    @Transactional(readOnly = true)
    public Optional<ClientDtos.Client> find(long clientId) {
        return attachAggregates(query.byIds(List.of(clientId))).stream().findFirst();
    }

    /**
     * B-026 · one client with the whole §4B.2 field set — the S-33 form's read,
     * and the only source of the {@code ETag} its {@code PATCH} requires.
     *
     * <p>Built from the entity plus {@link #find}'s aggregates rather than from a
     * second wide {@code SELECT}. The grid's four aggregates are exactly what the
     * detail needs too — projects, open tickets, last ticket date, primary
     * contact — and computing them twice from two statements is how the form and
     * the row it was opened from start disagreeing about the same client.
     */
    @Transactional(readOnly = true)
    public Optional<ClientDtos.ClientDetail> findDetail(long clientId) {
        Optional<Client> entity = clients.findById(clientId);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        Optional<ClientDtos.Client> row = find(clientId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDetail(entity.get(), row.get()));
    }

    /*
     * B-027 · `contactsOf` moved to ClientContactService and is not duplicated
     * here.
     *
     * B-025 put the read on this class because there was nothing else for it to
     * live on. Now that the contact writes exist, keeping a second reader beside
     * them would mean two classes deciding what a client's contacts are — and
     * they would disagree the first time one honours `includeInactive` and the
     * other does not, which is precisely the drift `ProjectRoles` (B-017) and
     * `PasswordComplexity` (B-013) exist one package over to prevent. The
     * aggregates this class does still read — `primaryContacts`,
     * `contactSummary` — are grid columns, not the contact list.
     */

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Activate or deactivate one client. Idempotent — setting the state it
     * already holds is a 200, not a conflict.
     */
    @Transactional
    public Optional<ClientDtos.Client> setStatus(long clientId, boolean isActive) {
        Optional<Client> found = clients.findById(clientId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // B-026 · not `isActive ? ACTIVE : INACTIVE`. A Prospect is already
        // active by ClientStatus.isActive(), so writing ACTIVE would let this
        // route — and S-32's bulk Activate through it — silently promote a
        // prospect into a contracted client. ClientStatus.activatedFrom holds
        // the rule so the single and bulk setters cannot drift.
        found.get().setStatus(ClientStatus.activatedFrom(found.get().getStatus(), isActive));
        // Flushed for the reason setStatusBulk gives: the response is read back
        // through JdbcClient, which Hibernate's auto-flush does not see coming.
        clients.saveAndFlush(found.get());
        return find(clientId);
    }

    /**
     * S-32's bulk activate/deactivate.
     *
     * <p><b>An unknown id fails the whole request rather than being skipped.</b>
     * One transaction, and the check happens before anything is written. A caller
     * who selected fifty rows and silently changed forty-nine has been told the
     * bulk action succeeded, and there is nothing in the response that says
     * otherwise — the partial-success story is exactly what this endpoint exists
     * to avoid, and reproducing it inside one request would be worse than the
     * fifty separate calls it replaces.
     *
     * <p>Duplicate ids in the body are collapsed rather than refused. The same
     * client named twice is one row to write, and a caller assembling a selection
     * from two pages should not have to de-duplicate to be allowed to act.
     *
     * @throws UnknownClientException naming every id that does not exist, so the
     *                                caller fixes all of them in one round rather
     *                                than one per retry
     */
    @Transactional
    public List<ClientDtos.Client> setStatusBulk(List<Long> clientIds, boolean isActive) {
        Set<Long> ids = new LinkedHashSet<>(clientIds);
        List<Client> found = clients.findAllById(ids);

        if (found.size() != ids.size()) {
            Set<Long> present = new LinkedHashSet<>();
            found.forEach(c -> present.add(c.getId()));
            List<Long> missing = ids.stream().filter(id -> !present.contains(id)).toList();
            throw new UnknownClientException(missing);
        }

        // Per row, not one status for the batch: a selection of forty containing
        // three prospects must come back with three prospects. See
        // ClientStatus.activatedFrom.
        found.forEach(c -> c.setStatus(ClientStatus.activatedFrom(c.getStatus(), isActive)));
        // Flushed, not merely saved. The response is assembled by
        // ClientQueryRepository through JdbcClient, which issues SQL outside the
        // EntityManager — and Hibernate's AUTO flush only triggers ahead of
        // queries it can see overlap the dirty entities, which a raw JDBC read
        // is not. Without the flush this returns the statuses as they were
        // *before* the write, on the one call whose whole purpose is to report
        // what changed.
        clients.saveAllAndFlush(found);

        return attachAggregates(query.byIds(ids));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Attaches the four S-32 columns that are not on the client row.
     *
     * <p>Four queries for the page, whatever its size — never four per row. An
     * N+1 over a grid of clients is invisible at the four seeded rows and is not
     * invisible at the five thousand B-032's import allows.
     */
    private List<ClientDtos.Client> attachAggregates(List<ClientQueryRepository.Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        rows.forEach(r -> ids.add(r.id()));

        Map<Long, Long> openCounts = query.openTicketCounts(ids);
        Map<Long, Instant> lastDates = query.lastTicketDates(ids);
        Map<Long, List<ClientDtos.ProjectRef>> projects = query.projectsByClient(ids);
        Map<Long, ClientDtos.Contact> primaries = query.primaryContacts(ids);

        List<ClientDtos.Client> views = new ArrayList<>(rows.size());
        for (ClientQueryRepository.Row row : rows) {
            views.add(new ClientDtos.Client(
                    row.id(),
                    row.clientCode(),
                    row.name(),
                    row.domain(),
                    toManager(row),
                    row.supportPlan(),
                    row.slaPolicyId(),
                    row.timezone(),
                    // B-026 · `status <> 'INACTIVE'`, not `status = 'ACTIVE'`.
                    // ClientStatus carries the argument: the narrow reading
                    // removes every Prospect from §4B.2's ticket-form dropdown,
                    // which filters on this boolean.
                    ClientStatus.isActive(row.status()),
                    row.status(),
                    openCounts.getOrDefault(row.id(), 0L),
                    primaries.get(row.id()),
                    projects.getOrDefault(row.id(), List.of()),
                    lastDates.get(row.id())));
        }
        return views;
    }

    /**
     * B-026 · the grid row widened to the S-33 form's field set.
     *
     * <p>Package-private and not private: {@code ClientWriteService} assembles
     * its own response through this after a save, so a create and a read of the
     * client just created cannot describe it differently.
     *
     * <p>{@code tags} is normalised to an empty list rather than left null. The
     * column is nullable and the form binds an array; a null would arrive as
     * {@code undefined} and the first render of a client that has never been
     * tagged would throw on {@code .map}.
     */
    ClientDtos.ClientDetail toDetail(Client entity, ClientDtos.Client row) {
        ClientQueryRepository.ContactSummary contacts = query.contactSummary(entity.getId());
        // Read through JdbcClient, not off the entity. `hibernate.jdbc.time_zone`
        // makes JPA read a DATE a day early — measured, not suspected; the whole
        // account is on ClientQueryRepository.contractDates. Everything else on
        // this record comes off the entity, which is correct for every type but
        // this one.
        ClientQueryRepository.ContractDates contract = query.contractDates(entity.getId());

        return new ClientDtos.ClientDetail(
                row.id(),
                row.clientCode(),
                row.name(),
                row.domain(),
                row.accountManager(),
                row.supportPlan(),
                row.slaPolicyId(),
                row.timezone(),
                row.isActive(),
                row.status(),
                row.openTicketCount(),
                row.primaryContact(),
                row.projects(),
                row.lastTicketDate(),
                entity.getShortName(),
                entity.getLogoUrl(),
                entity.getIndustry(),
                entity.getPrimaryEmail(),
                entity.getSupportEmail(),
                entity.getPhone(),
                entity.getAddressLine1(),
                entity.getAddressLine2(),
                entity.getCity(),
                entity.getState(),
                entity.getCountry(),
                entity.getPostalCode(),
                contract.start(),
                contract.end(),
                entity.getBillingReference(),
                entity.getBillingEmail(),
                entity.getNotes(),
                entity.getTags() == null ? List.of() : List.copyOf(entity.getTags()),
                query.defaultProjectId(entity.getId()),
                contacts.count(),
                contacts.hasPrimary());
    }

    /**
     * Null when the client has no account manager, which is a real state — the
     * column is nullable and a freshly imported client has nobody on it yet.
     * {@code UserRef} is {@code NON_NULL}, so the key is absent rather than a
     * null object.
     */
    private static ClientDtos.UserRef toManager(ClientQueryRepository.Row row) {
        if (row.accountManagerId() == null) {
            return null;
        }
        return new ClientDtos.UserRef(
                row.accountManagerId(), row.accountManagerName(), row.accountManagerRole());
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /** Raised by the bulk setter; rendered as a 404 by {@link ClientExceptionHandler}. */
    static final class UnknownClientException extends RuntimeException {

        private final transient List<Long> clientIds;

        UnknownClientException(List<Long> clientIds) {
            super(clientIds.size() == 1
                    ? "Client " + clientIds.getFirst() + " does not exist."
                    : "These clients do not exist: " + clientIds + ".");
            this.clientIds = List.copyOf(clientIds);
        }

        List<Long> clientIds() {
            return clientIds;
        }
    }
}
