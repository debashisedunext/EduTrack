package com.edunext.edutrack.api.feature.masters.priorities;

import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-021 · the decisions S-12 makes that the schema does not.
 *
 * <p>Against mocks, so each rule can be put in the one state that exercises it.
 * {@link PriorityMasterIT} proves the half a mock cannot — that B-002's seed is
 * shaped the way this screen assumes, and that the three usage counts really do
 * read the columns they claim to.
 */
class PriorityServiceTest {

    private PriorityRepository priorities;
    private PriorityUsageRepository usage;
    private PriorityService service;

    @BeforeEach
    void setUp() {
        priorities = mock(PriorityRepository.class);
        usage = mock(PriorityUsageRepository.class);
        service = new PriorityService(priorities, usage);

        // The real save assigns the identity column. Returning the argument
        // untouched would leave `create` mapping a null id into a primitive.
        when(priorities.save(any(Priority.class))).thenAnswer(i -> {
            Priority saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99);
            }
            return saved;
        });
        when(usage.all()).thenReturn(new PriorityUsageRepository.Counts(Map.of(), Map.of(), Map.of()));
        when(usage.forLevel(anyString()))
                .thenReturn(new PriorityUsageRepository.Counts.Row(0L, 0, 0));
        when(priorities.findByIsEscalationTriggerTrue()).thenReturn(List.of());
        when(priorities.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(priorities.findAll()).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the list default is narrow, and that is the departure from B-020")
    class Listing {

        @Test
        @DisplayName("without includeInactive it asks for active rows only")
        void defaultIsActiveOnly() {
            when(priorities.findByIsActiveTrueOrderBySeqAsc())
                    .thenReturn(List.of(level("HIGH", "High", 30, true)));

            assertThat(service.list(false)).extracting(PriorityDtos.PriorityView::level)
                    .containsExactly("HIGH");
            verify(priorities, never()).findAllByOrderBySeqAscIdAsc();
        }

        /**
         * The reason the default is not "everything, let the picker filter" —
         * the rule B-020 and B-064 follow. `CreateTicketPage` filters task types
         * by `isActive` before building its picker and then maps priorities
         * straight through without a filter, because until B-021 this endpoint
         * could not return a retired one. Widening the default would put a
         * retired level into the create form and the ticket list filter, both
         * Stream C's files.
         */
        @Test
        @DisplayName("with includeInactive it asks for every row, retired ones too")
        void includeInactiveWidensIt() {
            when(priorities.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                    level("HIGH", "High", 30, true),
                    level("TRIVIAL", "Trivial", 5, false)));

            assertThat(service.list(true))
                    .extracting(PriorityDtos.PriorityView::level, PriorityDtos.PriorityView::isActive)
                    .containsExactly(tuple("HIGH", true), tuple("TRIVIAL", false));
            verify(priorities, never()).findByIsActiveTrueOrderBySeqAsc();
        }

        @Test
        @DisplayName("the three counts are keyed by code, because no table holds the id")
        void countsAreKeyedByCode() {
            when(priorities.findByIsActiveTrueOrderBySeqAsc())
                    .thenReturn(List.of(level("HIGH", "High", 30, true)));
            when(usage.all()).thenReturn(new PriorityUsageRepository.Counts(
                    Map.of("HIGH", 1204L), Map.of("HIGH", 3), Map.of("HIGH", 2)));

            PriorityDtos.PriorityView view = service.list(false).get(0);
            assertThat(view.ticketCount()).isEqualTo(1204L);
            assertThat(view.taskTypeCount()).isEqualTo(3);
            assertThat(view.slaPolicyCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a level nothing references reads as zero, not as absent")
        void unreferencedLevelsDefaultToZero() {
            when(priorities.findByIsActiveTrueOrderBySeqAsc())
                    .thenReturn(List.of(level("LOW", "Low", 10, true)));

            PriorityDtos.PriorityView view = service.list(false).get(0);
            assertThat(view.ticketCount()).isZero();
            assertThat(view.taskTypeCount()).isZero();
            assertThat(view.slaPolicyCount()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        /**
         * D-066 · the headline of this task, inverted. It used to assert the
         * refusal — "closed four-value enum … Record&lt;Level&gt;" — because the
         * contract typed {@code Level} as an enum and a fifth level would have
         * serialised into a response the generated client's own schema rejects.
         * {@code Level} is an open string now, so S-12's promise is kept, and
         * the test that recorded the gap becomes the test that proves it closed.
         */
        @Test
        @DisplayName("a fifth level is accepted — S-12's 'Admin can add levels'")
        void aFifthLevelIsAccepted() {
            service.create(write("URGENT", "Urgent", "#EF4444", null, null, null, null));

            verify(priorities).save(any());
        }

        @Test
        @DisplayName("a code that would not survive a URL or a mail subject is still refused")
        void anUnusableCodeIsRefused() {
            // Open is not unvalidated. A level code travels in filter params
            // and mail subjects, so a space or a dot changes what it means
            // rather than merely looking untidy — the contract's Level pattern,
            // mirrored.
            assertThatThrownBy(() -> service.create(
                    write("VERY URGENT", "Very urgent", "#EF4444", null, null, null, null)))
                    .isInstanceOf(PriorityService.PriorityValidationException.class)
                    .hasMessageContaining("not a usable level code");

            verify(priorities, never()).save(any());
        }

        @Test
        @DisplayName("the code is upper-cased before the uniqueness check, not after")
        void codeIsUpperCasedBeforeTheCheck() {
            when(priorities.existsByCode("CRITICAL")).thenReturn(true);

            assertThatThrownBy(() -> service.create(
                    write("critical", "Critical", "#EF4444", null, null, null, null)))
                    .isInstanceOf(PriorityService.DuplicatePriorityException.class);
        }

        @Test
        @DisplayName("a duplicate name is refused even though no index would catch it")
        void duplicateNameIsRefused() {
            when(priorities.findByNameIgnoreCase("high"))
                    .thenReturn(Optional.of(level("HIGH", "High", 30, true)));

            assertThatThrownBy(() -> service.create(
                    write("MEDIUM", "high", "#3B82F6", null, null, null, null)))
                    .isInstanceOf(PriorityService.DuplicatePriorityException.class)
                    .hasMessageContaining("indistinguishable");
        }

        @Test
        @DisplayName("an omitted seq sorts the new level to the end, a gap apart")
        void omittedSeqSortsToTheEnd() {
            when(priorities.findAll()).thenReturn(List.of(
                    level("LOW", "Low", 10, true), level("CRITICAL", "Critical", 40, true)));

            PriorityDtos.PriorityView created =
                    service.create(write("HIGH", "High", "#F59E0B", null, null, null, null));

            assertThat(created.seq()).isEqualTo((short) 50);
        }

        @Test
        @DisplayName("seq zero is honoured — it is the least-severe slot, not an absence")
        void seqZeroIsHonoured() {
            PriorityDtos.PriorityView created =
                    service.create(write("LOW", "Low", "#10B981", null, null, 0, null));

            assertThat(created.seq()).isZero();
        }

        @Test
        @DisplayName("a seq past SMALLINT is refused rather than truncated to a negative")
        void oversizeSeqIsRefused() {
            assertThatThrownBy(() -> service.create(
                    write("LOW", "Low", "#10B981", null, null, 40_000, null)))
                    .isInstanceOf(PriorityService.PriorityValidationException.class)
                    .hasMessageContaining("32767");
        }

        @Test
        @DisplayName("creating with autoEscalates true clears the flag from the incumbent")
        void createWithTheFlagMovesIt() {
            Priority incumbent = level("CRITICAL", "Critical", 40, true);
            incumbent.setId(9);
            incumbent.setEscalationTrigger(true);
            when(priorities.findByIsEscalationTriggerTrue())
                    .thenReturn(new ArrayList<>(List.of(incumbent)));

            PriorityDtos.PriorityView created =
                    service.create(write("HIGH", "High", "#F59E0B", null, true, null, null));

            assertThat(created.autoEscalates()).isTrue();
            assertThat(incumbent.isEscalationTrigger()).isFalse();
        }

        @Test
        @DisplayName("a retired level cannot be created as the escalation target")
        void aRetiredLevelCannotBeTheTarget() {
            assertThatThrownBy(() -> service.create(
                    write("HIGH", "High", "#F59E0B", null, true, null, false)))
                    .isInstanceOf(PriorityService.EscalationTargetException.class)
                    .hasMessageContaining("no picker offers");
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("an unknown id is empty, not an exception — the controller turns it into 404")
        void unknownIdIsEmpty() {
            when(priorities.findById(99)).thenReturn(Optional.empty());

            assertThat(service.update(99, patch(null, null, null, null, null, null, null)))
                    .isEmpty();
        }

        /**
         * S-12 submits the whole form on every save. Any other reading of a
         * resent code makes every edit a 409 — B-016's rule on project codes and
         * B-020's on task type codes.
         */
        @Test
        @DisplayName("resending the stored code is a no-op")
        void resendingTheCodeIsANoOp() {
            stored(1, level("HIGH", "High", 30, true));

            assertThatCode(() -> service.update(1,
                    patch("HIGH", "Elevated", null, null, null, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a changed code is refused, because nothing would cascade the rename")
        void aChangedCodeIsRefused() {
            stored(1, level("HIGH", "High", 30, true));

            assertThatThrownBy(() -> service.update(1,
                    patch("ELEVATED", null, null, null, null, null, null)))
                    .isInstanceOf(PriorityService.ImmutablePriorityCodeException.class)
                    .hasMessageContaining("orphan");
        }

        @Test
        @DisplayName("a rename can collide with another level and is refused, not applied")
        void renameCollisionIsRefused() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            Priority other = level("CRITICAL", "Critical", 40, true);
            other.setId(2);
            when(priorities.findByNameIgnoreCase("critical")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.update(1,
                    patch(null, "critical", null, null, null, null, null)))
                    .isInstanceOf(PriorityService.DuplicatePriorityException.class);
            assertThat(high.getName()).as("refused, not half-applied").isEqualTo("High");
        }

        @Test
        @DisplayName("renaming a level to its own name is not a collision with itself")
        void renamingToItsOwnNameIsFine() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(priorities.findByNameIgnoreCase("High")).thenReturn(Optional.of(high));

            assertThatCode(() -> service.update(1,
                    patch(null, "High", "#F59E0B", null, null, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("clearing defaultSlaHrs drops the level out of the §6 ladder's rung 4")
        void clearingTheDefaultSlaIsARealState() {
            Priority high = level("HIGH", "High", 30, true);
            high.setDefaultSlaHours(new BigDecimal("8.00"));
            stored(1, high);

            service.update(1, patch(null, null, null, Optional.empty(), null, null, null));

            assertThat(high.getDefaultSlaHours()).isNull();
        }

        @Test
        @DisplayName("an omitted defaultSlaHrs leaves the stored one alone")
        void omittedDefaultSlaIsLeftAlone() {
            Priority high = level("HIGH", "High", 30, true);
            high.setDefaultSlaHours(new BigDecimal("8.00"));
            stored(1, high);

            service.update(1, patch(null, "Elevated", null, null, null, null, null));

            assertThat(high.getDefaultSlaHours()).isEqualByComparingTo("8.00");
        }
    }

    // ------------------------------------------------------------------
    // the escalation target — §6's pointer
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("exactly one active level is the SLA engine's escalation target")
    class EscalationTarget {

        @Test
        @DisplayName("setting the flag clears it everywhere else — it is single-writer")
        void settingItClearsTheIncumbent() {
            Priority critical = level("CRITICAL", "Critical", 40, true);
            critical.setId(4);
            critical.setEscalationTrigger(true);
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(priorities.findByIsEscalationTriggerTrue())
                    .thenReturn(new ArrayList<>(List.of(critical)));

            service.update(1, patch(null, null, null, null, true, null, null));

            assertThat(high.isEscalationTrigger()).isTrue();
            assertThat(critical.isEscalationTrigger()).as("moved, not duplicated").isFalse();
            verify(priorities).save(critical);
        }

        @Test
        @DisplayName("clearing the last flag is refused — §6 would have nowhere to escalate to")
        void clearingTheLastFlagIsRefused() {
            Priority critical = level("CRITICAL", "Critical", 40, true);
            critical.setEscalationTrigger(true);
            stored(1, critical);

            assertThatThrownBy(() -> service.update(1,
                    patch(null, null, null, null, false, null, null)))
                    .isInstanceOf(PriorityService.EscalationTargetException.class)
                    .hasMessageContaining("no target");
            assertThat(critical.isEscalationTrigger()).isTrue();
        }

        @Test
        @DisplayName("clearing a flag that is not set is a no-op, not a refusal")
        void clearingAnUnsetFlagIsFine() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);

            assertThatCode(() -> service.update(1,
                    patch(null, null, null, null, false, null, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("retiring the flagged level is refused before its usage counts are read")
        void retiringTheFlaggedLevelIsRefused() {
            Priority critical = level("CRITICAL", "Critical", 40, true);
            critical.setEscalationTrigger(true);
            stored(1, critical);

            assertThatThrownBy(() -> service.update(1,
                    patch(null, null, null, null, null, null, false)))
                    .isInstanceOf(PriorityService.EscalationTargetException.class)
                    .hasMessageContaining("Move the escalation flag");
            assertThat(critical.isActive()).isTrue();
        }

        /**
         * The bug this suite was written to catch, and it was real: `update`
         * originally ran the retire guard and the escalation guard against the
         * entity's stored state, so a body carrying both fields passed each
         * check by being read before the other had been applied — the flag was
         * set while the level was still active, and the level was retired while
         * the flag was still unset. The end state was a retired escalation
         * target, which is exactly what both guards exist to prevent.
         */
        @Test
        @DisplayName("one patch cannot both retire a level and make it the escalation target")
        void retireAndFlagInOneRequestIsRefused() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);

            assertThatThrownBy(() -> service.update(1,
                    patch(null, null, null, null, true, null, false)))
                    .isInstanceOf(PriorityService.EscalationTargetException.class)
                    .hasMessageContaining("A retired level cannot be the escalation target");
            assertThat(high.isEscalationTrigger()).isFalse();
            assertThat(high.isActive()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // the retire guard
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("retiring")
    class Retiring {

        @Test
        @DisplayName("tickets at the level never block it — that is why level is a VARCHAR")
        void ticketsDoNotBlockARetire() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(usage.forLevel("HIGH"))
                    .thenReturn(new PriorityUsageRepository.Counts.Row(1204L, 0, 0));

            service.update(1, patch(null, null, null, null, null, null, false));

            assertThat(high.isActive()).isFalse();
        }

        @Test
        @DisplayName("SLA policy rows never block it either — the column just leaves the grid")
        void slaPoliciesDoNotBlockARetire() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(usage.forLevel("HIGH"))
                    .thenReturn(new PriorityUsageRepository.Counts.Row(0L, 0, 7));

            service.update(1, patch(null, null, null, null, null, null, false));

            assertThat(high.isActive()).isFalse();
        }

        /**
         * The asymmetry, and the reason for it: `TaskTypeService.normaliseLevel`
         * refuses a retired level as a `defaultLevel`, so a type left pointing
         * here fails validation on its next save — on a screen whose admin has
         * no way to see what caused it. One screen must not be able to put
         * another into a state it cannot get out of.
         */
        @Test
        @DisplayName("active task types defaulting to it do block it, and are named in the refusal")
        void taskTypesBlockARetire() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(usage.forLevel("HIGH"))
                    .thenReturn(new PriorityUsageRepository.Counts.Row(0L, 3, 0));
            when(usage.activeTaskTypeNamesDefaultingTo("HIGH"))
                    .thenReturn(List.of("Production Bug", "Client Bug", "Network Issue"));

            assertThatThrownBy(() -> service.update(1,
                    patch(null, null, null, null, null, null, false)))
                    .isInstanceOf(PriorityService.PriorityInUseException.class)
                    .hasMessageContaining("Production Bug")
                    .hasMessageContaining("Repoint them");
            assertThat(high.isActive()).as("refused, not half-applied").isTrue();
        }

        @Test
        @DisplayName("the refusal caps the names it lists and says how many more there are")
        void theRefusalCapsTheNames() {
            Priority high = level("HIGH", "High", 30, true);
            stored(1, high);
            when(usage.forLevel("HIGH"))
                    .thenReturn(new PriorityUsageRepository.Counts.Row(0L, 8, 0));
            when(usage.activeTaskTypeNamesDefaultingTo("HIGH"))
                    .thenReturn(List.of("A", "B", "C", "D", "E"));

            assertThatThrownBy(() -> service.update(1,
                    patch(null, null, null, null, null, null, false)))
                    .hasMessageContaining("and 3 more");
        }

        @Test
        @DisplayName("reactivating a retired level runs no retire guard")
        void reactivatingIsNotGuarded() {
            Priority retired = level("TRIVIAL", "Trivial", 5, false);
            stored(1, retired);

            service.update(1, patch(null, null, null, null, null, null, true));

            assertThat(retired.isActive()).isTrue();
            verify(usage, never()).activeTaskTypeNamesDefaultingTo(anyString());
        }

        @Test
        @DisplayName("a save that leaves isActive false alone is not a fresh retire")
        void alreadyRetiredIsNotRetiredAgain() {
            Priority retired = level("TRIVIAL", "Trivial", 5, false);
            stored(1, retired);
            when(usage.forLevel("TRIVIAL"))
                    .thenReturn(new PriorityUsageRepository.Counts.Row(0L, 4, 0));

            // Editing the colour of an already-retired level must not be refused
            // by a guard about a transition that is not happening.
            assertThatCode(() -> service.update(1,
                    patch(null, null, "#84CC16", null, null, null, false)))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void stored(int id, Priority priority) {
        priority.setId(id);
        when(priorities.findById(id)).thenReturn(Optional.of(priority));
    }

    /**
     * An id is set even where the test does not care about it: {@code toView}
     * maps it into a primitive, so a fixture without one fails on unboxing
     * rather than on the thing being asserted. {@link #stored} overrides it for
     * the tests that do care.
     */
    private static Priority level(String code, String name, int seq, boolean active) {
        Priority priority = new Priority();
        priority.setId(0);
        priority.setCode(code);
        priority.setName(name);
        priority.setColour("#F59E0B");
        priority.setSeq((short) seq);
        priority.setActive(active);
        return priority;
    }

    private static PriorityDtos.PriorityWrite write(String level, String name, String colour,
                                                    BigDecimal sla, Boolean autoEscalates,
                                                    Integer seq, Boolean isActive) {
        return new PriorityDtos.PriorityWrite(level, name, colour, sla, autoEscalates, seq, isActive);
    }

    private static PriorityDtos.PriorityPatch patch(String level, String name, String colour,
                                                    Optional<BigDecimal> sla, Boolean autoEscalates,
                                                    Integer seq, Boolean isActive) {
        return new PriorityDtos.PriorityPatch(level, name, colour, sla, autoEscalates, seq, isActive);
    }
}
