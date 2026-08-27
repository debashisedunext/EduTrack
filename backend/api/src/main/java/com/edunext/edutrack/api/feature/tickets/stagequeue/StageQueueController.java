package com.edunext.edutrack.api.feature.tickets.stagequeue;

import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-062 - {@code GET /stages/queue}, per {@code contracts/openapi.yaml}.
 *
 * <p>The route the contract has declared since D-001 and nothing served, so
 * S-31 answered 404 against a real backend while working perfectly against
 * D-004's mock. {@link StageQueueService} records why the rest of the feature
 * shipped without it.
 *
 * <h2>{@code isAuthenticated()}, not a capability</h2>
 *
 * <p>{@code TicketListController}'s own reasoning, and the contract agrees -
 * this operation declares 401 and no 403. There is no {@code ticket.queue}
 * permission and there should not be one: every seeded role has a queue to
 * look at, and which rows they may see is
 * {@code ScopeResolver.stageQueueScope}'s decision per row rather than the
 * guard's per role. A role with no project membership already gets an empty
 * page from {@code DENY_ALL} rather than a refusal.
 *
 * <h2>{@code stage} is required, and that is a scope decision</h2>
 *
 * <p>The contract marks it {@code required: true} and {@link StageQueueSpecs}
 * explains what it is doing there: the queue reads under a scope wider than
 * section 10.2, and without a stage the endpoint degrades into "every ticket
 * on my projects" - a much larger grant arrived at by leaving a parameter off
 * a URL. {@code @NotBlank} rather than only Spring's own missing-parameter
 * check, so {@code ?stage=} with an empty value is refused too rather than
 * reaching the query as a filter that matches nothing.
 */
@RestController
@RequestMapping("/api/v1/stages/queue")
@Tag(name = "ribbon")
@Validated
class StageQueueController {

    private final StageQueueService queue;

    StageQueueController(StageQueueService queue) {
        this.queue = queue;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getStageQueue",
            summary = "Team inbox for a stage (S-31)",
            description = """
                    "Waiting in QA", "Waiting in Deployment" - the landing page for QA and \
                    Deployment roles. Sorted by time-in-stage descending, so the ticket \
                    rotting longest is first.

                    Scoped by project membership rather than assignment: a queue of work \
                    nobody has picked up yet cannot be scoped to the person it is not yet \
                    assigned to. `stage` is required for that reason - it is half of what \
                    keeps the wider scope safe.""")
    StageQueueDtos.QueueResponse queue(
            Authentication caller,
            @RequestParam @NotBlank @Size(max = 20) String stage,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        CursorPage<StageQueueDtos.QueueRow> page =
                queue.queue(caller, stage, projectId, unassignedOnly, cursor, limit);

        return new StageQueueDtos.QueueResponse(page.data(), page.meta());
    }
}
