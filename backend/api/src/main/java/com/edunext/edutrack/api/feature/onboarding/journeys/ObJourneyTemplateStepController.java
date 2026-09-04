package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
                    `true` on the column, matching every item that predates this field.""")
    ObJourneyTemplateDtos.StepItemResponse addItem(
            @PathVariable long stepId,
            @Valid @RequestBody ObJourneyTemplateDtos.AddStepItemRequest request) {
        ObJourneyTemplateStepItem item = service.addStepItem(stepId, request.label(), request.mandatory());
        return new ObJourneyTemplateDtos.StepItemResponse(ObJourneyTemplateDtos.StepItem.of(item));
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
