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

        ObJourneyTemplateDtos.ObJourneyTemplateResponse response = controller.create(authenticated(7L),
                new ObJourneyTemplateDtos.CreateTemplateRequest(500L, "ERP Rollout", 1, null));

        assertThat(response.data().id()).isEqualTo(2L);
        verify(service).createTemplate(500L, "ERP Rollout", 1, null, 7L);
    }

    @Test
    @DisplayName("beginRevision delegates the caller as editor")
    void beginRevisionDelegates() {
        ObJourneyTemplate draft = template(3L, false);
        when(service.beginRevision(1L, 9L)).thenReturn(draft);

        ObJourneyTemplateDtos.ObJourneyTemplateResponse response = controller.beginRevision(authenticated(9L), 1L);

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

    /**
     * The step is now named by an implementation stage id rather than by a
     * string, so what this asserts is that the id reaches the service — a
     * controller that dropped it would produce a step belonging to no stage,
     * which the database accepts (the column is nullable for the legacy rows)
     * and which nothing downstream could tell from a hand-typed step.
     */
    @Test
    @DisplayName("addTask passes the stage group and the name through, with an empty items/docs pair")
    void addTaskDelegatesAllFields() {
        ObJourneyTemplateStep created = step(20L, 1L, 1, null);
        when(service.addTask(4L, "Create tenant", "desc", 2, 6L, true, null))
                .thenReturn(created);

        ObJourneyTemplateDtos.StepResponse response = controller.addTask(4L,
                new ObJourneyTemplateDtos.AddTaskRequest("Create tenant", "desc", 2, 6L, true, null));

        assertThat(response.data().id()).isEqualTo(20L);
        assertThat(response.data().items()).isEmpty();
        assertThat(response.data().docs()).isEmpty();
        verify(service).addTask(4L, "Create tenant", "desc", 2, 6L, true, null);
    }

    private void stubDetailFor(long templateId, ObJourneyTemplate t, List<ObJourneyTemplateStep> steps) {
        when(service.getTemplate(templateId)).thenReturn(t);
        when(service.getSteps(templateId)).thenReturn(steps);
        // The detail read carries the stage groups now. Empty is enough here:
        // these tests are about the precondition and the delegation, and a
        // group list would only add noise to the ETag they compare.
        when(service.getStages(templateId)).thenReturn(List.of());
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
        // The route takes a stage; the tag it checks is that stage's template's.
        when(service.templateIdOfStage(7L)).thenReturn(1L);

        controller.reorderTasks(7L, "*", new ObJourneyTemplateDtos.ReorderTasksRequest(List.of(11L, 10L)));

        verify(service).reorderTasks(eq(7L), eq(List.of(11L, 10L)));
    }

    @Test
    @DisplayName("reorder without If-Match is refused (428) before the service is called — CONVENTIONS.md §5")
    void reorderWithoutIfMatchRefused() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));
        when(service.templateIdOfStage(7L)).thenReturn(1L);

        assertThatThrownBy(() ->
                controller.reorderTasks(7L, null, new ObJourneyTemplateDtos.ReorderTasksRequest(List.of(10L))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(428));

        verify(service, never()).reorderTasks(anyLong(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("reorder with a stale If-Match is refused (412) before the service is called")
    void reorderWithStaleIfMatchRefused() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));
        when(service.templateIdOfStage(7L)).thenReturn(1L);

        assertThatThrownBy(() -> controller.reorderTasks(7L, "\"not-the-current-tag\"",
                new ObJourneyTemplateDtos.ReorderTasksRequest(List.of(10L))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(412));

        verify(service, never()).reorderTasks(anyLong(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("C-124 · the rename is refused (428) without an If-Match, before the service is called")
    void updateModuleServiceWithoutIfMatchRefused() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));

        assertThatThrownBy(() -> controller.updateModuleService(1L, null,
                new ObJourneyTemplateDtos.UpdateModuleServiceRequest("Renamed", null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(428));

        verify(service, never()).updateModuleService(anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("C-124 · the rename delegates name and productId once the precondition holds")
    void updateModuleServiceDelegates() {
        stubDetailFor(1L, template(1L, false), List.of(step(10L, 1L, 1, null)));
        when(service.updateModuleService(1L, "Renamed", 42L)).thenReturn(template(1L, false));

        controller.updateModuleService(1L, "*",
                new ObJourneyTemplateDtos.UpdateModuleServiceRequest("Renamed", 42L));

        verify(service).updateModuleService(1L, "Renamed", 42L);
    }

    /**
     * The asymmetry is deliberate and worth pinning: {@code PATCH} requires a
     * precondition and {@code DELETE} takes none. A tag derived from the
     * template's content cannot see the race a delete actually cares about — a
     * client boarding between the read and the call — so requiring one would
     * refuse edits that do not matter while still missing the one that does.
     */
    @Test
    @DisplayName("C-124 · the delete takes no If-Match and delegates straight through")
    void deleteModuleServiceDelegates() {
        controller.deleteModuleService(1L);

        verify(service).deleteModuleService(1L);
    }
}
