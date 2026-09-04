package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** C-102 · plain construction against a mocked {@link ObJourneyTemplateService}. */
class ObJourneyTemplateStepItemControllerTest {

    private final ObJourneyTemplateService service = mock(ObJourneyTemplateService.class);
    private final ObJourneyTemplateStepItemController controller = new ObJourneyTemplateStepItemController(service);

    @Test
    @DisplayName("mounted under /api/v1/onboarding/journey-template-step-items")
    void mountedWhereTheContractPutsIt() {
        assertThat(ObJourneyTemplateStepItemController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/onboarding/journey-template-step-items");
    }

    @Test
    @DisplayName("remove delegates the item id")
    void removeDelegates() {
        controller.remove(30L);

        verify(service).removeStepItem(30L);
    }
}
