package com.edunext.edutrack.api.security.scope;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * A-034 · the door. Every ticket read in the application goes through this
 * bean, and it is the only place {@link ScopeResolver}'s specification is
 * composed in.
 *
 * <p>A guard nobody is obliged to call is decoration. {@code ScopeResolver}
 * can produce a perfect specification and the system still leaks the first
 * time somebody writes {@code ticketRepository.findAll()} in a service,
 * because nothing about that line looks wrong. So the scope is not something
 * feature code remembers to add — it is something feature code cannot remove:
 * these methods take the caller and apply it themselves, and any extra
 * criteria a feature needs are {@code AND}-ed <em>on top of</em> the scope,
 * never instead of it.
 *
 * <p>A-037's ArchUnit rule makes that structural, by forbidding calls to
 * {@link TicketRepository} from outside this package. Until it lands the rule
 * is convention; the convention is stated here and in
 * {@code TicketRepository}'s own javadoc, which already says no finder applies
 * a scope of its own.
 *
 * <h2>Absence, not refusal</h2>
 *
 * <p>An out-of-scope id comes back as {@link Optional#empty()} — the same
 * answer as an id that was never issued. Callers turn that into 404 (A-035).
 * There is no method here that reports "exists but not yours", because having
 * one would make it available to be returned.
 */
@Component
public class ScopedTickets {

    private final TicketRepository tickets;
    private final ScopeResolver scope;

    ScopedTickets(TicketRepository tickets, ScopeResolver scope) {
        this.tickets = tickets;
        this.scope = scope;
    }

    /** @return the ticket, or empty if it does not exist <em>or</em> is out of scope. */
    public Optional<Ticket> byId(Authentication caller, long ticketId) {
        return tickets.findOne(scoped(caller, hasId(ticketId)));
    }

    /** As {@link #byId}, for the {@code CRM-26-00347} form users actually type. */
    public Optional<Ticket> byCode(Authentication caller, String ticketCode) {
        if (ticketCode == null || ticketCode.isBlank()) {
            return Optional.empty();
        }
        return tickets.findOne(scoped(caller, hasCode(ticketCode)));
    }

    public List<Ticket> list(Authentication caller, Specification<Ticket> criteria, Sort sort) {
        return tickets.findAll(scoped(caller, criteria), sort);
    }

    public Page<Ticket> page(Authentication caller, Specification<Ticket> criteria, Pageable pageable) {
        return tickets.findAll(scoped(caller, criteria), pageable);
    }

    /**
     * Counts only what the caller can see, which is what makes a list header
     * honest: a total that counted rows the body cannot show would tell a
     * Developer how many tickets exist in projects they have no access to.
     */
    public long count(Authentication caller, Specification<Ticket> criteria) {
        return tickets.count(scoped(caller, criteria));
    }

    /**
     * May this caller see this ticket at all — the question without the row.
     *
     * <p>D-013 authorises a STOMP subscription with it: a Developer must not be
     * able to subscribe to {@code /topic/ticket.{id}} for a ticket they could
     * not {@code GET}. It is deliberately implemented by running the same
     * specification rather than by re-stating §10.2 as a boolean, so the two
     * cannot drift — one rule, one evaluator. It costs an indexed existence
     * check per subscription, which is once per topic per session, not per
     * message.
     */
    public boolean canSee(Authentication caller, long ticketId) {
        return tickets.exists(scoped(caller, hasId(ticketId)));
    }

    /**
     * The same question from an identity rather than a servlet
     * {@link Authentication} — the WebSocket session has the former and there
     * is no {@link SecurityContextHolder} on a message thread.
     */
    public boolean canSee(CallerIdentity caller, long ticketId) {
        return tickets.exists(ScopeResolver.ticketScope(caller).and(hasId(ticketId)));
    }

    /**
     * The scope first, the feature's criteria second. Written this way round on
     * purpose: {@code scope.and(criteria)} cannot be turned into
     * {@code criteria} by a null, whereas a helper that started from the
     * caller's filter and appended the scope could be.
     */
    private Specification<Ticket> scoped(Authentication caller, Specification<Ticket> criteria) {
        Specification<Ticket> mandatory = scope.ticketScope(caller);
        return criteria == null ? mandatory : mandatory.and(criteria);
    }

    private static Specification<Ticket> hasId(long ticketId) {
        return (root, query, builder) -> builder.equal(root.get("id"), ticketId);
    }

    private static Specification<Ticket> hasCode(String ticketCode) {
        return (root, query, builder) -> builder.equal(root.get("ticketCode"), ticketCode);
    }
}
