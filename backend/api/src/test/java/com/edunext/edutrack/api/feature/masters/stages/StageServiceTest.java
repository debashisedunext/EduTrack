package com.edunext.edutrack.api.feature.masters.stages;

import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-040 · the decisions S-13 tab 2 makes that the schema does not.
 *
 * <p>Against mocks, so each rule can be put in the one state that exercises it —
 * a stage nothing has entered, a stage with history, an order that inverts a
 * return path. {@code StageMasterIT} proves the half a mock cannot: that B-004's
 * seed is shaped the way this screen assumes, that the two usage counts read the
 * columns they claim to, and that the reorder's two passes really do survive
 * {@code uq_workflow_stages_seq} — which is the one assertion here that would
 * pass against any mock whatever the code did.
 */
class StageServiceTest {

    private WorkflowStageRepository stages;
    private WorkflowTemplateRepository templates;
    private RoleRepository roles;
    private StageUsageRepository usage;
    private StageService service;

    private WorkflowTemplate template;

    @BeforeEach
    void setUp() {
        stages = mock(WorkflowStageRepository.class);
        templates = mock(WorkflowTemplateRepository.class);
        roles = mock(RoleRepository.class);
        usage = mock(StageUsageRepository.class);
        service = new StageService(stages, templates, roles, usage);

        template = new WorkflowTemplate();
        template.setId(1L);
        template.setName("Standard Dev Flow");

        when(templates.existsById(1L)).thenReturn(true);
        when(templates.findById(1L)).thenReturn(Optional.of(template));
        when(usage.forTemplate(anyLong())).thenReturn(StageUsageRepository.Counts.empty());

        // Every seeded role code resolves; anything else does not.
        when(roles.findByCode(anyString())).thenAnswer(i -> {
            String code = i.getArgument(0);
            if (List.of("ADMIN", "PM", "SUPPORT", "DEVELOPER", "QA", "DEPLOYMENT").contains(code)) {
                Role role = new Role();
                role.setCode(code);
                role.setActive(true);
                return Optional.of(role);
            }
            return Optional.empty();
        });

        // The real save assigns the identity column.
        when(stages.save(any(WorkflowStage.class))).thenAnswer(i -> {
            WorkflowStage saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });
        when(stages.saveAllAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** B-004's Standard Dev Flow, trimmed to the four stages every rule here needs. */
    private List<WorkflowStage> seededRibbon() {
        List<WorkflowStage> ribbon = new ArrayList<>();
        ribbon.add(stage(10L, (short) 10, "INTAKE", "Intake", "SUPPORT", List.of()));
        ribbon.add(stage(20L, (short) 20, "TRIAGE", "Triage / Planning", "PM", List.of()));
        ribbon.add(stage(30L, (short) 30, "DEV", "Development", "DEVELOPER", List.of("TRIAGE")));
        ribbon.add(stage(40L, (short) 40, "QA", "QA / Testing", "QA", List.of("DEV")));
        return ribbon;
    }

    private WorkflowStage stage(long id, short seq, String code, String name,
                                String ownerRole, List<String> canReturnTo) {
        WorkflowStage s = new WorkflowStage();
        s.setId(id);
        s.setTemplate(template);
        s.setSeq(seq);
        s.setStageCode(code);
        s.setDisplayName(name);
        s.setOwnerRole(ownerRole);
        s.setCanReturnTo(new ArrayList<>(canReturnTo));
        return s;
    }

    private void ribbonIs(List<WorkflowStage> ribbon) {
        when(stages.findByTemplateIdOrderBySeqAsc(1L)).thenReturn(ribbon);
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the view")
    class Reads {

        @Test
        @DisplayName("position is the 1-based index, and seq is B-004's stored 10, 20, 30")
        void positionIsNotSeq() {
            ribbonIs(seededRibbon());

            List<StageDtos.StageView> view = service.list(1L).orElseThrow();

            assertThat(view).extracting(StageDtos.StageView::stageCode,
                            StageDtos.StageView::position, StageDtos.StageView::seq)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("INTAKE", 1, (short) 10),
                            org.assertj.core.api.Assertions.tuple("TRIAGE", 2, (short) 20),
                            org.assertj.core.api.Assertions.tuple("DEV", 3, (short) 30),
                            org.assertj.core.api.Assertions.tuple("QA", 4, (short) 40));
        }

        @Test
        @DisplayName("an unknown template is empty rather than an empty ribbon")
        void unknownTemplateIsAbsent() {
            when(templates.existsById(7L)).thenReturn(false);

            assertThat(service.list(7L)).isEmpty();
        }

        @Test
        @DisplayName("a stage nothing has entered is code-editable")
        void unusedStageIsEditable() {
            ribbonIs(seededRibbon());

            assertThat(service.list(1L).orElseThrow())
                    .allMatch(StageDtos.StageView::isCodeEditable);
        }

        @Test
        @DisplayName("history alone freezes the code, with no ticket standing in it")
        void historyAloneFreezesTheCode() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of("DEV", 12L), Map.of()));

            StageDtos.StageView dev = service.list(1L).orElseThrow().stream()
                    .filter(s -> s.stageCode().equals("DEV")).findFirst().orElseThrow();

            assertThat(dev.transitionCount()).isEqualTo(12);
            assertThat(dev.openTicketCount()).isZero();
            assertThat(dev.isCodeEditable()).isFalse();
        }

        @Test
        @DisplayName("an open ticket alone freezes it too — neither count is a superset")
        void openTicketAloneFreezesTheCode() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of(), Map.of("QA", 3L)));

            StageDtos.StageView qa = service.list(1L).orElseThrow().stream()
                    .filter(s -> s.stageCode().equals("QA")).findFirst().orElseThrow();

            assertThat(qa.transitionCount()).isZero();
            assertThat(qa.openTicketCount()).isEqualTo(3);
            assertThat(qa.isCodeEditable()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create appends")
    class Create {

        @Test
        @DisplayName("seq is max + 10 — a caller never chooses it")
        void appendsAtTheEnd() {
            ribbonIs(seededRibbon());

            StageDtos.StageView created = service.create(1L, new StageDtos.StageWrite(
                    "DEPLOY", "Deployment", "DEPLOYMENT", new BigDecimal("4.00"),
                    false, List.of(), "rocket"));

            assertThat(created.seq()).isEqualTo((short) 50);
            assertThat(created.position()).isEqualTo(5);
        }

        @Test
        @DisplayName("a lower-case code is stored upper-case rather than refused")
        void normalisesTheCode() {
            ribbonIs(seededRibbon());

            StageDtos.StageView created = service.create(1L, new StageDtos.StageWrite(
                    "deploy", "Deployment", "DEPLOYMENT", null, false, null, null));

            assertThat(created.stageCode()).isEqualTo("DEPLOY");
        }

        @Test
        @DisplayName("a duplicate code within the template is refused")
        void refusesADuplicate() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.create(1L, new StageDtos.StageWrite(
                    "DEV", "Development again", "DEVELOPER", null, false, null, null)))
                    .isInstanceOf(StageService.DuplicateStageException.class)
                    .hasMessageContaining("Standard Dev Flow");
        }

        @Test
        @DisplayName("the same code on another template is not a duplicate — DEV exists on two")
        void codeIsUniquePerTemplateNotGlobally() {
            ribbonIs(List.of());

            assertThatCode(() -> service.create(1L, new StageDtos.StageWrite(
                    "DEV", "Development", "DEVELOPER", null, false, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an owner role nothing matches is refused — owner_role has no foreign key")
        void refusesAnUnknownOwnerRole() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.create(1L, new StageDtos.StageWrite(
                    "DEPLOY", "Deployment", "SUPPORT_DESK", null, false, null, null)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("not an active role");
        }

        @Test
        @DisplayName("an inactive role is refused as firmly as an unknown one")
        void refusesAnInactiveRole() {
            ribbonIs(seededRibbon());
            Role retired = new Role();
            retired.setCode("QA");
            retired.setActive(false);
            when(roles.findByCode("QA")).thenReturn(Optional.of(retired));

            assertThatThrownBy(() -> service.create(1L, new StageDtos.StageWrite(
                    "REVIEW", "Review", "QA", null, false, null, null)))
                    .isInstanceOf(StageService.StageValidationException.class);
        }

        @Test
        @DisplayName("every existing stage is a legal return target for one appended last")
        void appendedStageMayReturnToAnything() {
            ribbonIs(seededRibbon());

            StageDtos.StageView created = service.create(1L, new StageDtos.StageWrite(
                    "DEPLOY", "Deployment", "DEPLOYMENT", null, false,
                    List.of("DEV", "TRIAGE"), null));

            assertThat(created.canReturnTo()).containsExactly("DEV", "TRIAGE");
        }

        @Test
        @DisplayName("an unknown template is 404, and nothing is written")
        void refusesAnUnknownTemplate() {
            when(templates.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(7L, new StageDtos.StageWrite(
                    "DEPLOY", "Deployment", "DEPLOYMENT", null, false, null, null)))
                    .isInstanceOf(StageService.TemplateNotFoundException.class);
            verify(stages, never()).save(any());
        }
    }

    // ------------------------------------------------------------------
    // return targets
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("canReturnTo is a backward target, which is the column's definition")
    class ReturnTargets {

        @Test
        @DisplayName("a forward target is refused — that is a handoff, not a return")
        void refusesAForwardTarget() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.update(1L, 20L,
                    new StageDtos.StagePatch(null, null, null, null, null, List.of("QA"), null)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("backward target");
        }

        @Test
        @DisplayName("returning to itself is refused")
        void refusesSelf() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.update(1L, 30L,
                    new StageDtos.StagePatch(null, null, null, null, null, List.of("DEV"), null)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("cannot return to itself");
        }

        @Test
        @DisplayName("a code from another template is refused — the target is this ribbon's own")
        void refusesAForeignCode() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.update(1L, 40L,
                    new StageDtos.StagePatch(null, null, null, null, null,
                            List.of("VERIFY"), null)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("not a stage on this template");
        }

        @Test
        @DisplayName("a duplicate target is collapsed rather than refused — it means one thing")
        void collapsesDuplicates() {
            ribbonIs(seededRibbon());

            StageDtos.StageView updated = service.update(1L, 40L,
                    new StageDtos.StagePatch(null, null, null, null, null,
                            List.of("DEV", "DEV", "dev"), null));

            assertThat(updated.canReturnTo()).containsExactly("DEV");
        }

        @Test
        @DisplayName("an empty list clears them; null leaves them alone")
        void emptyClearsAndNullKeeps() {
            ribbonIs(seededRibbon());

            StageDtos.StageView cleared = service.update(1L, 40L,
                    new StageDtos.StagePatch(null, null, null, null, null, List.of(), null));
            assertThat(cleared.canReturnTo()).isEmpty();

            ribbonIs(seededRibbon());
            StageDtos.StageView untouched = service.update(1L, 40L,
                    new StageDtos.StagePatch(null, "QA / Testing", null, null, null, null, null));
            assertThat(untouched.canReturnTo()).containsExactly("DEV");
        }
    }

    // ------------------------------------------------------------------
    // rename
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("stageCode is frozen once anything holds it")
    class Rename {

        @Test
        @DisplayName("a rename on an untouched stage is allowed — nothing holds the old code")
        void allowsRenameWhenUnused() {
            ribbonIs(seededRibbon());

            StageDtos.StageView updated = service.update(1L, 40L,
                    new StageDtos.StagePatch("TESTING", null, null, null, null, null, null));

            assertThat(updated.stageCode()).isEqualTo("TESTING");
        }

        @Test
        @DisplayName("history refuses it — a rename would orphan every ribbon segment")
        void refusesRenameWithHistory() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of("QA", 41L), Map.of()));

            assertThatThrownBy(() -> service.update(1L, 40L,
                    new StageDtos.StagePatch("TESTING", null, null, null, null, null, null)))
                    .isInstanceOf(StageService.ImmutableStageCodeException.class)
                    .hasMessageContaining("41")
                    .hasMessageContaining("stage-SLA scan");
        }

        @Test
        @DisplayName("an open ticket refuses it too")
        void refusesRenameWithOpenTickets() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of(), Map.of("QA", 2L)));

            assertThatThrownBy(() -> service.update(1L, 40L,
                    new StageDtos.StagePatch("TESTING", null, null, null, null, null, null)))
                    .isInstanceOf(StageService.ImmutableStageCodeException.class);
        }

        @Test
        @DisplayName("sending the same code back is a no-op, not a rename")
        void sameCodeIsNotARename() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of("QA", 41L), Map.of()));

            assertThatCode(() -> service.update(1L, 40L,
                    new StageDtos.StagePatch("QA", "QA & Testing", null, null, null, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("renaming onto a sibling's code is a duplicate, checked before usage")
        void refusesRenameOntoASibling() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.update(1L, 40L,
                    new StageDtos.StagePatch("DEV", null, null, null, null, null, null)))
                    .isInstanceOf(StageService.DuplicateStageException.class);
        }

        @Test
        @DisplayName("ownerRole may change on a live stage — that is what the master is for")
        void ownerRoleChangesFreely() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of("QA", 41L), Map.of("QA", 6L)));

            StageDtos.StageView updated = service.update(1L, 40L,
                    new StageDtos.StagePatch(null, null, "PM", null, null, null, null));

            assertThat(updated.ownerRole()).isEqualTo("PM");
        }
    }

    // ------------------------------------------------------------------
    // reorder
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("reorder is a whole-set replace")
    class Reorder {

        @Test
        @DisplayName("seq is rewritten to 10, 20, 30 in the order given")
        void rewritesSeq() {
            ribbonIs(seededRibbon());

            List<StageDtos.StageView> after = service.reorder(1L, List.of(20L, 10L, 30L, 40L));

            assertThat(after).extracting(StageDtos.StageView::stageCode,
                            StageDtos.StageView::seq, StageDtos.StageView::position)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("TRIAGE", (short) 10, 1),
                            org.assertj.core.api.Assertions.tuple("INTAKE", (short) 20, 2),
                            org.assertj.core.api.Assertions.tuple("DEV", (short) 30, 3),
                            org.assertj.core.api.Assertions.tuple("QA", (short) 40, 4));
        }

        @Test
        @DisplayName("two passes, and the first parks above the occupied range")
        void writesInTwoPasses() {
            ribbonIs(seededRibbon());

            service.reorder(1L, List.of(20L, 10L, 30L, 40L));

            // Not an implementation detail: one pass would collide with
            // uq_workflow_stages_seq the moment two rows swap, and no mock can
            // show that. StageMasterIT is where the constraint itself is proved.
            verify(stages, org.mockito.Mockito.times(2)).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("a partial list is refused rather than interpreted")
        void refusesAPartialList() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(40L, 30L)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("4 expected, 2 given");
        }

        @Test
        @DisplayName("a repeated id is refused — it changes the length of the sequence")
        void refusesADuplicateId() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(10L, 20L, 30L, 30L)))
                    .isInstanceOf(StageService.StageValidationException.class)
                    .hasMessageContaining("more than once");
        }

        @Test
        @DisplayName("an id from another template is refused")
        void refusesAForeignId() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(10L, 20L, 30L, 99L)))
                    .isInstanceOf(StageService.StageValidationException.class);
        }

        @Test
        @DisplayName("an order that would invert QA → DEV is refused, naming the pair")
        void refusesAnOrderThatInvertsAReturnPath() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(10L, 20L, 40L, 30L)))
                    .isInstanceOf(StageService.ReturnTargetDirectionException.class);
        }

        @Test
        @DisplayName("and nothing is written when it is refused")
        void writesNothingOnRefusal() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(20L, 10L, 40L, 30L)))
                    .isInstanceOf(StageService.ReturnTargetDirectionException.class);

            verify(stages, never()).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("the offending pairs travel on the exception, so the screen can point at them")
        void namesEveryOffendingPair() {
            ribbonIs(seededRibbon());

            assertThatThrownBy(() -> service.reorder(1L, List.of(30L, 40L, 10L, 20L)))
                    .isInstanceOf(StageService.ReturnTargetDirectionException.class)
                    .extracting(e -> ((StageService.ReturnTargetDirectionException) e).pairs())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                    .contains("DEV → TRIAGE");
        }

        @Test
        @DisplayName("moving a stage that has no return path is allowed on a live template")
        void allowsAReorderThatBreaksNothing() {
            ribbonIs(seededRibbon());
            when(usage.forTemplate(1L)).thenReturn(
                    new StageUsageRepository.Counts(Map.of("DEV", 80L), Map.of("QA", 5L)));

            assertThatCode(() -> service.reorder(1L, List.of(20L, 10L, 30L, 40L)))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // templates
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the selector")
    class Templates {

        @Test
        @DisplayName("inactive templates are listed — every historical ticket points at one")
        void listsInactiveTemplates() {
            WorkflowTemplate retired = new WorkflowTemplate();
            retired.setId(2L);
            retired.setName("Infra Flow");
            retired.setActive(false);
            when(templates.findAll()).thenReturn(List.of(template, retired));
            when(usage.stageCounts()).thenReturn(Map.of(1L, 8, 2L, 5));

            assertThat(service.templates())
                    .extracting(StageDtos.WorkflowTemplateView::name, StageDtos.WorkflowTemplateView::isActive,
                            StageDtos.WorkflowTemplateView::stageCount)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("Infra Flow", false, 5),
                            org.assertj.core.api.Assertions.tuple("Standard Dev Flow", true, 8));
        }
    }
}
