package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
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
 * ObJourneyStepLifecycleExceptionHandler}, which is package-scoped and
 * therefore already covers this class.
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

                    **`isDone` cannot express a False answer.** The column is three-state — \
                    unanswered, True, or False-with-a-mandatory-remark — and this request \
                    carries one boolean. Recording "no, and here is why" needs a contract \
                    change; see the service method's own note.""")
    ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse answer(
            Authentication caller, @PathVariable long itemId,
            @Valid @RequestBody ObJourneyStepLifecycleDtos.ObJourneyStepItemUpdateRequest request) {
        ObJourneyStepItem item = service.answerItem(
                itemId, CallerIdentityAccess.requireUserId(caller), request.isDone());

        // isMandatory is not re-derived for this response. The caller just
        // read the checklist to find this id and is about to re-read it — the
        // template join to answer "was this one mandatory" would be a query
        // spent on a field the answer does not change.
        return new ObJourneyStepLifecycleDtos.ObJourneyStepItemResponse(
                new ObJourneyStepLifecycleDtos.ObJourneyStepItem(
                        item.getId(), item.getStepId(), item.getSequence(), item.getLabel(),
                        true, item.getAnswer() != null, item.getAnsweredAt(),
                        item.getAnsweredBy() == null ? null
                                : new ObJourneyStepLifecycleDtos.UserRef(item.getAnsweredBy(), null)));
    }
}
