package com.edunext.edutrack.api.feature.onboarding.journeys;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-102 · {@code DELETE /onboarding/journey-template-step-docs/{docId}} —
 * one required-document entry, addressed directly by its own id, the same
 * split {@link ObJourneyTemplateStepItemController} makes for step items.
 *
 * <p>Auth: {@code isAuthenticated()} only — see {@link ObJourneyTemplateController}'s
 * class javadoc for why nothing stronger exists yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-template-step-docs")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyTemplateStepDocController {

    private final ObJourneyTemplateService service;

    ObJourneyTemplateStepDocController(ObJourneyTemplateService service) {
        this.service = service;
    }

    @DeleteMapping("/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeObJourneyTemplateStepDoc",
            summary = "Remove a required-document entry (OB-07)",
            description = "`409` if the owning template has ever been published — only a draft's docs may be removed.")
    void remove(@PathVariable long docId) {
        service.removeStepDoc(docId);
    }
}
