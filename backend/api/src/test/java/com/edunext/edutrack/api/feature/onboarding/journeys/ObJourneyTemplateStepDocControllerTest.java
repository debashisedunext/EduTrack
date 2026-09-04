package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** C-102 · plain construction against a mocked {@link ObJourneyTemplateService}. */
class ObJourneyTemplateStepDocControllerTest {

    private final ObJourneyTemplateService service = mock(ObJourneyTemplateService.class);
    private final ObJourneyTemplateStepDocController controller = new ObJourneyTemplateStepDocController(service);

    @Test
    @DisplayName("mounted under /api/v1/onboarding/journey-template-step-docs")
    void mountedWhereTheContractPutsIt() {
        assertThat(ObJourneyTemplateStepDocController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/onboarding/journey-template-step-docs");
    }

    @Test
    @DisplayName("remove delegates the doc id")
    void removeDelegates() {
        controller.remove(40L);

        verify(service).removeStepDoc(40L);
    }
}
