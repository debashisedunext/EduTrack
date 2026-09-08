package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * B-125 · {@code /onboarding/clients/{obClientId}/prereqs} and its ad-hoc
 * create — the accordion above the journey accordions on OB-05, and the
 * interactive half of CP-03.
 *
 * <h2>The gate is reported here, never opened here</h2>
 *
 * <p>{@code gateStatus} is the same value every journey on this client
 * carries, repeated so the strip renders without reading a journey. There is
 * no route on this controller that opens it, and there will not be one: plan
 * §5.3 leaves the gate to open as a consequence of the transition that clears
 * the last task, and the only valve is skipping a non-mandatory one.
 *
 * <h2>Row scope is {@link ObClientScope}'s, not a second reading of §3</h2>
 *
 * <p>The predicate is composed into this feature's own SQL rather than
 * respelled — B-102 widened the record's visibility for exactly that, and
 * CLAUDE.md's rule against writing your own filtering is why. An
 * out-of-scope client answers 404, never 403: no existence leak.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObClientPrereqController {

    private final ObClientPrereqService prereqs;
    private final ObPrereqTaskService taskService;
    private final ObClientPrereqAssembler assembler;
    private final ObJourneyGateReader journeys;
    private final ObPrereqClientVisibility visibility;

    ObClientPrereqController(ObClientPrereqService prereqs,
                             ObPrereqTaskService taskService,
                             ObClientPrereqAssembler assembler,
                             ObJourneyGateReader journeys,
                             ObPrereqClientVisibility visibility) {
        this.prereqs = prereqs;
        this.taskService = taskService;
        this.assembler = assembler;
        this.journeys = journeys;
        this.visibility = visibility;
    }

    @GetMapping(path = "/clients/{obClientId}/prereqs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObClientPrereqs",
            summary = "One client's prerequisites, and the state of their gate (OB-05, CP-03)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqsResponse> get(
            @PathVariable long obClientId, Authentication caller) {

        requireVisible(obClientId, caller);
        ObClientPrereqDtos.ObClientPrereqs body = read(obClientId);
        return ResponseEntity.ok().eTag(etagOf(body)).body(
                new ObClientPrereqDtos.ObClientPrereqsResponse(body));
    }

    /**
     * The ad-hoc addition, and the reason it takes an {@code If-Match} when
     * CONVENTIONS §5 does not ask a create for one.
     *
     * <p>An ad-hoc task can be mandatory, and adding a mandatory task
     * <b>re-locks a gate that may have just opened</b>. The precondition is
     * against the client's prerequisite tag, so an Admin adding a task from a
     * screen rendered before another Admin verified the last mandatory one is
     * refused with 412 rather than silently reversing a gate-open — which
     * would stop clocks that had already started and contradict a kickoff
     * mail already sent.
     *
     * <p>That is a lost update in every sense the convention cares about;
     * what makes it unusual is only that the row being lost is not the one
     * being written.
     */
    @PostMapping(path = "/clients/{obClientId}/prereq-tasks",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addObClientPrereqTask",
            summary = "Add an ad-hoc task to one client's checklist (OB-05)")
    ResponseEntity<ObClientPrereqDtos.ObClientPrereqTaskResponse> addAdHoc(
            @PathVariable long obClientId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObClientPrereqDtos.ObClientPrereqTaskCreateRequest request,
            Authentication caller) {

        ObClientScope scope = requireVisible(obClientId, caller);
        if (!scope.mayWrite()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your onboarding role does not permit editing this client's checklist.");
        }
        requirePrecondition(obClientId, ifMatch);

        ObClientPrereqTask created = taskService.addAdHoc(obClientId, request.title(),
                request.description(), request.tatDays(), request.isMandatory(),
                identity(caller).userId());

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ObClientPrereqDtos.ObClientPrereqTaskResponse(assembler.task(created)));
    }

    // ── plumbing ──────────────────────────────────────────────────────

    private ObClientPrereqDtos.ObClientPrereqs read(long obClientId) {
        ObClientPrereqs header = prereqs.headerOf(obClientId)
                .orElseThrow(() -> new ClientPrereqsNotFoundException(obClientId));
        List<ObClientPrereqTask> rows = prereqs.tasksOf(obClientId);
        return ObClientPrereqDtos.ObClientPrereqs.of(header,
                journeys.gateStatusOf(obClientId),
                prereqs.progressOf(rows),
                assembler.tasks(rows));
    }

    /**
     * 404 for a client this caller cannot see, and for one that does not
     * exist — blueprint §2's no-existence-leak rule, which A-112 states for
     * this module.
     */
    private ObClientScope requireVisible(long obClientId, Authentication caller) {
        ObClientScope scope = ObClientScope.of(identity(caller));
        if (!visibility.isVisible(scope, obClientId)) {
            throw new ClientPrereqsNotFoundException(obClientId);
        }
        return scope;
    }

    private void requirePrecondition(long obClientId, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET this client's prereqs first and send back its ETag.");
        }
        String current = etagOf(read(obClientId));
        String candidate = ifMatch.trim();
        boolean ok = "*".equals(candidate)
                || candidate.replace("W/", "").replace("\"", "").equals(current);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This client's prerequisites changed since you read them. "
                            + "Reload before adding a task — the gate may have opened.");
        }
    }

    /**
     * Content-derived over the header and every task, which is what the
     * contract asks for and what makes the tag able to detect the race the
     * create cares about: another Admin verifying the last mandatory task.
     *
     * <p>A 32-bit hash, and two states can collide — the call every other
     * controller in this codebase already makes, recorded rather than fixed
     * on one screen.
     */
    private static String etagOf(ObClientPrereqDtos.ObClientPrereqs body) {
        return Integer.toHexString(body.hashCode());
    }

    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated onboarding-prereqs route reached with no resolvable "
                                + "caller identity"));
    }
}
