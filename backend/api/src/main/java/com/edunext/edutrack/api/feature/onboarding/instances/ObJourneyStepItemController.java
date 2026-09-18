package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObStepRowState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-111 · answering one Task List entry — {@code PATCH
 * /onboarding/journey-step-items/{itemId}}, the checkbox on OB-06's panel.
 *
 * <p><b>Its own controller because it is its own route tree.</b> The contract
 * addresses an item by <em>its</em> id, not by step-and-item, exactly as
 * {@code ObJourneyTemplateStepItemController} does one level up on the
 * template side. Hanging it off {@code /journey-steps/{stepId}} would mean a
 * path the contract does not describe, and a caller who has an item id — which
 * is what {@code GET /journey-steps/{stepId}} hands them — would have to carry
 * the step id around to use it.
 *
 * <p><b>Authorisation is the step's, not the item's.</b> {@link
 * ObJourneyStepLifecycleService#answerItem} loads the item's step and applies
 * {@code ObStepOwnership} to it: an item is only ever as writable as the
 * service it belongs to. {@code @PreAuthorize("isAuthenticated()")} says the
 * interim state explicitly, on the sibling controller's own reasoning — a
 * row-scope rule is not a {@code @PreAuthorize} expression, and {@code
 * RouteAuthorizationTest} requires every route to declare a decision.
 *
 * <p>Exceptions are translated by {@link
 * ObJourneyStepLifecycleExceptionHandler}, <b>which names this class in its
 * {@code assignableTypes}</b>. This sentence used to read "package-scoped and
 * therefore already covers this class", and it was wrong: {@code
 * assignableTypes} is a list of controller types, not a package, so for as long
 * as only the sibling controller was listed every refusal from this route —
 * closed service, unknown item, not the owner — left as a 500 carrying no
 * problem type at all. A controller added to this package has to be added
 * there too.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-step-items")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyStepItemController {

    private final ObJourneyStepLifecycleService service;

    ObJourneyStepItemController(ObJourneyStepLifecycleService service) {
        this.service = service;
    }

    @PatchMapping(value = "/{itemId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObJourneyStepItem",
            summary = "Answer a Task List entry (OB-06)",
            description = """
                    Ticking an entry records it **answered**, which is what the completion \
                    gate reads — it is satisfied by any answer, True or False, not only by \
                    True. Unticking returns the entry to unanswered and clears the remark \
                    with it, since a remark explains an answer that is no longer recorded.

                    Only the step's owner or backup owner may call this (`422` otherwise), \
                    and only while the step is open: a closed step's checklist is the record \
                    of how it closed, so a `DONE` or `SKIPPED` step answers `422` \
                    `ob-step-terminal`.

                    The answer is three-state — `true`, `false`, or `null` for not yet \
                    answered — and the remark is **optional on either answer** (PLAN.md §4, \
                    D-17). It was mandatory on a `false`; a blank one now stores as absent \
                    rather than being refused.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse answer(
            Authentication caller, @PathVariable long itemId,
            @Valid @RequestBody ObJourneyStepLifecycleDtos.ObJourneyStepItemUpdateRequest request) {
        ObJourneyStepItem item = service.answerItem(
                itemId, CallerIdentityAccess.requireUserId(caller), request.answer(), request.remark());
        return itemResponse(item);
    }

    /**
     * The OB Manager's verdict on one row — {@code PATCH
     * /onboarding/journey-step-items/{itemId}/review}.
     *
     * <p><b>A sibling route rather than a field on the one above.</b> The two
     * write different columns, are refused for different reasons and are
     * permitted to different people: answering is the owner's and is gated by
     * {@code ObStepOwnership}, reviewing is a moderator's and is gated by
     * {@code requireModerator}. Folding the verdict into the answer request
     * would mean one body whose legality depends on which fields are present,
     * and one route whose authorisation cannot be stated.
     *
     * <p><b>A verdict is not a commitment.</b> Recording one leaves the review
     * open, so the reviewer may cycle a row as many times as they like —
     * {@code Not reviewed → Verified → Rejected} — and only two things end it:
     * a rejection that carries a reason, which returns the task by itself, and
     * {@code POST /journey-steps/{stepId}/review/complete}, which the reviewer
     * presses once every row is verified.
     */
    @PatchMapping(value = "/{itemId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reviewObJourneyStepItem",
            summary = "Record an OB Manager's verdict on a Task List entry",
            description = """
                    Sets one check-list row to `VERIFIED` or `REJECTED` — or back to \
                    `NOT_REVIEWED` to take a mark back. **OB Manager or OB Admin only**; \
                    anybody else answers `403` `step-moderator-required`, and a caller with \
                    no onboarding role at all answers `404`, so the route discloses nothing.

                    Only while the step is `PENDING_REVIEW` (`422` \
                    `invalid-step-transition` otherwise), and never on a row already \
                    `VERIFIED` (`422` `ob-step-item-verified`) — a verdict is recorded once \
                    and a verified row is not shown again.

                    **A rejection must say why.** `remark` is mandatory on `REJECTED` \
                    (`422` `ob-step-reject-reason-required`) and is written to the row's own \
                    remark; on the other two states it is ignored, so verifying a row can \
                    never overwrite the implementor's note.

                    **There is no separate call to close the review.** When the last \
                    undecided row is given a verdict the task moves by itself: to `DONE` if \
                    every row is `VERIFIED`, or back to `IN_PROGRESS` with its owner if any \
                    is `REJECTED` — in which case only the rejected rows are writable again \
                    and the verified ones are locked for good.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse review(
            Authentication caller, @PathVariable long itemId,
            @Valid @RequestBody ObJourneyStepLifecycleDtos.ObStepItemReviewRequest request) {
        ObJourneyStepItem item = service.reviewItem(
                itemId, CallerIdentityAccess.requireUserId(caller),
                CallerIdentityAccess.onboardingModuleRole(caller), request.state(), request.remark());
        return itemResponse(item);
    }

    /*
     * isMandatory is not re-derived for either response. The caller just read
     * the checklist to find this id and is about to re-read it — the template
     * join to answer "was this one mandatory" would be a query spent on a
     * field neither write changes.
     */
    /**
     * Hand one row to the reviewer — the implementor's per-row Send.
     *
     * <p>The row's own button, beside the row. Nothing waits for the rest of
     * the check list: two of five finished go now and the other three keep.
     * The task's <b>Mark complete</b> is unchanged and still sends everything
     * that is ready in one press.
     */
    @PostMapping(value = "/{itemId}/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitObJourneyStepItem",
            summary = "Send one Task List entry for verification",
            description = """
                    Puts one check-list row on the implementor manager's desk. **The row's \
                    owner only** — anybody else answers `403`.

                    The row must be answered (`422` `completion-gate-not-satisfied` \
                    otherwise — there is nothing to verify about a blank line), must not \
                    already be out (`422` `ob-step-under-review`) and must not be approved \
                    (`422` `ob-step-item-verified`).

                    While it is out, that row alone is frozen: its neighbours stay writable, \
                    which is what lets somebody carry on with rows three to five while one \
                    and two are being read. The task shows as `PENDING_REVIEW` for as long \
                    as any row is out.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse submit(
            Authentication caller, @PathVariable long itemId) {
        ObJourneyStepItem item = service.submitItem(itemId, CallerIdentityAccess.requireUserId(caller));
        return itemResponse(item);
    }

    /**
     * Send one row back with its verdict — the reviewer's per-row Send.
     *
     * <p>Separate from {@code /review} on purpose: recording a verdict is a
     * thought and may be taken back, releasing it is a message somebody else
     * starts acting on.
     */
    @PostMapping(value = "/{itemId}/send-back", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "sendBackObJourneyStepItem",
            summary = "Release one verdict to the implementor",
            description = """
                    Sends one reviewed row back to whoever it belongs to, carrying the \
                    verdict recorded on it. **OB Manager or OB Admin only**; anybody else \
                    answers `403`, and a caller with no onboarding role at all answers \
                    `404`.

                    The row must be out for review (`422` `invalid-step-transition`) and \
                    must carry a verdict (`422` `completion-gate-not-satisfied`). A \
                    `REJECTED` row must say why (`422` `ob-step-reject-reason-required`).

                    **A rejection returns the row unanswered** — the claim it carried is \
                    withdrawn with the verdict, so its implementor asserts the work again \
                    rather than resubmitting what was refused. The reviewer's reason \
                    survives on the row's remark.

                    The task itself moves only when the last row comes back, so a manager \
                    may release two now and read the rest later.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse sendBack(
            Authentication caller, @PathVariable long itemId) {
        ObJourneyStepItem item = service.releaseItem(
                itemId, CallerIdentityAccess.requireUserId(caller),
                CallerIdentityAccess.onboardingModuleRole(caller));
        return itemResponse(item);
    }

    /*
     * isMandatory is not re-derived for either response. The caller just read
     * the checklist to find this id and is about to re-read it — the template
     * join to answer "was this one mandatory" would be a query spent on a
     * field neither write changes.
     */
    private static ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse itemResponse(ObJourneyStepItem item) {
        return new ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse(
                new ObJourneyStepLifecycleDtos.ObJourneyStepItem(
                        item.getId(), item.getStepId(), item.getSequence(), item.getLabel(),
                        true, item.getAnswer() != null,
                        item.getAnswer(), item.getRemark(), item.getAnsweredAt(),
                        item.getAnsweredBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(item.getAnsweredBy(), null),
                        item.getReviewState(), item.getReviewedAt(),
                        item.getReviewedBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(item.getReviewedBy(), null),
                        // Locking is the row's state, not the verdict's: a verdict recorded
                        // on a row still out for review is this reviewer's own work and
                        // stays theirs to cycle until they release it.
                        item.getRowState() == ObStepRowState.VERIFIED,
                        item.getRowState(), item.getSubmittedAt(),
                        item.getSubmittedBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(item.getSubmittedBy(), null),
                        item.getOutcomeSeenAt(), item.isUnseenOutcome()));
    }
}
