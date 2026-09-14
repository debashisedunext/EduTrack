package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * C-102 · {@code /onboarding/journey-template-steps} — a step's own
 * lifecycle, and the two checklists (items, docs) that hang off it. Sibling
 * of {@link ObJourneyTemplateController} (the template itself) and
 * {@link ObJourneyTemplateStepItemController} / {@link ObJourneyTemplateStepDocController}
 * (deleting a single item or doc directly by its own id, the same split
 * {@code /projects/{id}/members/{userId}} makes between a collection and one
 * member of it).
 *
 * <p>Auth: {@code isAuthenticated()} only — see {@link ObJourneyTemplateController}'s
 * class javadoc for why nothing stronger exists yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-template-steps")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyTemplateStepController {

    private final ObJourneyTemplateService service;

    ObJourneyTemplateStepController(ObJourneyTemplateService service) {
        this.service = service;
    }

    /**
     * The edit the seeded stages made necessary.
     *
     * <p>A Module Service is created holding one step per implementation
     * stage, each with a one-day TAT and no owner, so the admin's work on this
     * screen is <em>editing</em> steps rather than adding them — and until this
     * route existed the only edit available was remove-and-re-add, which takes
     * the step's task list and documents with it.
     *
     * <p><b>{@code If-Match} is required, and the tag is the template's.</b>
     * A step has no read of its own to draw one from; {@code getObJourneyTemplate}
     * is what the designer holds, and its tag covers every step on the
     * template. That is the same bargain {@code PUT .../steps/order} already
     * strikes one route over, and it is the right one here for a sharper
     * reason than consistency: what this edit can silently destroy is a
     * dependency somebody else just set, and the tag that notices is the one
     * covering the whole step set.
     */
    @PatchMapping(value = "/{stepId}",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObJourneyTemplateStep",
            summary = "Edit a step of a draft template (OB-07)",
            description = """
                    TAT, owner, backup, sign-off, description and the step dependency. \
                    Neither the implementation stage nor the name can be changed — a step \
                    IS its stage, and swapping it is remove plus add, which is also what \
                    makes it obvious that the task list goes with it. `409` if the \
                    template has ever been published, or if the new dependency would \
                    make the step wait on itself through a chain.""")
    ObJourneyTemplateDtos.StepResponse update(
            @PathVariable long stepId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObJourneyTemplateDtos.UpdateStepRequest request) {

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the template first and send back its ETag.");
        }
        ObJourneyTemplateStep updated = service.updateStep(stepId, request.name(), request.description(),
                request.tatDays(), request.ownerUserId(), request.requiresSignoff(),
                request.dependsOnStepId(), request.clearDependsOn(), request.clearOwnerUserId());
        return new ObJourneyTemplateDtos.StepResponse(ObJourneyTemplateDtos.StepDetail.of(updated,
                service.getStepItems(stepId).stream().map(ObJourneyTemplateDtos.StepItem::of).toList(),
                service.getStepDocs(stepId).stream().map(ObJourneyTemplateDtos.StepDoc::of).toList()));
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeObJourneyTemplateStep",
            summary = "Remove a step from a draft template (OB-07)",
            description = """
                    `409` naming the dependent step ids if another step in the same \
                    template still depends on this one — re-point them first. The \
                    migration's own FK is `RESTRICT`, so this check exists to name the \
                    dependents rather than let the database refuse with a constraint name. \
                    `409` (a different case) if the template has ever been published.""")
    void remove(@PathVariable long stepId) {
        service.removeStep(stepId);
    }

    @PostMapping(value = "/{stepId}/items",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addObJourneyTemplateStepItem",
            summary = "Add a Task List entry to a step (OB-07)",
            description = """
                    `mandatory` (C-102) — `false` marks the item one the instance-side \
                    completion gate (C-106) will not require an answer to. Defaults to \
                    `true` on the column, matching every item that predates this field.

                    B-131 — **accepted on a service already in use.** Unlike every other \
                    edit to a journey template, adding a Task List entry is allowed on the \
                    active version, and the item is back-filled onto every journey currently \
                    running from it; `backfilledJourneyCount` reports how many. A *retired* \
                    version still refuses with `409`.""")
    ObJourneyTemplateDtos.StepItemResponse addItem(
            @PathVariable long stepId,
            @Valid @RequestBody ObJourneyTemplateDtos.AddStepItemRequest request) {
        ObJourneyTemplateService.StepItemAdded added =
                service.addStepItem(stepId, request.label(), request.mandatory());
        return new ObJourneyTemplateDtos.StepItemResponse(
                ObJourneyTemplateDtos.StepItem.of(added.item()), added.backfilledJourneyCount());
    }

    @PostMapping(value = "/{stepId}/docs",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addObJourneyTemplateStepDoc",
            summary = "Add a required-document entry to a step (OB-07)",
            description = """
                    The architect's addition 7 (plan §1.1): a step can't complete with \
                    required documents missing. `required: false` rows are documents the \
                    owner may attach without gating completion.""")
    ObJourneyTemplateDtos.StepDocResponse addDoc(
            @PathVariable long stepId,
            @Valid @RequestBody ObJourneyTemplateDtos.AddStepDocRequest request) {
        ObJourneyTemplateStepDoc doc = service.addStepDoc(stepId, request.label(), request.required());
        return new ObJourneyTemplateDtos.StepDocResponse(ObJourneyTemplateDtos.StepDoc.of(doc));
    }
}
