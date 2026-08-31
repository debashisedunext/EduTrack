package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /tickets} — S-17's server side.
 *
 * <h2>⚠ Written by Stream A, in Stream C's directory</h2>
 *
 * <p>{@code api/feature/tickets/} belongs to Stream C per TEAM-PLAN.md §6, and
 * this endpoint is Stream C's work. It was written here because the contract
 * has declared {@code listTickets} since D-001 and nothing implemented it: S-17
 * ships against D-004's mock server, so the screen works and the missing server
 * is invisible. No backlog line in any stream claims it.
 *
 * <p>It is <b>not</b> a quiet incursion — CODEOWNERS requests Divyansh on this
 * path automatically, and it needs his review. If he would rather own it, this
 * is a starting point to take over rather than a decision imposed on him.
 *
 * <h2>Every parameter the contract declares is accepted</h2>
 *
 * <p>Including {@code moduleId}, which was accepted and ignored until C-065
 * added the column and C-070 wired the predicate. Accepting and ignoring it was
 * the smaller lie while nothing could answer it — rejecting a parameter the
 * generated client already sends would have broken the client over a column that
 * did not exist. It now filters, which is the state this note was waiting for.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "tickets")
class TicketListController {

    private final TicketListService list;

    TicketListController(TicketListService list) {
        this.list = list;
    }

    /**
     * Every authenticated role may ask; {@code ScopeResolver} decides what they
     * get back. Denying the capability to a role would leave it with no ticket
     * screen at all — the narrowing is row scoping's job, not the guard's.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listTickets", summary = "List tickets — row-scoped server-side")
    TicketListDtos.ListResponse list(
            Authentication caller,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Integer taskTypeId,
            @RequestParam(required = false) Long moduleId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean isDelayed,
            @RequestParam(required = false) Boolean isClientRaised,
            @RequestParam(required = false) Boolean reopenedOnly,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) Boolean excludeClosed,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closedTo,
            // A-060 · the reported-date window. Named for the column it filters,
            // like the two pairs above it — the dashboard emitted this as bare
            // `from`/`to` from A-055 onwards, which is both vaguer and, on a
            // screen that already has `dueFrom` and `closedFrom`, ambiguous
            // about which date it means.
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedTo,
            // Dashboard Rework Dev 1, PR 5 · the seven Today/Weekly drill-down
            // parameters — see TicketListSpecs.filters for what each narrows.
            @RequestParam(required = false) String statusCategory,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startedTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate finishedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate finishedTo,
            @RequestParam(required = false) Boolean pendingReview,
            @RequestParam(required = false) String sort) {

        TicketListSpecs.Filters filters = new TicketListSpecs.Filters(
                q, projectId, clientId, taskTypeId, moduleId, level, status, stage, assigneeId,
                isDelayed, isClientRaised, reopenedOnly, unassigned, excludeClosed,
                dueFrom, dueTo, closedFrom, closedTo, reportedFrom, reportedTo,
                statusCategory, statuses, updatedFrom, updatedTo,
                startedFrom, startedTo, finishedFrom, finishedTo, pendingReview);

        CursorPage<TicketListDtos.TicketSummary> page =
                list.list(caller, filters, sort, cursor, limit);

        return new TicketListDtos.ListResponse(page.data(), page.meta());
    }
}
