package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-102 · plain construction against a mocked {@link ObJourneyTemplateService},
 * on {@code ModuleControllerTest}'s own convention: this proves the envelope
 * shape and the delegation, not the Spring wiring, which
 * {@code ContractConformanceTest} covers by starting the real application.
 */
class ObJourneyTemplateControllerTest {

    private final ObJourneyTemplateService service = mock(ObJourneyTemplateService.class);
    private final ObJourneyTemplateController controller = new ObJourneyTemplateController(service);

    private static Authentication authenticated(long userId) {
        DevPrincipal principal = new DevPrincipal(userId, "priya", "Priya Rao", "ADMIN", List.of(), List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static ObJourneyTemplate template(long id, boolean active) {
        ObJourneyTemplate t = new ObJourneyTemplate();
        t.setId(id);
        t.setProductId(500L);
        t.setName("ERP Rollout");
        t.setVersion(1);
        t.setActive(active);
        t.setSequence(1);
        return t;
    }

    private static ObJourneyTemplateStep step(long id, long templateId, int sequence, Long dependsOn) {
        ObJourneyTemplateStep s = new ObJourneyTemplateStep();
        s.setId(id);
        s.setTemplateId(templateId);
        s.setSequence(sequence);
        s.setName("Kickoff");
        s.setTatDays(2);
        s.setDependsOnStepId(dependsOn);
        return s;
    }

    @Test
    @DisplayName("mounted under /api/v1/onboarding")
    void mountedWhereTheContractPutsIt() {
        assertThat(ObJourneyTemplateController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/onboarding");
    }

    @Test
    @DisplayName("getDetail assembles steps, items, docs and the computed parallel groups into one response")
    void getDetailAssemblesFullTree() {
        ObJourneyTemplate t = template(1L, true);
        ObJourneyTemplateStep kickoff = step(10L, 1L, 1, null);
        ObJourneyTemplateStep migration = step(11L, 1L, 2, 10L);

        when(service.getTemplate(1L)).thenReturn(t);
        when(service.getSteps(1L)).thenReturn(List.of(kickoff, migration));
        when(service.getStepItems(10L)).thenReturn(List.of());
        when(service.getStepItems(11L)).thenReturn(List.of());
        when(service.getStepDocs(10L)).thenReturn(List.of());
        when(service.getStepDocs(11L)).thenReturn(List.of());
        when(service.parallelGroups(1L)).thenReturn(List.of(List.of(kickoff), List.of(migration)));

        var response = controller.getDetail(1L);

        assertThat(response.getHeaders().getETag())
                .as("CONVENTIONS.md §5 — detail reads carry an ETag")
                .isNotBlank();
        ObJourneyTemplateDtos.TemplateDetailResponse body = response.getBody();
        assertThat(body.data().id()).isEqualTo(1L);
        assertThat(body.data().steps()).hasSize(2);
        assertThat(body.data().steps().get(1).dependsOnStepId()).isEqualTo(10L);
        assertThat(body.data().parallelGroups())
                .as("parallel groups are step ids, layer 0 first")
                .containsExactly(List.of(10L), List.of(11L));
    }

    @Test
    @DisplayName("create wraps the service's result and stamps the caller as createdBy")
    void createDelegatesAndWraps() {
        ObJourneyTemplate created = template(2L, false);
        when(service.createTemplate(500L, "ERP Rollout", 1, null, 7L)).thenReturn(created);

        ObJourneyTemplateDtos.TemplateResponse response = controller.create(authenticated(7L),
                new ObJourneyTemplateDtos.CreateTemplateRequest(500L, "ERP Rollout", 1, null));

        assertThat(response.data().id()).isEqualTo(2L);
        verify(service).createTemplate(500L, "ERP Rollout", 1, null, 7L);
    }

    @Test
    @DisplayName("beginRevision delegates the caller as editor")
    void beginRevisionDelegates() {
        ObJourneyTemplate draft = template(3L, false);
        when(service.beginRevision(1L, 9L)).thenReturn(draft);

        ObJourneyTemplateDtos.TemplateResponse response = controller.beginRevision(authenticated(9L), 1L);

        assertThat(response.data().id()).isEqualTo(3L);
        verify(service).beginRevision(1L, 9L);
    }

    @Test
    @DisplayName("publish delegates the caller as publishedBy")
    void publishDelegates() {
        ObJourneyTemplate published = template(1L, true);
        when(service.publish(1L, 9L)).thenReturn(published);

        controller.publish(authenticated(9L), 1L);

        verify(service).publish(1L, 9L);
    }

    @Test
    @DisplayName("addStep delegates every field and wraps an empty items/docs pair")
    void addStepDelegatesAllFields() {
        ObJourneyTemplateStep created = step(20L, 1L, 1, null);
        when(service.addStep(1L, "Kickoff", "desc", 2, 6L, "PM", 8L, true, null)).thenReturn(created);

        ObJourneyTemplateDtos.StepResponse response = controller.addStep(1L,
                new ObJourneyTemplateDtos.AddStepRequest("Kickoff", "desc", 2, 6L, "PM", 8L, true, null));

        assertThat(response.data().id()).isEqualTo(20L);
        assertThat(response.data().items()).isEmpty();
        assertThat(response.data().docs()).isEmpty();
        verify(service).addStep(1L, "Kickoff", "desc", 2, 6L, "PM", 8L, true, null);
    }

    private void stubDetailFor(long templateId, ObJourneyTemplate t, List<ObJourneyTemplateStep> steps) {
        when(service.getTemplate(templateId)).thenReturn(t);
        when(service.getSteps(templateId)).thenReturn(steps);
        for (ObJourneyTemplateStep step : steps) {
            when(service.getStepItems(step.getId())).thenReturn(List.of());
            when(service.getStepDocs(step.getId())).thenReturn(List.of());
        }
        when(service.parallelGroups(templateId)).thenReturn(steps.stream().map(List::of).toList());
    }

    @Test
    @DisplayName("reorder with a wildcard If-Match passes the requested id order straight through")
    void reorderDelegates() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null), step(11L, 1L, 2, null)));

        controller.reorder(1L, "*", new ObJourneyTemplateDtos.ReorderStepsRequest(List.of(11L, 10L)));

        verify(service).reorderSteps(eq(1L), eq(List.of(11L, 10L)));
    }

    @Test
    @DisplayName("reorder without If-Match is refused (428) before the service is called — CONVENTIONS.md §5")
    void reorderWithoutIfMatchRefused() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));

        assertThatThrownBy(() ->
                controller.reorder(1L, null, new ObJourneyTemplateDtos.ReorderStepsRequest(List.of(10L))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(428));

        verify(service, never()).reorderSteps(anyLong(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("reorder with a stale If-Match is refused (412) before the service is called")
    void reorderWithStaleIfMatchRefused() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));

        assertThatThrownBy(() -> controller.reorder(1L, "\"not-the-current-tag\"",
                new ObJourneyTemplateDtos.ReorderStepsRequest(List.of(10L))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(412));

        verify(service, never()).reorderSteps(anyLong(), org.mockito.ArgumentMatchers.anyList());
    }
}
