package com.edunext.edutrack.api.feature.tickets.bulkreassign;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * C-063 · {@code POST /tickets/bulk-reassign}, blueprint §7.5 S-24 and S-17.
 *
 * <h2>Each ticket is its own transaction</h2>
 *
 * <p>{@code TicketJournal.append} is {@link org.springframework.transaction.annotation.Propagation#MANDATORY}
 * and takes a per-ticket lock, so five hundred appends inside one request-long
 * transaction would hold five hundred locks at once and roll every one of them
 * back the moment a single row refused. Neither is acceptable for a route the
 * contract documents as reporting "partial success" — {@code bulk-close}'s own
 * description says the identical thing for the identical reason. A
 * {@link TransactionTemplate} opens and commits one transaction per ticket, the
 * pattern {@code AttachmentScanTask} and {@code ThumbnailTask} already set:
 * {@code @Transactional} on a method called from inside this class's own loop
 * would never run through the Spring proxy and would silently do nothing.
 *
 * <h2>What this does not check</h2>
 *
 * <p>There is no {@code fromUserId} anywhere in this class, on purpose — see
 * {@link BulkReassignDtos}. And there is no rule here that a ticket must
 * already belong to some particular resource before it can be reassigned;
 * "reassign whoever holds it" is the entire operation, whether the caller
 * assembled {@code ticketIds} from a grid selection or from S-24's source-
 * resource step.
 */
@Service
class BulkReassignService {

    private static final String REASSIGNED = "REASSIGNED";
    private static final String NOT_FOUND_OR_OUT_OF_SCOPE = "Not found or out of scope";

    private final ScopedTickets tickets;
    private final UserRepository users;
    private final TicketJournal journal;
    private final TransactionTemplate transaction;

    BulkReassignService(ScopedTickets tickets,
                        UserRepository users,
                        TicketJournal journal,
                        PlatformTransactionManager transactionManager) {
        this.tickets = tickets;
        this.users = users;
        this.journal = journal;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws UnknownUserException 400 — {@code toUserId} names nobody
     */
    BulkReassignDtos.BulkResultData reassign(Authentication caller,
                                             BulkReassignDtos.BulkReassignRequest request) {
        long toUserId = request.toUserId();
        if (!users.existsById(toUserId)) {
            throw new UnknownUserException(toUserId);
        }

        // Duplicate ids collapse before anything runs — ResourceService.setStatus's
        // rule, for the same reason: a grid that ticked the same row twice, or a
        // page that sent it twice across a refresh, should get one outcome for it
        // rather than two.
        Set<String> ids = new LinkedHashSet<>(request.ticketIds());

        List<BulkReassignDtos.TicketOutcome> results = new ArrayList<>(ids.size());
        int succeeded = 0;
        int failed = 0;
        for (String ticketCode : ids) {
            BulkReassignDtos.TicketOutcome outcome =
                    transaction.execute(status -> reassignOne(caller, ticketCode, toUserId, request.reason()));
            results.add(outcome);
            if (outcome.ok()) {
                succeeded++;
            } else {
                failed++;
            }
        }
        return new BulkReassignDtos.BulkResultData(succeeded, failed, List.copyOf(results));
    }

    /**
     * One ticket, one transaction. Row scoping applies first — an id the caller
     * cannot see comes back identically to one that was never issued, per
     * A-035, so this reports the same {@code "Not found or out of scope"} the
     * bulk-close and bulk-level routes' own contract text names.
     */
    private BulkReassignDtos.TicketOutcome reassignOne(Authentication caller,
                                                        String ticketCode,
                                                        long toUserId,
                                                        String reason) {
        Optional<Ticket> found = tickets.byCode(caller, ticketCode);
        if (found.isEmpty()) {
            return BulkReassignDtos.TicketOutcome.refused(ticketCode, NOT_FOUND_OR_OUT_OF_SCOPE);
        }
        Ticket ticket = found.get();

        Long previous = ticket.getAssignedTo();
        if (Objects.equals(previous, toUserId)) {
            // Already there. Not an error — a source resource's ticket list can
            // grow stale between the wizard's step 2 and its confirm, and a
            // ticket somebody else already moved to the same target is success,
            // not a refusal to surface. No history row: nothing happened.
            return BulkReassignDtos.TicketOutcome.ok(ticketCode);
        }

        ticket.setAssignedTo(toUserId);
        journal.append(reassignedEntry(ticket, previous, toUserId, caller, reason));
        return BulkReassignDtos.TicketOutcome.ok(ticketCode);
    }

    private static TicketHistory reassignedEntry(Ticket ticket,
                                                  Long previous,
                                                  long toUserId,
                                                  Authentication caller,
                                                  String reason) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(REASSIGNED);
        entry.setFieldName("assignedTo");
        entry.setOldValue(previous == null ? null : String.valueOf(previous));
        entry.setNewValue(String.valueOf(toUserId));
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(reason.trim());
        return entry;
    }

    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }
}
