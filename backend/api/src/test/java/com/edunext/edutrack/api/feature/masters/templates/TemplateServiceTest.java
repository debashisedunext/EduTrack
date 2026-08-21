package com.edunext.edutrack.api.feature.masters.templates;

import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMapping;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMappingRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-041 · the decisions S-13 tab 3 makes that the schema does not.
 *
 * <p>Against mocks, so each rule can be put in the one state that exercises it —
 * a template with history and no rules, a template with rules and no history, the
 * one carrying the default flag. {@code TemplateMasterIT} proves the half a mock
 * cannot: that the generated columns really refuse a duplicate wildcard pair,
 * which is the assertion here that would pass against any repository whatever the
 * schema did.
 */
class TemplateServiceTest {

    private WorkflowTemplateRepository templates;
    private WorkflowTemplateMappingRepository mappings;
    private WorkflowStageRepository stages;
    private TaskTypeRepository taskTypes;
    private TemplateUsageRepository usage;
    private JdbcClient jdbc;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        templates = mock(WorkflowTemplateRepository.class);
        mappings = mock(WorkflowTemplateMappingRepository.class);
        stages = mock(WorkflowStageRepository.class);
        taskTypes = mock(TaskTypeRepository.class);
        usage = mock(TemplateUsageRepository.class);
        jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        service = new TemplateService(templates, mappings, stages, taskTypes, usage, jdbc);

        when(usage.stageCounts()).thenReturn(Map.of());
        when(usage.mappingCounts()).thenReturn(Map.of());
        when(usage.ticketCounts()).thenReturn(Map.of());
        when(usage.mappingsFor(anyLong())).thenReturn(List.of());
        when(templates.findByName(anyString())).thenReturn(Optional.empty());
        when(templates.existsById(anyLong())).thenReturn(true);
        when(templates.save(any())).thenAnswer(inv -> {
            WorkflowTemplate t = inv.getArgument(0);
            if (t.getId() == null) t.setId(100L);
            return t;
        });
        when(stages.findByTemplateIdOrderBySeqAsc(any())).thenReturn(List.of());
        when(taskTypes.existsById(anyInt())).thenReturn(true);
        when(mappings.findByTemplateIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        when(mappings.findByPair(any(), any())).thenReturn(Optional.empty());
        when(mappings.countByTemplateId(anyLong())).thenReturn(0L);
    }

    private WorkflowTemplate existing(long id, String name, boolean isDefault, boolean isActive) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(id);
        t.setName(name);
        t.setDefault(isDefault);
        t.setActive(isActive);
        when(templates.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private void liveStages(long templateId, int howMany) {
        List<WorkflowStage> list = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            WorkflowStage s = new WorkflowStage();
            s.setId((long) i + 1);
            s.setStageCode("S" + i);
            s.setSeq((short) ((i + 1) * 10));
            s.setDeprecated(false);
            list.add(s);
        }
        when(stages.findByTemplateIdOrderBySeqAsc(templateId)).thenReturn(list);
    }

    @Nested
    @DisplayName("in use means two different things")
    class InUse {

        /**
         * Deactivation is refused by what the template is <em>for</em>, not by
         * what it has done. A template three rules route to cannot be switched
         * off, because the next ticket on any of those pairs would resolve to a
         * template the master says is out of service.
         */
        @Test
        @DisplayName("refuses to deactivate a template routing rules point at")
        void deactivateRefusedByRules() {
            existing(1L, "Support Fast-Track", false, true);
            when(mappings.countByTemplateId(1L)).thenReturn(3L);

            assertThatThrownBy(() -> service.update(1L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, null, false)))
                    .isInstanceOf(TemplateService.TemplateInUseException.class);
        }

        /**
         * The other half, and it is the case that would be wrong if the two
         * guards were folded into one: a template with ten thousand closed
         * tickets and no live rule may be retired freely, and retiring it is the
         * right thing to do.
         */
        @Test
        @DisplayName("allows deactivating a template with history but no rules")
        void deactivateAllowedWithHistoryOnly() {
            WorkflowTemplate t = existing(1L, "Old Flow", false, true);
            when(usage.ticketCounts()).thenReturn(Map.of(1L, 8_412L));
            when(mappings.countByTemplateId(1L)).thenReturn(0L);

            assertThatCode(() -> service.update(1L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, null, false)))
                    .doesNotThrowAnyException();
            assertThat(t.isActive()).isFalse();
        }

        /**
         * Deletion is refused by history alone — the stages cascade, and every
         * historical ribbon segment resolves its name, icon and owner role
         * through them.
         */
        @Test
        @DisplayName("refuses to delete a template any ticket ever started on")
        void deleteRefusedByHistory() {
            existing(1L, "Standard Dev Flow", false, true);
            when(usage.ticketCounts()).thenReturn(Map.of(1L, 47L));

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(TemplateService.TemplateInUseException.class)
                    .hasMessageContaining("47");
            verify(templates, never()).delete(any());
        }

        @Test
        @DisplayName("refuses to delete a template rules still point at")
        void deleteRefusedByRules() {
            existing(1L, "Support Fast-Track", false, true);
            when(mappings.countByTemplateId(1L)).thenReturn(2L);

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(TemplateService.TemplateInUseException.class);
            verify(templates, never()).delete(any());
        }

        /**
         * The surviving case — B-042's, one table up. A template created by
         * mistake and caught the same afternoon.
         */
        @Test
        @DisplayName("deletes a template with neither history nor rules")
        void deleteAllowedWhenUnused() {
            WorkflowTemplate t = existing(1L, "Typo Flow", false, true);

            assertThat(service.delete(1L)).contains(true);
            verify(templates).delete(t);
        }
    }

    @Nested
    @DisplayName("exactly one default, and it can only be moved")
    class Default {

        /**
         * B-039's "at least one on-create transition must survive" rule, on a
         * different table and for the same reason: this is the only screen that
         * could undo it, and the state it would leave is every unmapped pair
         * resolving to nothing.
         */
        @Test
        @DisplayName("refuses to clear the default without naming a replacement")
        void cannotClearDefault() {
            existing(1L, "Standard Dev Flow", true, true);

            assertThatThrownBy(() -> service.update(1L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, false, null)))
                    .isInstanceOf(TemplateService.LastDefaultException.class);
        }

        @Test
        @DisplayName("refuses to deactivate the default")
        void cannotDeactivateDefault() {
            existing(1L, "Standard Dev Flow", true, true);

            assertThatThrownBy(() -> service.update(1L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, null, false)))
                    .isInstanceOf(TemplateService.LastDefaultException.class);
        }

        @Test
        @DisplayName("refuses to delete the default")
        void cannotDeleteDefault() {
            existing(1L, "Standard Dev Flow", true, true);

            assertThatThrownBy(() -> service.delete(1L))
                    .isInstanceOf(TemplateService.LastDefaultException.class);
        }

        /**
         * A workflow with nothing live routes no ticket anywhere and no screen
         * would notice: the ticket is created, resolves to the template, and
         * finds no first stage to enter. B-042's last-live-stage argument, one
         * table up.
         */
        @Test
        @DisplayName("refuses the default to a template with no live stage")
        void cannotDefaultAnEmptyTemplate() {
            existing(2L, "Blank Flow", false, true);
            liveStages(2L, 0);

            assertThatThrownBy(() -> service.update(2L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, true, null)))
                    .isInstanceOf(TemplateService.EmptyTemplateException.class);
        }

        @Test
        @DisplayName("refuses the default to an inactive template")
        void cannotDefaultAnInactiveTemplate() {
            existing(2L, "Retired Flow", false, false);
            liveStages(2L, 3);

            assertThatThrownBy(() -> service.update(2L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, true, null)))
                    .isInstanceOf(TemplateService.InactiveDefaultException.class);
        }

        /**
         * Moving it clears the old one in the same transaction, and it is one
         * statement rather than a read-modify-write.
         *
         * <p>The read-modify-write version is a lost update waiting to happen:
         * two Admins promoting two different templates would each clear what the
         * other had just set, and the table would end with two defaults or none.
         */
        @Test
        @DisplayName("clears every other default in one statement when moving it")
        void movingTheDefaultClearsTheOld() {
            WorkflowTemplate t = existing(2L, "Infra Flow", false, true);
            liveStages(2L, 5);

            service.update(2L, new TemplateDtos.WorkflowTemplatePatchRequest(null, null, true, null));

            assertThat(t.isDefault()).isTrue();
            verify(jdbc).sql(anyString());
        }
    }

    @Nested
    @DisplayName("the patch applies isActive before isDefault")
    class PatchOrder {

        /**
         * Order matters, and this is the case that proves it. A request switching
         * a template off <em>and</em> handing the default away in one call would,
         * under the other order, walk straight through a rule neither field could
         * break alone: the default would move first, and the deactivation guard
         * would then see a template no longer holding the flag.
         */
        @Test
        @DisplayName("refuses one patch that both deactivates and hands the default away")
        void cannotWalkThroughTheDefaultGuard() {
            existing(1L, "Standard Dev Flow", true, true);

            assertThatThrownBy(() -> service.update(1L,
                    new TemplateDtos.WorkflowTemplatePatchRequest(null, null, false, false)))
                    .isInstanceOf(TemplateService.LastDefaultException.class);
        }
    }

    @Nested
    @DisplayName("the routing rules")
    class Mappings {

        @Test
        @DisplayName("refuses a pair another template already claims, naming it")
        void refusesAClaimedPair() {
            existing(1L, "Infra Flow", false, true);
            WorkflowTemplateMapping claimed = new WorkflowTemplateMapping();
            claimed.setId(9L);
            claimed.setTemplateId(2L);
            claimed.setProjectId(null);
            claimed.setTaskTypeId(3);
            when(mappings.findByPair(null, 3)).thenReturn(Optional.of(claimed));
            existing(2L, "Support Fast-Track", false, true);

            assertThatThrownBy(() -> service.replaceMappings(1L,
                    new TemplateDtos.TemplateMappingReplaceRequest(
                            List.of(new TemplateDtos.TemplateMappingEntry(null, 3)))))
                    .isInstanceOf(TemplateService.MappingClaimedException.class)
                    .hasMessageContaining("Support Fast-Track");
        }

        /**
         * A pair this same template already holds is not a clash — it is the
         * ordinary case of saving a set with one rule added, and refusing it
         * would make the panel unsaveable after its first save.
         */
        @Test
        @DisplayName("does not treat a pair this template already holds as claimed")
        void ownPairIsNotAClash() {
            existing(1L, "Infra Flow", false, true);
            WorkflowTemplateMapping own = new WorkflowTemplateMapping();
            own.setId(9L);
            own.setTemplateId(1L);
            own.setTaskTypeId(3);
            when(mappings.findByPair(null, 3)).thenReturn(Optional.of(own));
            when(mappings.findByTemplateIdOrderByIdAsc(1L)).thenReturn(List.of(own));

            assertThatCode(() -> service.replaceMappings(1L,
                    new TemplateDtos.TemplateMappingReplaceRequest(
                            List.of(new TemplateDtos.TemplateMappingEntry(null, 3)))))
                    .doesNotThrowAnyException();
        }

        /**
         * An unchanged rule keeps its row, so nothing is deleted and nothing is
         * inserted. That is what makes {@code created_at} mean when the routing
         * decision was made rather than when the panel was last saved.
         */
        @Test
        @DisplayName("leaves an unchanged rule alone rather than deleting and recreating it")
        void unchangedRuleKeepsItsRow() {
            existing(1L, "Infra Flow", false, true);
            WorkflowTemplateMapping own = new WorkflowTemplateMapping();
            own.setId(9L);
            own.setTemplateId(1L);
            own.setTaskTypeId(7);
            when(mappings.findByTemplateIdOrderByIdAsc(1L)).thenReturn(List.of(own));

            service.replaceMappings(1L, new TemplateDtos.TemplateMappingReplaceRequest(
                    List.of(new TemplateDtos.TemplateMappingEntry(null, 7))));

            verify(mappings).deleteAll(List.of());
            verify(mappings).saveAll(List.of());
        }

        @Test
        @DisplayName("removes a rule left out of the set")
        void absentRuleIsRemoved() {
            existing(1L, "Infra Flow", false, true);
            WorkflowTemplateMapping own = new WorkflowTemplateMapping();
            own.setId(9L);
            own.setTemplateId(1L);
            own.setTaskTypeId(7);
            when(mappings.findByTemplateIdOrderByIdAsc(1L)).thenReturn(List.of(own));

            service.replaceMappings(1L,
                    new TemplateDtos.TemplateMappingReplaceRequest(List.of()));

            verify(mappings).deleteAll(List.of(own));
        }

        @Test
        @DisplayName("refuses a project id that names nothing, keyed to the field")
        void refusesUnknownProject() {
            existing(1L, "Infra Flow", false, true);
            when(jdbc.sql(anyString()).param(anyString(), any()).query(Long.class).list())
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.replaceMappings(1L,
                    new TemplateDtos.TemplateMappingReplaceRequest(
                            List.of(new TemplateDtos.TemplateMappingEntry(404L, null)))))
                    .isInstanceOf(TemplateService.UnknownReferenceException.class)
                    .hasMessageContaining("projectId");
        }

        @Test
        @DisplayName("refuses a task type id that names nothing")
        void refusesUnknownTaskType() {
            existing(1L, "Infra Flow", false, true);
            when(taskTypes.existsById(404)).thenReturn(false);

            assertThatThrownBy(() -> service.replaceMappings(1L,
                    new TemplateDtos.TemplateMappingReplaceRequest(
                            List.of(new TemplateDtos.TemplateMappingEntry(null, 404)))))
                    .isInstanceOf(TemplateService.UnknownReferenceException.class)
                    .hasMessageContaining("taskTypeId");
        }

        /**
         * A repeated pair is a client bug rather than an Admin decision — the
         * screen cannot produce one, since a pair is a row. Collapsed rather than
         * refused, because both entries ask for the same thing and the unique key
         * would otherwise turn a harmless duplicate into a 500 from the flush.
         */
        @Test
        @DisplayName("collapses a pair named twice instead of failing at the flush")
        void collapsesDuplicates() {
            existing(1L, "Infra Flow", false, true);

            service.replaceMappings(1L, new TemplateDtos.TemplateMappingReplaceRequest(List.of(
                    new TemplateDtos.TemplateMappingEntry(null, 7),
                    new TemplateDtos.TemplateMappingEntry(null, 7))));

            verify(mappings).saveAll(org.mockito.ArgumentMatchers.argThat(
                    (List<WorkflowTemplateMapping> saved) -> saved.size() == 1));
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("refuses a name another template already has")
        void refusesDuplicateName() {
            WorkflowTemplate other = new WorkflowTemplate();
            other.setId(1L);
            when(templates.findByName("Infra Flow")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.create(new TemplateDtos.WorkflowTemplateWriteRequest(
                    "Infra Flow", null, null, null)))
                    .isInstanceOf(TemplateService.DuplicateTemplateException.class);
        }

        /**
         * §7.4's "built by picking stages", done as A-005's own header asks for:
         * versioned by copy.
         */
        @Test
        @DisplayName("copies every stage of the source, deprecated ones included")
        void copiesTheRibbon() {
            WorkflowStage live = new WorkflowStage();
            live.setStageCode("DEV");
            live.setSeq((short) 10);
            live.setCanReturnTo(new ArrayList<>(List.of("TRIAGE")));
            WorkflowStage retired = new WorkflowStage();
            retired.setStageCode("OLD");
            retired.setSeq((short) 20);
            retired.setDeprecated(true);
            when(stages.findByTemplateIdOrderBySeqAsc(5L)).thenReturn(List.of(live, retired));

            service.create(new TemplateDtos.WorkflowTemplateWriteRequest("Copy", null, null, 5L));

            verify(stages).saveAll(org.mockito.ArgumentMatchers.argThat(
                    (List<WorkflowStage> copies) -> copies.size() == 2
                            && copies.get(1).isDeprecated()));
        }

        /**
         * {@code canReturnTo} is a mutable list behind a JSON converter. Sharing
         * the reference would make an edit to one template's stage silently
         * rewrite the other's — a bug with no error and no obvious cause.
         */
        @Test
        @DisplayName("gives the copy its own canReturnTo list, not the source's reference")
        void copiesTheReturnTargetsByValue() {
            WorkflowStage source = new WorkflowStage();
            source.setStageCode("DEV");
            source.setSeq((short) 10);
            List<String> shared = new ArrayList<>(List.of("TRIAGE"));
            source.setCanReturnTo(shared);
            when(stages.findByTemplateIdOrderBySeqAsc(5L)).thenReturn(List.of(source));

            service.create(new TemplateDtos.WorkflowTemplateWriteRequest("Copy", null, null, 5L));

            verify(stages).saveAll(org.mockito.ArgumentMatchers.argThat(
                    (List<WorkflowStage> copies) -> copies.get(0).getCanReturnTo() != shared
                            && copies.get(0).getCanReturnTo().equals(shared)));
        }

        @Test
        @DisplayName("refuses a source template that does not exist")
        void refusesUnknownSource() {
            when(templates.existsById(404L)).thenReturn(false);

            assertThatThrownBy(() -> service.create(new TemplateDtos.WorkflowTemplateWriteRequest(
                    "Copy", null, null, 404L)))
                    .isInstanceOf(TemplateService.UnknownReferenceException.class);
        }

        @Test
        @DisplayName("creates an empty template when no source is named")
        void createsEmpty() {
            service.create(new TemplateDtos.WorkflowTemplateWriteRequest("Blank", null, null, null));

            verify(stages, never()).saveAll(any());
        }

        @Test
        @DisplayName("stores a blank description as null rather than as an empty string")
        void blankDescriptionIsNull() {
            service.create(new TemplateDtos.WorkflowTemplateWriteRequest("Blank", "   ", null, null));

            verify(templates).save(org.mockito.ArgumentMatchers.argThat(
                    (WorkflowTemplate t) -> t.getDescription() == null));
        }
    }
}
