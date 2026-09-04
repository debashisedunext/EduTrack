package com.edunext.edutrack.api.feature.onboarding.journeys;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-102 · {@code DELETE /onboarding/journey-template-step-items/{itemId}} —
 * one Task List entry, addressed directly by its own id rather than nested
 * under its step, since deleting one needs nothing about the step beyond
 * what {@code ObJourneyTemplateService#removeStepItem} already resolves.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-template-step-items")
@Tag(name = "onboarding-journeys")
class ObJourneyTemplateStepItemController {

    private final ObJourneyTemplateService service;

    ObJourneyTemplateStepItemController(ObJourneyTemplateService service) {
        this.service = service;
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeObJourneyTemplateStepItem",
            summary = "Remove a Task List entry (OB-07)",
            description = "`409` if the owning template has ever been published — only a draft's items may be removed.")
    void remove(@PathVariable long itemId) {
        service.removeStepItem(itemId);
    }
}
