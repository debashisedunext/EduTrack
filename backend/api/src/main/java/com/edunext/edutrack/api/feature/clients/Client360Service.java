package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * B-066 · S-32's Client 360 view — the read-across a PM opens by clicking a
 * client name on a ticket (S-20's traceability rule, {@code entityLinks.ts}).
 *
 * <h2>Row scope, and why the client lookup does not carry one</h2>
 *
 * <p>A client is not row-scoped — {@link ClientController}'s javadoc states
 * the §2 row 51 argument for every role reading every client — so
 * {@link ClientService#find} answers the same {@code Client} to whoever asks
 * and a missing id is unambiguously "no such client". The <b>tickets</b>
 * against that client are a different question: {@code ScopeResolver}'s rule
 * still applies to them, so a Developer opening this screen must see only
 * their own assigned work against the client, never the client's whole book.
 * Both queries below go through {@link ScopedTickets}, A-034's one door onto
 * {@code TicketRepository}.
 *
 * <h2>The figures ignore {@code ?status=}, the ticket list does not</h2>
 *
 * <p>{@code status} narrows which tickets the caller sees on this page, the
 * same way a dashboard's own filters narrow the ticket list beside its
 * cards without narrowing the cards themselves — narrowing the aggregates
 * under a list filter would make "SLA compliance" answer a different
 * question depending on what the caller happened to have selected.
 *
 * <h2>One scoped fetch, not four {@code COUNT}s</h2>
 *
 * <p>{@code TicketReportRepository.clientReport} already computes this exact
 * shape — open/closed/SLA/avg-resolution per client — as one aggregate SQL
 * statement, but it is package-private in {@code feature/reports} (Stream
 * A's, by this backlog's own M6 split) and built for a report iterating
 * every client at once, not this screen's one. Re-deriving the same numbers
 * here from a single {@link ScopedTickets#list} keeps the query inside
 * Stream B's own directory and inside the one door {@code ScopeGuardRulesTest}
 * allows onto {@code TicketRepository} — at the cost of hydrating entities
 * instead of running a {@code GROUP BY}. One client's ticket history is
 * bounded the way {@code ClientQueryRepository}'s own aggregates already
 * argue an Admin grid is (not the live {@code COUNT(*)} CLAUDE.md forbids for
 * dashboards); if a client with an unusually large history ever makes that
 * cost real, the fix is a new aggregate method on {@code ScopedTickets}
 * itself, flagged for Stream A's sign-off the way {@code byIds} already was.
 */
@Service
class Client360Service {

    /** Matches {@code TicketListService}'s default sort, so a client's history reads newest-first here too. */
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final ClientService clients;
    private final ScopedTickets tickets;

    /**
     * B-0xx · flagged for Divyansh's sign-off rather than fixed quietly.
     * {@code TicketWire.of} started requiring this to resolve {@code reportedBy}
     * /{@code assignee} into the contract's {@code UserRef} — see that class's
     * own note on the S-20 blank-screen bug this closes. One lookup per row
     * here rather than {@code TicketListRefs}' batched {@code IN} query: this
     * page's own ticket list already pages at {@link PageLimit}, and batching
     * it is a fair follow-up but a bigger one than a compile fix should carry.
     */
    private final UserRepository users;

    Client360Service(ClientService clients, ScopedTickets tickets, UserRepository users) {
        this.clients = clients;
        this.tickets = tickets;
        this.users = users;
    }

    @Transactional(readOnly = true)
    Optional<Client360Dtos.Client360Response> view(Authentication caller, long clientId,
                                                    String status, String rawCursor, Integer rawLimit) {

        Optional<ClientDtos.Client> client = clients.find(clientId);
        if (client.isEmpty()) {
            return Optional.empty();
        }

        Figures figures = summarise(tickets.list(caller, hasClientId(clientId), Sort.unsorted()));

        int limit = PageLimit.clamp(rawLimit);
        Specification<Ticket> criteria = Specification.allOf(
                hasClientId(clientId),
                status == null ? null : hasStatus(status),
                after(Cursor.decode(rawCursor)));

        List<Ticket> fetched = tickets
                .page(caller, criteria, PageRequest.of(0, PageLimit.fetchSize(limit), SORT))
                .getContent();

        CursorPage<Ticket> page = CursorPage.of(fetched, limit,
                t -> new Cursor(String.valueOf(t.getCreatedAt()), t.getId()));

        List<TicketWire.Ticket> wire = page.data().stream().map(t -> TicketWire.of(t, users)).toList();

        Client360Dtos.Client360Data data = new Client360Dtos.Client360Data(
                client.get(), wire, figures.open(), figures.closed(),
                figures.slaCompliancePct(), figures.avgResolutionHrs());

        return Optional.of(new Client360Dtos.Client360Response(data, page.meta()));
    }

    // ------------------------------------------------------------------
    // The rolled-up figures
    // ------------------------------------------------------------------

    private record Figures(long open, long closed, BigDecimal slaCompliancePct, BigDecimal avgResolutionHrs) {
    }

    /**
     * {@code openNow}/{@code closed}/{@code slaCommitted}/{@code slaMet}/
     * {@code avgResolutionHours} — {@code TicketReportRepository.clientReport}'s
     * own five figures, computed the same way: SLA compliance is measured
     * against tickets that carried a planned close date, never against every
     * closed ticket, so a client whose work was never date-committed reads as
     * null rather than a misleading 100%.
     */
    private static Figures summarise(List<Ticket> all) {
        long open = 0;
        long closed = 0;
        long committed = 0;
        long met = 0;
        long resolvedCount = 0;
        long resolutionHoursSum = 0;

        for (Ticket t : all) {
            if (!"CLOSED".equals(t.getStatus())) {
                open++;
                continue;
            }
            closed++;

            Instant actual = t.getActualCloseDate();
            if (actual != null) {
                resolvedCount++;
                resolutionHoursSum += Duration.between(t.getDateReported(), actual).toHours();
            }

            Instant planned = t.getPlannedCloseDate();
            if (planned != null) {
                committed++;
                if (actual != null && !actual.isAfter(planned)) {
                    met++;
                }
            }
        }

        BigDecimal slaPct = committed == 0 ? null
                : BigDecimal.valueOf(met * 100.0 / committed).setScale(1, RoundingMode.HALF_UP);
        BigDecimal avgHrs = resolvedCount == 0 ? null
                : BigDecimal.valueOf((double) resolutionHoursSum / resolvedCount).setScale(1, RoundingMode.HALF_UP);

        return new Figures(open, closed, slaPct, avgHrs);
    }

    // ------------------------------------------------------------------
    // Specifications — kept local rather than importing
    // feature/tickets/list/TicketListSpecs, which is package-private
    // ------------------------------------------------------------------

    private static Specification<Ticket> hasClientId(long clientId) {
        return (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
    }

    private static Specification<Ticket> hasStatus(String status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** {@code TicketListSpecs.after}'s own keyset predicate, against this method's fixed sort. */
    private static Specification<Ticket> after(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        Instant boundary;
        try {
            boundary = Instant.parse(cursor.sortKey());
        } catch (RuntimeException malformed) {
            // Not one of ours — Cursor.decode's own contract for unrecognised
            // input is "the first page", and a boundary this method cannot
            // parse gets the same answer rather than a 400 on a stale bookmark.
            return null;
        }
        Instant finalBoundary = boundary;
        return (root, query, cb) -> cb.or(
                cb.lessThan(root.get("createdAt"), finalBoundary),
                cb.and(cb.equal(root.get("createdAt"), finalBoundary),
                        cb.lessThan(root.get("id"), cursor.id())));
    }
}
