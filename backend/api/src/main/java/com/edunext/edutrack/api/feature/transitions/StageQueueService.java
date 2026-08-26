package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * C-062 · S-31's team inbox — {@code GET /stages/queue}, blueprint §17 item 12.
 *
 * <p>Visibility is {@link StageQueueScope}'s, deliberately not
 * {@code ScopedTickets}' — see that class's own javadoc for why §10.2's
 * {@code assigned_to = me} would make this screen show nothing worth showing.
 * Everything else here is ordinary: a keyset page over {@code tickets},
 * filtered to one stage and never {@code CLOSED}, with each row's time in
 * stage computed through {@link WorkingHoursService} — CLAUDE.md's rule that
 * every duration in this system routes through one implementation.
 *
 * <h2>The sort is {@code stageEnteredAt} ascending, and that is "longest waiting first"</h2>
 *
 * <p>The screen's contract is "sorted by time-in-stage descending" — the
 * ticket rotting longest on top. Time-in-stage is a monotonically decreasing
 * function of {@code stageEnteredAt} for a fixed "now", so ordering by the
 * stored column ascending gives the identical row order without recomputing a
 * working-minutes figure per row just to sort by it — the same shortcut
 * {@code TicketListSpecs} takes for every other keyset page in this codebase.
 */
@Service
class StageQueueService {

    private final TicketRepository tickets;
    private final StageQueueScope scope;
    private final WorkflowStageRepository stages;
    private final WorkingHoursService workingHours;
    private final UserRepository users;
    private final ProjectRepository projects;
    private final ClientRepository clients;

    StageQueueService(TicketRepository tickets, StageQueueScope scope, WorkflowStageRepository stages,
                      WorkingHoursService workingHours, UserRepository users, ProjectRepository projects,
                      ClientRepository clients) {
        this.tickets = tickets;
        this.scope = scope;
        this.stages = stages;
        this.workingHours = workingHours;
        this.users = users;
        this.projects = projects;
        this.clients = clients;
    }

    @Transactional(readOnly = true)
    StageQueueDtos.ListResponse list(Authentication caller, String stageCode, Long projectId,
                                     boolean unassignedOnly, String rawCursor, Integer rawLimit) {
        CallerIdentity identity = CallerIdentity.of(caller).orElse(null);
        if (identity == null) {
            // Absent, not defaulted — CallerIdentity's own rule. isAuthenticated()
            // on the route means this should not happen in practice; the safe
            // answer if it somehow does is the empty queue, not every ticket.
            return new StageQueueDtos.ListResponse(List.of(), PageMeta.last());
        }

        List<Long> visible = scope.visibleProjectIds(identity);
        List<Long> narrowed = projectId == null
                ? visible
                : (visible.contains(projectId) ? List.of(projectId) : List.of());

        if (narrowed.isEmpty()) {
            return new StageQueueDtos.ListResponse(List.of(), PageMeta.last());
        }

        int limit = PageLimit.clamp(rawLimit);
        Cursor cursor = Cursor.decode(rawCursor);

        Specification<Ticket> criteria = Specification.allOf(
                waiting(stageCode, narrowed, unassignedOnly), after(cursor));
        Sort sort = Sort.by(Sort.Order.asc("stageEnteredAt"), Sort.Order.asc("id"));

        List<Ticket> fetched = tickets
                .findAll(criteria, PageRequest.of(0, PageLimit.fetchSize(limit), sort))
                .getContent();

        CursorPage<Ticket> page = CursorPage.of(fetched, limit,
                t -> new Cursor(String.valueOf(enteredAt(t)), t.getId()));

        List<StageQueueDtos.QueueRow> rows = page.data().stream()
                .map(t -> toRow(t, stageCode))
                .toList();

        return new StageQueueDtos.ListResponse(rows, page.meta());
    }

    private StageQueueDtos.QueueRow toRow(Ticket t, String stageCode) {
        Instant enteredAt = enteredAt(t);
        BigDecimal workingHrs = workingHours.workingHoursBetween(
                enteredAt, Instant.now(), t.getProjectId(), t.getAssignedTo());
        int mins = workingHrs.multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();

        // Looked up per ticket rather than once per page: slaHours lives on
        // (template, stageCode), and two projects on different templates can
        // give the same stage code different SLA hours (§7.4's designer lets
        // them). A page-wide single lookup would silently borrow one
        // project's SLA for another's ticket.
        WorkflowStage stageDef = t.getWorkflowTemplateId() == null ? null
                : stages.findByTemplateIdAndStageCode(t.getWorkflowTemplateId(), stageCode).orElse(null);
        boolean breached = stageDef != null && stageDef.getSlaHours() != null
                && workingHrs.compareTo(stageDef.getSlaHours()) > 0;

        return new StageQueueDtos.QueueRow(
                TicketWire.of(t, users, projects, clients), enteredAt, mins, breached);
    }

    /**
     * {@code stageEnteredAt} is nullable and, until C-062's own fix to
     * {@code TicketWriteService.create}, was never written on creation — a
     * ticket seeded or created before that fix can still carry {@code null}.
     * {@code createdAt} is the honest fallback, the same one the mock's
     * {@code ribbon.ts} handler uses for the identical reason.
     */
    private static Instant enteredAt(Ticket t) {
        return t.getStageEnteredAt() != null ? t.getStageEnteredAt() : t.getCreatedAt();
    }

    private static Specification<Ticket> waiting(String stageCode, List<Long> projectIds, boolean unassignedOnly) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.equal(root.get("currentStage"), stageCode));
            and.add(root.get("projectId").in(projectIds));
            and.add(cb.notEqual(root.get("status"), "CLOSED"));
            if (unassignedOnly) {
                and.add(cb.isNull(root.get("assignedTo")));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    /** Resume after the last row of the previous page — {@code TicketListSpecs.after}'s own shape. */
    private static Specification<Ticket> after(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        return (root, query, cb) -> {
            Instant boundary;
            try {
                boundary = Instant.parse(cursor.sortKey());
            } catch (RuntimeException malformed) {
                // An unparsable cursor means the first page, Cursor.decode's own
                // contract for input this endpoint did not issue.
                return null;
            }
            Predicate strictlyPast = cb.greaterThan(root.get("stageEnteredAt"), boundary);
            Predicate sameValue = cb.equal(root.get("stageEnteredAt"), boundary);
            Predicate tieBreak = cb.greaterThan(root.get("id"), cursor.id());
            return cb.or(strictlyPast, cb.and(sameValue, tieBreak));
        };
    }
}
