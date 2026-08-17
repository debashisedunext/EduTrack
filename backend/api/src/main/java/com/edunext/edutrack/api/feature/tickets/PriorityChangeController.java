package com.edunext.edutrack.api.feature.tickets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-020 · {@code PATCH /tickets/{ticketId}/priority}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>The contract has declared {@code changeTicketPriority} since D-001 with
 * nothing serving it, so the generated client has always exported the function:
 * S-20's chip would have worked against the mock and 404'd in production. That
 * is the state C-028 found for {@code deleteAttachment} and C-038 for
 * {@code reopenTicket}, and it is named as a defect each time rather than
 * discovered again.
 *
 * <p>A {@code PATCH} rather than an action {@code POST}, which is the contract's
 * shape and also the right one: unlike a reopen, this genuinely is a new value
 * for one field of the ticket. The side effects — the recomputed date and the
 * history row — are consequences of that field moving, not a separate act.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally; see {@code PlannedCloseDateController}'s note and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/priority")
@Tag(name = "tickets")
class PriorityChangeController {

    private final PriorityChangeService service;

    PriorityChangeController(PriorityChangeService service) {
        this.service = service;
    }

    /*
     * A-033 · ticket.assign, which Admin, PM and Support hold and Developer, QA
     * and Deployment do not.
     *
     * ⚠ READ THIS BEFORE COPYING THE ANNOTATION. The capability is BORROWED, and
     * the right long-term answer is a `ticket.change_priority` code that does not
     * exist yet. Recorded as the current answer rather than the settled one, in
     * the way PermissionMatrix records the leave-approval and PM-project-scope
     * rows.
     *
     * What is NOT in doubt is who. §4B.1's own row is explicit — "Admin, PM,
     * Support Desk. Developer/QA/Deployment can only *request* a change, which
     * raises a notification to the PM" — and that is exactly three roles, the
     * same three as §2's Assign, Close and Reopen rows. It is also a genuine
     * capability rather than a row rule wearing one: whether a Developer may
     * relevel does not depend on which ticket, on who is assigned, or on the
     * clock, which are the three things that forced the comment and attachment
     * rules down into their services. So @PreAuthorize can express all of it.
     *
     * What is borrowed is the *code*. §2 has no "change priority" row, so
     * V20260806_0900 seeds no permission for it, and adding one is a migration in
     * backend/domain/.../db/migration — Stream A's path, and off-limits to this
     * stream without sign-off (CLAUDE.md). Raised rather than made.
     *
     * ticket.assign is the borrowing, and it is chosen over the two alternatives
     * with the identical grant set:
     *
     *   ticket.reopen — C-038 argues at length that its meaning is narrow and
     *       doubly stated: §2's Reopen row AND a seeded workflow_transitions row
     *       for CLOSED → REOPENED. Borrowing it would tie a level change to a
     *       state machine it has nothing to do with, and would make ReopenService's
     *       argument for that capability read as broader than it is.
     *
     *   ticket.close — the same objection, on a transition this route must work
     *       *before*.
     *
     *   ticket.assign — "Assign or reassign a ticket to a resource". Of the three
     *       it is the only one that is not bound to a status transition, and it is
     *       the §2 capability about *directing* a ticket rather than *working* it:
     *       assigning decides who does it, prioritising decides when. §2 gives
     *       both to the same three roles for the same reason, which is why the
     *       sets coincide rather than by accident.
     *
     * The cost of being wrong here is bounded and visible: a role granted
     * ticket.assign and nothing else would gain this route too. Today no such
     * role exists — the three that hold it hold all of them — so the borrowing
     * changes nobody's access. It stops being true the first time an
     * administrator composes a custom role on S-09, which is the event that
     * should turn this comment into a migration.
     *
     * *Which* tickets is still not asked here. ScopedTickets applies the row
     * scope inside the service, so a ticket outside the caller's scope answers
     * 404 and never 403 (A-035) whatever capability they hold.
     */
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.assign')")
    @Operation(operationId = "changeTicketPriority",
            summary = "Change the priority level (§4B.1)",
            description = """
                    One transaction: move `level`, recompute `plannedCloseDate` from the SLA \
                    ladder under the new level, and append a `LEVEL_CHANGED` history row \
                    carrying the old value, the new value, the actor and the reason.

                    **`originalLevel` is never overwritten.** It is what makes "born critical \
                    versus became critical" reportable, and a single overwrite is \
                    unrecoverable.

                    **`reason` is mandatory once the ticket is assigned** and optional while \
                    it is still unassigned, per §4B.1 — a `400` keyed onto `reason` when it \
                    is missing.

                    **The date is recomputed from the cycle's start, not from now**, because \
                    an SLA is "resolve within N hours of being reported". Escalating an old \
                    ticket to a short level therefore produces a planned close date in the \
                    past, and the ticket is breached — which is the true answer, and what \
                    lets Stream D's scanner pick it up.

                    Re-picking the level the ticket already holds is a no-op: no history row, \
                    no recomputed date, `200` with the ticket unchanged.

                    Not yet: §4B.1's "setting Critical fires an immediate alert" fan-out, \
                    which is Stream D's — `SlaEscalation` already sends exactly that for the \
                    automatic half of the same rule, and the `LEVEL_CHANGED` row this writes \
                    is the event it would hang off.""")
    /*
     * ⚠ String, and the two ticket routes beside this one disagree.
     *
     * The contract's `TicketId` is a ticket CODE: `components/schemas/TicketId`
     * is `type: string` with the pattern `^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$` and
     * the example `CRM-26-00347`, and `Ticket.ticketId` is that same schema. So
     * every client — including the generated one every screen already uses —
     * puts a code in this path segment, and S-20's URL is
     * `/tickets/CRM-26-00347`.
     *
     * `TicketDetailController.full` (A-052) and `ReopenController.reopen`
     * (C-038) both declare `@PathVariable long ticketId`. Against the real
     * backend that is a 400 for every request the frontend actually sends; it
     * has not bitten because both have only ever been exercised against D-004's
     * mock, which routes on the string. No deviation is recorded for it in
     * PLAN.md §4, and `ScopedTickets` has carried `byCode`/`requireByCode` since
     * A-035 — described there as "the CRM-26-00347 form users actually type" —
     * with no caller until now.
     *
     * This route follows the contract rather than the neighbours, because a
     * route that only works when called wrongly is not working. Raised for
     * A-052 and C-038 rather than fixed here: changing either is another
     * stream's route and another task's test surface, and doing it quietly on a
     * priority branch is how a 400 becomes somebody else's afternoon.
     */
    PriorityChangeDtos.TicketResponse change(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody PriorityChangeDtos.ChangePriorityRequest request) {

        return new PriorityChangeDtos.TicketResponse(service.change(caller, ticketId, request));
    }
}
