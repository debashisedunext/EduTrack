package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObRag;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
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
 * C-104 · {@code /onboarding/journey-steps} — start, complete,
 * block-with-mandatory-reason, waiting-on-client, resume, and (C-107)
 * {@code skip}; {@code complete} now runs C-106's completion gate. See
 * {@link ObJourneyStepLifecycleService}'s own class javadoc for exactly
 * which two later tasks (C-105's clock-event maths, C-119's dependency
 * graph) this still deliberately leaves alone.
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
 * the first five own — only a step's owner or backup owner may act on it —
 * is enforced inside {@link ObJourneyStepLifecycleService}, not here, on
 * {@code StageOwnership}'s own precedent that a row-scope rule is not a
 * {@code @PreAuthorize} expression. {@code @PreAuthorize("isAuthenticated()")}
 * says the interim state explicitly rather than leaving it implicit —
 * {@code RouteAuthorizationTest} requires every route to declare a
 * decision.
 *
 * <p><b>{@code skip} is different in kind, not degree.</b> "Manager/Admin
 * only" is a capability, not a row-scope rule, and {@code moduleRoles} is
 * not converted into a Spring authority ({@code
 * OnboardingScopeResolver}'s own note), so it cannot be spelled as {@code
 * @PreAuthorize(hasAuthority(...))} today either. It is checked the same
 * way ownership is — inside the service, refused with a typed exception —
 * and answers {@code 403} rather than {@code 422} for the reason {@link
 * NotAnOnboardingModeratorException}'s own javadoc gives.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-steps")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyStepLifecycleController {

    private final ObJourneyStepLifecycleService service;
    private final ObJourneyStepRagService rag;

    ObJourneyStepLifecycleController(ObJourneyStepLifecycleService service, ObJourneyStepRagService rag) {
        this.service = service;
        this.rag = rag;
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

    @PostMapping(value = "/{stepId}/skip",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "skipObJourneyStep",
            summary = "Drop a service this client does not need (Manager/Admin) (C-107)",
            description = """
                    Any non-terminal status → `SKIPPED`, with a mandatory `reason` and the \
                    actor recorded in `skippedBy`. `403` if the caller holds a role in the \
                    onboarding module and it is neither `OB_MANAGER` nor `OB_ADMIN` — a \
                    capability check, not a row-scope one, so it does not depend on the step \
                    existing; `404` if the caller holds no onboarding standing at all, \
                    answered identically to the step not existing (`ObModuleGated`). `422` \
                    `ob-step-terminal` if the step is already `DONE` or `SKIPPED`.

                    `If-Match` is optional here, unlike the OB-07 reorder route: sent, it \
                    must match this step's current `ETag` or `412`; omitted, the write is not \
                    guarded by one. `Idempotency-Key` is accepted and not yet honoured — the \
                    24-hour replay store does not exist, following every other route's \
                    identical note on this header.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepDetailResponse skip(
            Authentication caller, @PathVariable long stepId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObJourneyStepLifecycleDtos.ObStepSkipRequest request) {
        requirePreconditionIfPresent(stepId, ifMatch);
        ObJourneyStep step = service.skip(stepId, CallerIdentityAccess.requireUserId(caller),
                CallerIdentityAccess.onboardingModuleRole(caller), request.reason());
        // Always null in practice: skip's own postcondition is SKIPPED, and
        // ObRagCalculator returns null for it. Computed anyway rather than
        // hardcoded, so this response stays correct if skip's own rules
        // ever change, and so it exercises the same path getStep's ETag
        // read below does.
        return ObJourneyStepLifecycleDtos.ObJourneyStepDetailResponse.of(step, rag.ragFor(step));
    }

    /**
     * C-111 · OB-06's own read — the step, its Task List and its required
     * documents.
     *
     * <p><b>Not guarded by ownership.</b> Plan §9 says the panel is
     * "read-only for anybody else's step", which is a statement that
     * everybody may read one; {@code ObStepOwnership} governs the writes.
     * The route is nonetheless {@code isAuthenticated()} like every other on
     * this controller, and out-of-module callers get the same 404 the class
     * javadoc describes.
     *
     * <p><b>The {@code ETag} is minted over the step without its checklist</b>,
     * which is the same expression {@link #requirePreconditionIfPresent}
     * verifies against. Hashing the fuller document this route returns would
     * hand callers a token their own {@code If-Match} is then rejected for —
     * the contract calls this route "the only source of the ETag", so it has
     * to mint the one the other routes actually check.
     *
     * <p>That the hash ignores items and docs is coherent rather than a
     * compromise: the routes guarded by {@code If-Match} are the transitions
     * and the step {@code PATCH}, none of which touch the checklist, and the
     * item {@code PATCH} is deliberately unguarded — "two people ticking two
     * different items on the same service is the normal case", per its own
     * contract note.
     */
    @GetMapping(value = "/{stepId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObJourneyStep",
            summary = "One service with its Task List and documents (OB-06)",
            description = """
                    The step panel's own read. `items` carries each checklist entry with the                     `isMandatory` joined back from its template row, and `isDone` meaning                     **answered** — the completion gate is satisfied by any answer, True or                     False, so a screen reading this as "answered True" would show an item                     outstanding that the server is willing to complete over.

                    `docs` comes from the *template* step: `ob_journey_step_docs` does not                     exist, so `isSatisfied` is counted against the step's clean attachments                     rather than matched entry by entry, and `attachmentId` is always null.""")
    ResponseEntity<ObJourneyStepLifecycleDtos.ObJourneyStepDetailResponse> getStep(@PathVariable long stepId) {
        ObJourneyStep step = service.getStep(stepId);
        ObRag stepRag = rag.ragFor(step);
        ObJourneyStepLifecycleService.ObStepChecklist checklist = service.checklistFor(stepId);
        return ResponseEntity.ok()
                .eTag(etagOf(ObJourneyStepLifecycleDtos.ObJourneyStepDetail.of(step, stepRag)))
                .body(ObJourneyStepLifecycleDtos.ObJourneyStepDetailResponse.of(
                        step, stepRag, items(checklist), docs(checklist)));
    }

    private static List<ObJourneyStepLifecycleDtos.ObJourneyStepItem> items(
            ObJourneyStepLifecycleService.ObStepChecklist checklist) {
        return checklist.items().stream().map(entry -> {
            ObJourneyStepItem row = entry.row();
            return new ObJourneyStepLifecycleDtos.ObJourneyStepItem(
                    row.getId(), row.getStepId(), row.getSequence(), row.getLabel(),
                    entry.mandatory(), row.getAnswer() != null, row.getAnsweredAt(),
                    // No display name to resolve without a users read this
                    // controller has never carried; the id is what the schema
                    // asks for elsewhere on this route tree too.
                    row.getAnsweredBy() == null ? null
                            : new ObJourneyStepLifecycleDtos.UserRef(row.getAnsweredBy(), null));
        }).toList();
    }

    private static List<ObJourneyStepLifecycleDtos.ObJourneyStepDoc> docs(
            ObJourneyStepLifecycleService.ObStepChecklist checklist) {
        return checklist.docs().stream()
                .map(doc -> new ObJourneyStepLifecycleDtos.ObJourneyStepDoc(
                        doc.id(), null, doc.label(), doc.required(), doc.satisfied(), null))
                .toList();
    }

    // ------------------------------------------------------------------
    // ETag / If-Match — C-107, on ObJourneyTemplateController's own pattern
    // ------------------------------------------------------------------

    /**
     * Unlike {@code ObJourneyTemplateController#requirePrecondition}, {@code
     * If-Match} is optional on this route — the contract lists no `428`
     * response here, only `412`. Absent, the write proceeds unguarded; present,
     * it must match.
     *
     * <p><b>C-114 · {@code rag} now enters this hash, and it can change with
     * nothing but elapsed time</b> — the one field on this DTO that could,
     * where every other one only moves on a write. In the narrow window
     * where a step crosses a RAG threshold between the client's read and
     * this precondition check, a {@code skip} racing that exact moment sees
     * a stale {@code If-Match} and gets {@code 412} for a reason that is
     * not a concurrent edit. Left as-is rather than excluding {@code rag}
     * from the hash: the failure mode is "reload and reapply", and a reload
     * shows the same freshly-crossed threshold, so the retry succeeds on
     * the first attempt — self-healing, not a stuck precondition.
     */
    private void requirePreconditionIfPresent(long stepId, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return;
        }
        ObJourneyStep step = service.getStep(stepId);
        String current = etagOf(ObJourneyStepLifecycleDtos.ObJourneyStepDetail.of(step, rag.ragFor(step)));
        if (!matches(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This service changed since you read it. Reload and reapply.");
        }
    }

    /** Content-derived, not timestamp-derived — {@code ObJourneyTemplateController}'s own reasoning. */
    private static String etagOf(ObJourneyStepLifecycleDtos.ObJourneyStepDetail detail) {
        return Integer.toHexString(detail.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
