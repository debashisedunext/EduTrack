package com.edunext.edutrack.api.feature.transitions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-062 · {@code GET /stages/queue} — S-31's team inbox, blueprint §17 item 12:
 * "QA and Deployment are queue-driven teams, not assignment-driven ones.
 * Without a shared 'waiting in QA' list, tickets stall between the handoff and
 * someone noticing."
 *
 * <h2>🔴 Written to close a real gap, and flagged for Stream A's review before it merges</h2>
 *
 * <p>Until now this route existed only in {@code contracts/openapi.yaml} and
 * {@code frontend/src/mocks/handlers/ribbon.ts} — nothing on the server
 * answered it, and {@code StageQueuePage.tsx}'s own javadoc, this feature's
 * {@code README.md} and this class all say the same thing: the reason is not
 * an oversight, it is that answering this route correctly means deciding
 * {@code GET /stages/queue}'s row visibility, and {@code ScopeResolver}'s
 * {@code assigned_to = me} for Developer/QA/Deployment (§10.2) is unusable
 * for a screen whose entire purpose is showing those three roles work that is
 * <em>not</em> already theirs. That decision is Stream A's — the guard is
 * Shivendra's component, named as the schema arbiter in TEAM-PLAN.md — and
 * {@link StageQueueScope} is where it is made: project membership, matching
 * the answer {@code StageQueueSubscriptionScope} (D-014) already gave the
 * WebSocket room this same screen listens on.
 *
 * <p>Built anyway, on the same precedent {@code TicketListController}'s own
 * header states for {@code GET /tickets}: the frontend has shipped against
 * this contract since D-001, real users were hitting a 404 nothing else in
 * the backlog claimed, and {@code StageQueueScope} is a narrow, additive
 * carve-out that does not touch {@code ScopeResolver} itself — every other
 * ticket read keeps exactly its current behaviour, and {@code TicketScopeIT}
 * proves it. Not a quiet incursion: this is a starting point for Stream A to
 * take over or correct, not a decision imposed on them.
 */
@RestController
@RequestMapping("/api/v1/stages")
@Tag(name = "ribbon")
class StageQueueController {

    private final StageQueueService service;

    StageQueueController(StageQueueService service) {
        this.service = service;
    }

    /**
     * Every authenticated role may ask, on {@code TicketListController}'s own
     * reasoning: what differs per role is the rows {@link StageQueueScope}
     * hands back, not the right to ask at all. Denying the capability would
     * leave a Developer unable to watch their own handoff land — §16's
     * walkthrough, and exactly what {@code StageQueueSubscriptionScope}
     * already grants the matching room for.
     */
    @GetMapping(path = "/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getStageQueue", summary = "Team inbox for a stage (S-31)",
            description = "\"Waiting in QA\", \"Waiting in Deployment\" — sorted by time-in-stage "
                    + "descending, so the ticket rotting longest is first. `stage` is mandatory so "
                    + "this can never degrade into every ticket on the caller's projects.")
    StageQueueDtos.ListResponse list(
            Authentication caller,
            @RequestParam String stage,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(caller, stage, projectId, Boolean.TRUE.equals(unassignedOnly), cursor, limit);
    }
}
