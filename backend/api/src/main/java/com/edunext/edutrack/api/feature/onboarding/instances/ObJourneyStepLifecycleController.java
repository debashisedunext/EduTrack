package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-104 · {@code /onboarding/journey-steps} — start, complete,
 * block-with-mandatory-reason, waiting-on-client, resume; {@code complete}
 * now runs C-106's completion gate. See {@link ObJourneyStepLifecycleService}'s
 * own class javadoc for exactly which two later tasks (C-105, C-119) this
 * still deliberately leaves alone.
 *
 * <h2>Auth: {@code authenticated()} only, deliberately not more</h2>
 *
 * <p>Same interim state {@code ObJourneyTemplateController} documents on
 * its own class, one module over: every {@code /onboarding/**} path is
 * drawn behind {@code ModuleAccessGuard} (A-111, plan §2.1) in the design,
 * but that guard's own javadoc says nothing calls it yet, and wiring it
 * into {@code SecurityConfig} is a separate, later task. So this falls to
 * {@code SecurityConfig}'s blanket {@code
 * .requestMatchers("/api/**").authenticated()}, exactly where {@code
 * /onboarding/journey-templates} has sat since C-102. The row-scope rule
 * this task does own — only a step's owner or backup owner may act on it —
 * is enforced inside {@link ObJourneyStepLifecycleService}, not here, on
 * {@code StageOwnership}'s own precedent that a row-scope rule is not a
 * {@code @PreAuthorize} expression. {@code @PreAuthorize("isAuthenticated()")}
 * says the interim state explicitly rather than leaving it implicit —
 * {@code RouteAuthorizationTest} requires every route to declare a
 * decision.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-steps")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyStepLifecycleController {

    private final ObJourneyStepLifecycleService service;

    ObJourneyStepLifecycleController(ObJourneyStepLifecycleService service) {
        this.service = service;
    }

    @PostMapping(value = "/{stepId}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startObJourneyStep",
            summary = "Start a step (C-104)",
            description = """
                    `PENDING` → `IN_PROGRESS`. `422` if the journey's gate is still \
                    `LOCKED` or the journey is held by another; `422` if the caller is \
                    neither the step's owner nor its backup owner; `422` if the step is \
                    not `PENDING`. No dependency-graph check yet — see the service's own \
                    javadoc; that refusal is C-119's.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepResponse start(Authentication caller, @PathVariable long stepId) {
        ObJourneyStep step = service.start(stepId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyStepLifecycleDtos.ObJourneyStepResponse.of(step);
    }

    @PostMapping(value = "/{stepId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "completeObJourneyStep",
            summary = "Complete a step (C-104, gated by C-106)",
            description = """
                    `IN_PROGRESS` → `DONE`. `422` (`ObCompletionGateProblem`) unless every \
                    mandatory Task List item is answered, every required document is \
                    attached and clean, and — where the step demands it — a client \
                    sign-off has been accepted (plan §5.8, architect's addition 7, §8). \
                    Any future client-facing acceptance flow (A-120) must call the same \
                    service method rather than completing the step directly.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepResponse complete(Authentication caller, @PathVariable long stepId) {
        ObJourneyStep step = service.complete(stepId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyStepLifecycleDtos.ObJourneyStepResponse.of(step);
    }

    @PostMapping(value = "/{stepId}/block",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "blockObJourneyStep",
            summary = "Block a step with a mandatory reason (C-104)",
            description = """
                    `IN_PROGRESS` → `BLOCKED`. `reasonCode` is mandatory (plan's addition \
                    5, "blocked-with-reason") — `400` if blank. Internal `BLOCKED` does not \
                    pause the TAT clock; see `waiting-on-client` for the state that does.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepResponse block(
            Authentication caller, @PathVariable long stepId,
            @Valid @RequestBody ObJourneyStepLifecycleDtos.BlockStepRequest request) {
        ObJourneyStep step = service.block(stepId, CallerIdentityAccess.requireUserId(caller),
                request.reasonCode(), request.note());
        return ObJourneyStepLifecycleDtos.ObJourneyStepResponse.of(step);
    }

    @PostMapping(value = "/{stepId}/waiting-on-client", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "markObJourneyStepWaitingOnClient",
            summary = "Mark a step waiting on the client (C-104)",
            description = """
                    `IN_PROGRESS` → `WAITING_ON_CLIENT`. Unlike internal `BLOCKED`, this \
                    state pauses the TAT clock and attributes the wait to the client — the \
                    clock-event row that actually pauses it is C-105's; this route only \
                    flips the status the scanner reads.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepResponse waitOnClient(Authentication caller, @PathVariable long stepId) {
        ObJourneyStep step = service.waitOnClient(stepId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyStepLifecycleDtos.ObJourneyStepResponse.of(step);
    }

    @PostMapping(value = "/{stepId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resumeObJourneyStep",
            summary = "Resume a blocked or waiting-on-client step (C-104)",
            description = """
                    `BLOCKED` → `IN_PROGRESS` or `WAITING_ON_CLIENT` → `IN_PROGRESS`. \
                    Clears the block reason and note. `due_at` is left untouched — \
                    recomputing it against the working calendar on resume is C-105's own \
                    line in the backlog, not this route's.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepResponse resume(Authentication caller, @PathVariable long stepId) {
        ObJourneyStep step = service.resume(stepId, CallerIdentityAccess.requireUserId(caller));
        return ObJourneyStepLifecycleDtos.ObJourneyStepResponse.of(step);
    }
}
