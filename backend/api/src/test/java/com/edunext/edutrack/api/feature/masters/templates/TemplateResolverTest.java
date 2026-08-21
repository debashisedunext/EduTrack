package com.edunext.edutrack.api.feature.masters.templates;

import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMapping;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMappingRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-041 · §4A.9's ladder — which workflow template a project × task type gets.
 *
 * <p>Against mocks, so each rung can be put in the one state that exercises it.
 * {@code TemplateMasterIT} proves the half a mock cannot: that the generated
 * columns really do refuse a second rule on the same pair, which is the one
 * assertion here that would pass against any repository whatever the schema did.
 */
class TemplateResolverTest {

    private WorkflowTemplateMappingRepository mappings;
    private WorkflowTemplateRepository templates;
    private TemplateResolver resolver;

    @BeforeEach
    void setUp() {
        mappings = mock(WorkflowTemplateMappingRepository.class);
        templates = mock(WorkflowTemplateRepository.class);
        resolver = new TemplateResolver(mappings, templates);

        when(templates.findById(anyLong())).thenAnswer(inv -> Optional.of(template(inv.getArgument(0), "T" + inv.getArgument(0))));
        when(templates.findByIsDefaultTrueAndIsActiveTrue()).thenReturn(List.of());
    }

    private static WorkflowTemplate template(long id, String name) {
        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(id);
        t.setName(name);
        t.setActive(true);
        return t;
    }

    private static WorkflowTemplateMapping rule(long id, long templateId, Long projectId, Integer taskTypeId) {
        WorkflowTemplateMapping m = new WorkflowTemplateMapping();
        m.setId(id);
        m.setTemplateId(templateId);
        m.setProjectId(projectId);
        m.setTaskTypeId(taskTypeId);
        return m;
    }

    /** Whatever {@code findCandidates} returns; the resolver does its own ranking. */
    private void candidates(WorkflowTemplateMapping... rules) {
        when(mappings.findCandidates(any(), any())).thenReturn(List.of(rules));
    }

    @Nested
    @DisplayName("the four rungs")
    class Rungs {

        @Test
        @DisplayName("an exact pair beats every wildcard")
        void exactWins() {
            candidates(
                    rule(1, 10L, null, 3),
                    rule(2, 20L, 5L, null),
                    rule(3, 30L, 5L, 3));

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isEqualTo(30L);
            assertThat(answer.rung()).isEqualTo("EXACT");
            assertThat(answer.mappingId()).isEqualTo(3L);
        }

        /**
         * The tie-break, and the one decision in this class that could
         * defensibly have gone the other way.
         *
         * <p>Both rules rank one apiece — one names the project, the other the
         * task type — and SQL cannot express "project beats task type" as part of
         * a specificity expression without stating the rule twice. It is decided
         * here: a project is the narrower population, so "everything on this
         * engagement follows that flow" outranks "this kind of work usually
         * follows this one".
         *
         * <p>Reversing the precedence would route real tickets differently and
         * would break nothing else, which is exactly why it needs a test rather
         * than a comment.
         */
        @Test
        @DisplayName("a project rule beats a task-type rule")
        void projectBeatsTaskType() {
            candidates(
                    rule(1, 10L, null, 3),
                    rule(2, 20L, 5L, null));

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isEqualTo(20L);
            assertThat(answer.rung()).isEqualTo("PROJECT");
        }

        @Test
        @DisplayName("a task-type rule wins when no project rule applies")
        void taskTypeWins() {
            candidates(rule(1, 10L, null, 3));

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isEqualTo(10L);
            assertThat(answer.rung()).isEqualTo("TASK_TYPE");
        }

        /**
         * An explicit catch-all is a rule somebody wrote and is <em>not</em> the
         * same as the default flag. They usually name the same template, and the
         * difference shows the day somebody changes one of them — which is why
         * the two report different rungs.
         */
        @Test
        @DisplayName("a catch-all rule beats the default flag and says so")
        void catchAllBeatsDefault() {
            candidates(rule(1, 10L, null, null));
            when(templates.findByIsDefaultTrueAndIsActiveTrue())
                    .thenReturn(List.of(template(99L, "Default")));

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isEqualTo(10L);
            assertThat(answer.rung()).isEqualTo("ANY");
            assertThat(answer.mappingId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("the fallback")
    class Fallback {

        @Test
        @DisplayName("falls through to the default template when no rule matches")
        void defaultWins() {
            candidates();
            when(templates.findByIsDefaultTrueAndIsActiveTrue())
                    .thenReturn(List.of(template(99L, "Standard Dev Flow")));

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isEqualTo(99L);
            assertThat(answer.templateName()).isEqualTo("Standard Dev Flow");
            assertThat(answer.rung()).isEqualTo("DEFAULT");
            // No rule was involved, so there is nothing for the screen to
            // highlight. A mapping id here would point at a row that did not
            // decide anything.
            assertThat(answer.mappingId()).isNull();
        }

        /**
         * The state {@code TemplateService} refuses to create and the schema
         * permits anyway — {@code is_default} is a plain {@code TINYINT} with an
         * index and no constraint, so a migration or a hand-written
         * {@code UPDATE} can leave none set.
         *
         * <p>Reported honestly rather than hidden behind a hard-coded fallback: a
         * pair that routes nowhere is a fact the screen can show and an invented
         * answer is not.
         */
        @Test
        @DisplayName("answers NONE rather than inventing a template")
        void noneWhenNothingIsDefault() {
            candidates();

            TemplateDtos.TemplateResolution answer = resolver.explain(5L, 3);

            assertThat(answer.templateId()).isNull();
            assertThat(answer.templateName()).isNull();
            assertThat(answer.rung()).isEqualTo("NONE");
        }

        /**
         * Two defaults is a state the service forbids and the database allows,
         * which is why {@code findByIsDefaultTrueAndIsActiveTrue} returns a list.
         * Picking the lowest id makes a database in that state resolve the same
         * way twice rather than by whatever the optimiser reached first — a
         * ticket raised on Monday and one on Tuesday getting different ribbons is
         * the failure worth ruling out.
         */
        @Test
        @DisplayName("is deterministic when two templates carry the default flag")
        void twoDefaultsResolveDeterministically() {
            candidates();
            when(templates.findByIsDefaultTrueAndIsActiveTrue())
                    .thenReturn(List.of(template(99L, "Second"), template(7L, "First")));

            assertThat(resolver.explain(5L, 3).templateId()).isEqualTo(7L);
            assertThat(resolver.explain(5L, 3).templateId()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("the narrow entry point")
    class Resolve {

        @Test
        @DisplayName("hands back just the id, for the caller that wants the answer")
        void resolveReturnsId() {
            candidates(rule(1, 10L, 5L, 3));

            assertThat(resolver.resolve(5L, 3)).contains(10L);
        }

        @Test
        @DisplayName("is empty rather than zero when nothing resolves")
        void resolveIsEmpty() {
            candidates();

            assertThat(resolver.resolve(5L, 3)).isEmpty();
        }
    }
}
