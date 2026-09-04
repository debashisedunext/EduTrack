package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C-102 · plain construction against a mocked {@link ObJourneyTemplateService}, {@code ModuleControllerTest}'s convention. */
class ObJourneyTemplateStepControllerTest {

    private final ObJourneyTemplateService service = mock(ObJourneyTemplateService.class);
    private final ObJourneyTemplateStepController controller = new ObJourneyTemplateStepController(service);

    @Test
    @DisplayName("mounted under /api/v1/onboarding/journey-template-steps")
    void mountedWhereTheContractPutsIt() {
        assertThat(ObJourneyTemplateStepController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/onboarding/journey-template-steps");
    }

    @Test
    @DisplayName("remove delegates the step id")
    void removeDelegates() {
        controller.remove(10L);

        verify(service).removeStep(10L);
    }

    @Test
    @DisplayName("addItem carries the mandatory flag through and wraps the result")
    void addItemDelegatesMandatoryFlag() {
        ObJourneyTemplateStepItem item = new ObJourneyTemplateStepItem();
        item.setId(30L);
        item.setStepId(10L);
        item.setSequence(1);
        item.setLabel("Signed requirement sheet received");
        item.setMandatory(false);
        when(service.addStepItem(10L, "Signed requirement sheet received", false)).thenReturn(item);

        ObJourneyTemplateDtos.StepItemResponse response = controller.addItem(10L,
                new ObJourneyTemplateDtos.AddStepItemRequest("Signed requirement sheet received", false));

        assertThat(response.data().id()).isEqualTo(30L);
        assertThat(response.data().mandatory()).isFalse();
        verify(service).addStepItem(10L, "Signed requirement sheet received", false);
    }

    @Test
    @DisplayName("addDoc carries the required flag through and wraps the result")
    void addDocDelegatesRequiredFlag() {
        ObJourneyTemplateStepDoc doc = new ObJourneyTemplateStepDoc();
        doc.setId(40L);
        doc.setStepId(10L);
        doc.setSequence(1);
        doc.setLabel("Signed requirement sheet");
        doc.setRequired(true);
        when(service.addStepDoc(10L, "Signed requirement sheet", true)).thenReturn(doc);

        ObJourneyTemplateDtos.StepDocResponse response = controller.addDoc(10L,
                new ObJourneyTemplateDtos.AddStepDocRequest("Signed requirement sheet", true));

        assertThat(response.data().id()).isEqualTo(40L);
        assertThat(response.data().required()).isTrue();
        verify(service).addStepDoc(10L, "Signed requirement sheet", true);
    }
}
