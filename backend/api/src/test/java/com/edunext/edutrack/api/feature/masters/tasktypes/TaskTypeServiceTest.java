package com.edunext.edutrack.api.feature.masters.tasktypes;

import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-020 · the rules the schema deliberately does not encode.
 *
 * <p>{@code TaskTypeMasterIT} proves the same decisions against real MySQL,
 * where {@code uq_task_types_code} and the three foreign keys into this table
 * have an opinion of their own. This proves the decisions themselves, without
 * Docker.
 */
class TaskTypeServiceTest {

    private TaskTypeRepository taskTypes;
    private PriorityRepository priorities;
    private TaskTypeUsageRepository usage;
    private TaskTypeService service;

    @BeforeEach
    void setUp() {
        taskTypes = mock(TaskTypeRepository.class);
        priorities = mock(PriorityRepository.class);
        usage = mock(TaskTypeUsageRepository.class);
        service = new TaskTypeService(taskTypes, priorities, usage);

        when(priorities.findByCode(anyString())).thenAnswer(call ->
                Optional.of(priority(call.getArgument(0), true)));
        when(taskTypes.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(taskTypes.existsByCode(anyString())).thenReturn(false);
        when(taskTypes.findAll()).thenReturn(List.of());
        when(usage.ticketCount(anyInt())).thenReturn(0L);
        // Stands in for AUTO_INCREMENT: without an id, mapping the saved type
        // to a view unboxes null and the failure reads as a mapping bug rather
        // than a missing stub.
        when(taskTypes.save(any())).thenAnswer(call -> {
            TaskType saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(42);
            }
            return saved;
        });
    }

    // ------------------------------------------------------------------
    // the grid
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the list")
    class Listing {

        @Test
        @DisplayName("returns retired types too — a ticket still has to render one")
        void retiredTypesAreReturned() {
            // The picker filters client-side. The grid cannot, and neither can
            // a ticket raised last year against a type since retired: filtering
            // here would leave that cell blank. B-064 states the same rule for
            // the module master, in the same words.
            TaskType live = stored(1, "CHANGE_REQUEST", "Change Request", (short) 10, true);
            TaskType retired = stored(2, "FAX_REQUEST", "Fax Request", (short) 20, false);
            when(taskTypes.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(live, retired));
            when(usage.ticketCountsByTaskType()).thenReturn(Map.of(2, 31L));

            List<TaskTypeDtos.TaskTypeView> grid = service.list();

            assertThat(grid).extracting(TaskTypeDtos.TaskTypeView::code)
                    .containsExactly("CHANGE_REQUEST", "FAX_REQUEST");
            assertThat(grid.get(1).isActive()).isFalse();
        }

        @Test
        @DisplayName("a type nothing was raised against counts zero, not null")
        void absentFromTheCountMapIsZero() {
            when(taskTypes.findAllByOrderBySeqAscIdAsc())
                    .thenReturn(List.of(stored(1, "OTHER", "Other", (short) 10, true)));
            when(usage.ticketCountsByTaskType()).thenReturn(Map.of());

            assertThat(service.list()).singleElement()
                    .extracting(TaskTypeDtos.TaskTypeView::ticketCount)
                    .isEqualTo(0L);
        }
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("the code is upper-cased before the uniqueness check, not after")
        void codeIsUpperCasedBeforeTheCheck() {
            // Otherwise `client_bug` is stored beside `CLIENT_BUG` and the two
            // are indistinguishable in every screen that renders them.
            when(taskTypes.existsByCode("CLIENT_BUG")).thenReturn(true);

            assertThatThrownBy(() -> service.create(write("client_bug", "Client Bug 2")))
                    .isInstanceOf(TaskTypeService.DuplicateTaskTypeException.class);

            verify(taskTypes, never()).save(any());
        }

        @Test
        @DisplayName("a duplicate name is refused, and keyed to the name field")
        void duplicateNameIsRefused() {
            // There is no uq_task_types_name. This rule is the only thing
            // stopping it, and ticketForm.ts's §4B.2 client-mandatory rule
            // matches on the name — so a duplicate takes that rule with it.
            when(taskTypes.findByNameIgnoreCase("client bug"))
                    .thenReturn(Optional.of(stored(6, "CLIENT_BUG", "Client Bug", (short) 60, true)));

            assertThatThrownBy(() -> service.create(write("CLIENT_DEFECT", "client bug")))
                    .isInstanceOf(TaskTypeService.DuplicateTaskTypeException.class)
                    .extracting(e -> ((TaskTypeService.DuplicateTaskTypeException) e).field())
                    .isEqualTo("name");
        }

        @Test
        @DisplayName("an omitted seq sorts the new type to the end")
        void omittedSeqGoesLast() {
            when(taskTypes.findAll()).thenReturn(List.of(
                    stored(1, "A", "A", (short) 10, true),
                    stored(2, "B", "B", (short) 110, true)));

            assertThat(service.create(write("C", "C")).seq()).isEqualTo((short) 120);
        }

        @Test
        @DisplayName("seq 0 is honoured — it is a position, not an absence")
        void zeroSeqIsAPosition() {
            TaskTypeDtos.TaskTypeWrite write = new TaskTypeDtos.TaskTypeWrite(
                    "URGENT_FIX", "Urgent Fix", null, "#EF4444", "HIGH", null, 0, null);

            assertThat(service.create(write).seq()).isZero();
        }

        @Test
        @DisplayName("a blank icon is stored as null, not as an empty string")
        void blankIconBecomesNull() {
            TaskTypeDtos.TaskTypeWrite write = new TaskTypeDtos.TaskTypeWrite(
                    "PLAIN", "Plain", "   ", "#4F46E5", "LOW", null, null, null);

            assertThat(service.create(write).icon()).isNull();
        }

        @Test
        @DisplayName("a new type is active unless it says otherwise")
        void activeByDefault() {
            assertThat(service.create(write("NEW_ONE", "New One")).isActive()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // defaultLevel — two checks, in this order
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("defaultLevel")
    class DefaultLevel {

        @Test
        @DisplayName("is validated against the priority master, not a hardcoded set")
        void unknownLevelIsRefused() {
            // B-021's whole point is that an Admin can add a level. A hardcoded
            // set here is what B-015 removed from ResourceController.
            when(priorities.findByCode("BLOCKER")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(
                    new TaskTypeDtos.TaskTypeWrite("X", "X", null, "#4F46E5", "BLOCKER", null, null, null)))
                    .isInstanceOf(TaskTypeService.TaskTypeValidationException.class)
                    .hasMessageContaining("priority master");
        }

        @Test
        @DisplayName("a retired level is refused — the create form would not offer it")
        void retiredLevelIsRefused() {
            when(priorities.findByCode("MEDIUM")).thenReturn(Optional.of(priority("MEDIUM", false)));

            assertThatThrownBy(() -> service.create(write("X", "X")))
                    .isInstanceOf(TaskTypeService.TaskTypeValidationException.class)
                    .hasMessageContaining("retired level");
        }

        @Test
        @DisplayName("a level the contract's Level enum cannot carry is refused, naming B-021")
        void levelOutsideTheContractEnumIsRefused() {
            // The trap this closes: stored happily, then serialised into a
            // response the generated client's own zod schema rejects — a screen
            // that breaks on read because of a save made on a different screen.
            when(priorities.findByCode("URGENT")).thenReturn(Optional.of(priority("URGENT", true)));

            assertThatThrownBy(() -> service.create(
                    new TaskTypeDtos.TaskTypeWrite("X", "X", null, "#4F46E5", "urgent", null, null, null)))
                    .isInstanceOf(TaskTypeService.TaskTypeValidationException.class)
                    .hasMessageContaining("B-021");
        }

        @Test
        @DisplayName("the four contract levels are exactly the four the enum declares")
        void contractLevelsMatchTheEnum() {
            assertThat(TaskTypeService.CONTRACT_LEVELS)
                    .containsExactlyInAnyOrder("LOW", "MEDIUM", "HIGH", "CRITICAL");
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Update {

        private TaskType stored;

        @BeforeEach
        void storedType() {
            stored = stored(2, "PRODUCTION_BUG", "Production Bug", (short) 20, true);
            stored.setIcon("flame");
            stored.setDefaultSlaHours(new BigDecimal("8.00"));
            when(taskTypes.findById(2)).thenReturn(Optional.of(stored));
        }

        @Test
        @DisplayName("a missing type is empty, not an exception — the caller makes it a 404")
        void missingTypeIsEmpty() {
            when(taskTypes.findById(99)).thenReturn(Optional.empty());

            assertThat(service.update(99, patch(p -> p.setName("Whatever")))).isEmpty();
        }

        @Test
        @DisplayName("resending the stored code is a no-op, not a 409")
        void resendingTheSameCodeIsFine() {
            // S-11 submits the whole form on every save. Any other reading makes
            // every edit a conflict — B-016's rule on project codes, and the
            // `u.id <> ?` B-013 documents on the resource form.
            TaskTypeDtos.TaskTypeView updated =
                    service.update(2, patch(p -> {
                        p.setCode("production_bug");
                        p.setName("Production Defect");
                    })).orElseThrow();

            assertThat(updated.name()).isEqualTo("Production Defect");
            assertThat(updated.code()).isEqualTo("PRODUCTION_BUG");
        }

        @Test
        @DisplayName("a changed code is refused")
        void changedCodeIsRefused() {
            assertThatThrownBy(() -> service.update(2, patch(p -> p.setCode("PROD_BUG"))))
                    .isInstanceOf(TaskTypeService.ImmutableTaskTypeCodeException.class);

            verify(taskTypes, never()).save(any());
        }

        @Test
        @DisplayName("renaming to a name this very type already holds is not a duplicate")
        void renamingToOwnNameIsNotADuplicate() {
            when(taskTypes.findByNameIgnoreCase("Production Bug")).thenReturn(Optional.of(stored));

            assertThat(service.update(2, patch(p -> p.setName("Production Bug"))))
                    .isPresent();
        }

        @Test
        @DisplayName("renaming onto another type's name is refused")
        void renamingOntoAnotherNameIsRefused() {
            when(taskTypes.findByNameIgnoreCase("Client Bug"))
                    .thenReturn(Optional.of(stored(6, "CLIENT_BUG", "Client Bug", (short) 60, true)));

            assertThatThrownBy(() -> service.update(2, patch(p -> p.setName("Client Bug"))))
                    .isInstanceOf(TaskTypeService.DuplicateTaskTypeException.class);
        }

        @Test
        @DisplayName("an omitted clearable field is left alone")
        void omittedClearableIsUntouched() {
            service.update(2, patch(p -> p.setName("Production Defect")));

            assertThat(stored.getIcon()).isEqualTo("flame");
            assertThat(stored.getDefaultSlaHours()).isEqualByComparingTo("8.00");
        }

        @Test
        @DisplayName("an explicit null clears it")
        void explicitNullClears() {
            service.update(2, patch(p -> {
                p.setIcon(Optional.empty());
                p.setDefaultSlaHrs(Optional.empty());
            }));

            assertThat(stored.getIcon()).isNull();
            assertThat(stored.getDefaultSlaHours()).isNull();
        }

        @Test
        @DisplayName("deactivating is never refused, however many tickets carry the type")
        void deactivatingIsNeverRefused() {
            // Refusing would leave an organisation unable to retire a type it
            // has stopped using, which is the only reason the flag exists. The
            // count is on the row so the decision is informed; the screen states
            // the consequences.
            when(usage.ticketCount(2)).thenReturn(4_812L);

            TaskTypeDtos.TaskTypeView updated =
                    service.update(2, patch(p -> p.setIsActive(false))).orElseThrow();

            assertThat(updated.isActive()).isFalse();
            assertThat(updated.ticketCount()).isEqualTo(4_812L);
        }

        @Test
        @DisplayName("a seq beyond SMALLINT is refused rather than truncated")
        void oversizedSeqIsRefused() {
            // (short) 40000 is negative, and a type that silently sorted to the
            // front of every picker is a display bug nobody traces back to the
            // save that caused it.
            assertThatThrownBy(() -> service.update(2, patch(p -> p.setSeq(40_000))))
                    .isInstanceOf(TaskTypeService.TaskTypeValidationException.class);
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static TaskTypeDtos.TaskTypeWrite write(String code, String name) {
        return new TaskTypeDtos.TaskTypeWrite(
                code, name, "bug", "#4F46E5", "MEDIUM", new BigDecimal("24.00"), null, null);
    }

    private static TaskTypeDtos.TaskTypePatch patch(
            java.util.function.Consumer<TaskTypeDtos.TaskTypePatch> fields) {

        TaskTypeDtos.TaskTypePatch patch = new TaskTypeDtos.TaskTypePatch();
        fields.accept(patch);
        return patch;
    }

    private static TaskType stored(int id, String code, String name, short seq, boolean active) {
        TaskType type = new TaskType();
        type.setId(id);
        type.setCode(code);
        type.setName(name);
        type.setColour("#4F46E5");
        type.setDefaultLevel("MEDIUM");
        type.setSeq(seq);
        type.setActive(active);
        return type;
    }

    private static Priority priority(String code, boolean active) {
        Priority priority = new Priority();
        priority.setCode(code);
        priority.setName(code);
        priority.setActive(active);
        return priority;
    }
}
