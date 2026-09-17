package com.edunext.edutrack.api.feature.onboarding.mytasks;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /onboarding/my-tasks} — every task still open against the caller,
 * across every project and client.
 *
 * <h2>The caller is the filter, and there is no way to say otherwise</h2>
 *
 * <p>No {@code ownerUserId} parameter exists on this route, by design rather
 * than by omission. One that did would let an implementor read a colleague's
 * queue by guessing a user id, and adding it later with a role check would put
 * an authorisation decision on a query parameter — the shape
 * {@code runReport} already refuses for the same reason.
 *
 * <p>That is also why this needs no {@code ObClientScope}: a row scope narrows
 * "which clients may I see", and this query is narrower still — the caller's
 * own tasks, which is a subset of anything a scope would allow.
 *
 * <h2>Auth: {@code isAuthenticated()}, like every onboarding controller</h2>
 *
 * <p>{@code @PreAuthorize} speaks blueprint §2's six platform roles, and the
 * division this screen wants is the module role {@code OB_STEP_OWNER}. The
 * navigation gates on that; this route does not need to, because a caller who
 * owns no task gets an empty page rather than somebody else's work. The worst a
 * wrong nav rule can do here is show an empty queue.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/onboarding/my-tasks")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObMyTaskController {

    private final ObMyTaskService service;

    ObMyTaskController(ObMyTaskService service) {
        this.service = service;
    }

    /**
     * The queue, soonest due first.
     *
     * <p>No {@code ETag}: a keyset page has no single version to tag, and
     * CONVENTIONS.md §5 puts tags on detail reads plus the three that are polled
     * or expensive. This is none of those.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObMyTasks", summary = "The implementor's open tasks")
    ObMyTaskDtos.ObMyTaskListResponse list(
            Authentication caller,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(identity(caller).userId(), isObAdmin(caller), cursor, limit);
    }

    /**
     * One task of the caller's — the header of the focused page.
     *
     * <p>404 for a task that is somebody else's, and for one that does not
     * exist. Deliberately the same answer: a 403 would confirm the task exists,
     * and {@code ob_journey_steps} ids are sequential, so the difference is an
     * enumeration oracle. CONVENTIONS.md §7.
     *
     * <p>This carries the labels — project, client, service, step — and not the
     * check list. The focused page reads
     * {@code GET /onboarding/journey-steps/{stepId}} for that, which is the
     * endpoint that already owns it and already returns the documents and the
     * effective owner beside it.
     *
     * <p>CONVENTIONS.md §5 puts an {@code ETag} on every detail read, and this
     * one earns it: the focused page is the screen somebody leaves open while
     * they work, so a revisit that has not changed is answered {@code 304}
     * rather than with the row again. Derived from the content, not from a
     * timestamp — a tag that moved when a save rewrote identical values would
     * cost a reload for nothing.
     *
     * <p>There is no {@code PATCH} here for it to guard: this resource is read
     * only, and the transitions that change the task are the journey-step
     * routes, which carry their own preconditions. The tag is a cache
     * validator, not a lost-update guard.
     */
    @GetMapping(path = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObMyTask", summary = "One of the caller's own tasks")
    ResponseEntity<ObMyTaskDtos.ObMyTaskResponse> get(
            Authentication caller,
            @PathVariable long taskId,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        ObMyTaskDtos.ObMyTask task = service.findOwnTask(identity(caller).userId(), isObAdmin(caller), taskId)
                .orElseThrow(() -> new ObMyTaskNotFoundException(taskId));

        String etag = ObMyTaskETag.of(task);
        if (ObMyTaskETag.matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(new ObMyTaskDtos.ObMyTaskResponse(task));
    }

    /**
     * {@link IllegalStateException} rather than a silent fallback: this route
     * sits behind {@code authenticated()}, so an unreadable identity means the
     * chain accepted a token this class cannot read — a bug worth a loud 500,
     * not a queue quietly answered for user zero.
     */
    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated my-tasks route reached with no resolvable caller identity"));
    }

    /**
     * Whether this caller's page also carries work awaiting their review.
     *
     * <p><b>Only {@code OB_ADMIN}.</b> Who reviews what is decided by the
     * project's own {@code implementor_manager_user_id}, in SQL, against the
     * caller's id — no role needed and nothing to go stale. This flag is the
     * narrow exception: an admin sees every submitted task, so a project whose
     * named manager has left is not a review nobody can close.
     *
     * <p>Read off {@code moduleRoles}, which is advisory and up to fifteen
     * minutes stale by the access token's own bargain. Acceptable here in a way
     * it would not be on a write: the worst a stale claim does is list a task
     * whose verdict {@code ObJourneyStepLifecycleService#requireReviewer} then
     * refuses, or leave one off a page that refreshes. Everybody else gets
     * exactly the page they got before this feature existed.
     */
    private static boolean isObAdmin(Authentication caller) {
        return CallerIdentity.of(caller)
                .flatMap(identity -> identity.moduleRole(ModuleAccessGuard.ONBOARDING))
                .filter("OB_ADMIN"::equals)
                .isPresent();
    }
}
