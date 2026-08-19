package com.edunext.edutrack.api.feature.masters.statuses;

import com.edunext.edutrack.domain.masters.Status;
import com.edunext.edutrack.domain.masters.StatusRepository;
import com.edunext.edutrack.domain.masters.WorkflowTransition;
import com.edunext.edutrack.domain.masters.WorkflowTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * B-039 · the decisions S-13 tab 1 makes that the schema does not.
 *
 * <p>Against mocks, so each rule can be put in the one state that exercises it.
 * {@code StatusMasterIT} proves the half a mock cannot — that B-003's seed is
 * shaped the way this screen assumes, that the migration's category backfill
 * landed, and that the two usage counts really do read the columns they claim to.
 */
class StatusServiceTest {

    private StatusRepository statuses;
    private WorkflowTransitionRepository transitions;
    private StatusUsageRepository usage;
    private StatusService service;

    @BeforeEach
    void setUp() {
        statuses = mock(StatusRepository.class);
        transitions = mock(WorkflowTransitionRepository.class);
        usage = mock(StatusUsageRepository.class);
        service = new StatusService(statuses, transitions, usage);

        // The real save assigns the identity column. Returning the argument
        // untouched would leave `create` mapping a null id into a primitive.
        when(statuses.save(any(Status.class))).thenAnswer(i -> {
            Status saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99);
            }
            return saved;
        });
        when(usage.all()).thenReturn(new StatusUsageRepository.Counts(Map.of(), Map.of()));
        when(usage.forCode(anyString()))
                .thenReturn(new StatusUsageRepository.Counts.Row(0L, 0));
        when(statuses.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(statuses.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of());
        when(transitions.findAllByOrderByIdAsc()).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the list default is narrow, following B-021 rather than B-020")
    class Listing {

        @Test
        @DisplayName("without includeInactive it asks for active rows only")
        void defaultIsActiveOnly() {
            when(statuses.findByIsActiveTrueOrderBySeqAsc())
                    .thenReturn(List.of(status("ON_HOLD", "On Hold", 30, true)));

            assertThat(service.list(false)).extracting(StatusDtos.StatusView::code)
                    .containsExactly("ON_HOLD");
            verify(statuses, never()).findAllByOrderBySeqAscIdAsc();
        }

        @Test
        @DisplayName("includeInactive asks for every row, retired ones included")
        void includeInactiveWidens() {
            when(statuses.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                    status("NEW", "New", 10, true),
                    status("REWORK", "Rework", 50, false)));

            assertThat(service.list(true)).extracting(StatusDtos.StatusView::code)
                    .containsExactly("NEW", "REWORK");
            verify(statuses, never()).findByIsActiveTrueOrderBySeqAsc();
        }

        /**
         * The ordering call, pinned because it is the one a later tidy-up would
         * "improve". Grouping by category here would make this list and the
         * ticket screens' status filters disagree about what follows what.
         */
        @Test
        @DisplayName("rows come back in the repository's seq order, not grouped by category")
        void orderIsSeqNotCategory() {
            when(statuses.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                    status("NEW", "New", 10, true),                     // TODO
                    status("IN_PROGRESS", "In Progress", 20, true),     // IN_PROGRESS
                    status("REOPENED", "Reopened", 80, true)));         // TODO again

            assertThat(service.list(true)).extracting(StatusDtos.StatusView::code)
                    .containsExactly("NEW", "IN_PROGRESS", "REOPENED");
        }

        @Test
        @DisplayName("both counts are attached from the grouped read, defaulting to zero")
        void countsAreAttached() {
            when(statuses.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                    status("NEW", "New", 10, true),
                    status("CLOSED", "Closed", 70, true)));
            when(usage.all()).thenReturn(new StatusUsageRepository.Counts(
                    Map.of("NEW", 42L), Map.of("NEW", 7)));

            assertThat(service.list(true))
                    .extracting(StatusDtos.StatusView::code,
                            StatusDtos.StatusView::ticketCount,
                            StatusDtos.StatusView::transitionCount)
                    .containsExactly(
                            tuple("NEW", 42L, 7),
                            tuple("CLOSED", 0L, 0));
        }

        @Test
        @DisplayName("deactivatedTransitions is null on every read — it describes an event")
        void deactivatedIsNullOnReads() {
            when(statuses.findById(1)).thenReturn(Optional.of(status("NEW", "New", 10, true)));

            assertThat(service.find(1)).get()
                    .extracting(StatusDtos.StatusView::deactivatedTransitions)
                    .isNull();
        }
    }

    // ------------------------------------------------------------------
    // the ninth-code refusal
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a ninth status is refused, because the contract's enum types tickets.status")
    class ClosedCodeSet {

        @Test
        @DisplayName("a code outside the eight is a 400 naming what has to change")
        void ninthCodeRefused() {
            assertThatThrownBy(() -> service.create(
                    write("TRIAGED", "Triaged", "IN_PROGRESS", "#4F46E5")))
                    .isInstanceOf(StatusService.StatusValidationException.class)
                    .hasMessageContaining("TRIAGED")
                    .hasMessageContaining("StatusCode")
                    .hasMessageContaining("Stream C");
        }

        @Test
        @DisplayName("the refusal keys on the code field, so the form can point at the input")
        void refusalIsFieldKeyed() {
            assertThatThrownBy(() -> service.create(
                    write("TRIAGED", "Triaged", "TODO", "#4F46E5")))
                    .isInstanceOfSatisfying(StatusService.StatusValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("code"));
        }

        @Test
        @DisplayName("nothing is written when the code is refused")
        void nothingWrittenOnRefusal() {
            assertThatThrownBy(() -> service.create(
                    write("TRIAGED", "Triaged", "TODO", "#4F46E5")))
                    .isInstanceOf(StatusService.StatusValidationException.class);
            verify(statuses, never()).save(any());
        }

        @Test
        @DisplayName("a lower-case code inside the eight is accepted and upper-cased")
        void lowerCaseIsNormalised() {
            when(statuses.existsByCode("ON_HOLD")).thenReturn(false);

            assertThat(service.create(write("on_hold", "On Hold", "IN_PROGRESS", "#F59E0B")).code())
                    .isEqualTo("ON_HOLD");
        }

        /**
         * The set this class guards is exactly the contract's enum. Asserted
         * against the literal eight rather than against the constant, so that
         * editing the constant without editing the contract fails here.
         */
        @Test
        @DisplayName("the guarded set is the contract's eight")
        void theSetIsTheContracts() {
            assertThat(StatusService.CONTRACT_CODES).containsExactlyInAnyOrder(
                    "NEW", "IN_PROGRESS", "ON_HOLD", "AWAITING_INFO",
                    "REWORK", "RESOLVED", "CLOSED", "REOPENED");
        }
    }

    // ------------------------------------------------------------------
    // uniqueness and immutability
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("code is unique and permanent; name is unique case-insensitively")
    class Identity {

        @Test
        @DisplayName("a duplicate code is refused and points at reactivating instead")
        void duplicateCode() {
            when(statuses.existsByCode("ON_HOLD")).thenReturn(true);

            assertThatThrownBy(() -> service.create(
                    write("ON_HOLD", "On Hold", "IN_PROGRESS", "#F59E0B")))
                    .isInstanceOf(StatusService.DuplicateStatusException.class)
                    .hasMessageContaining("reactivate");
        }

        @Test
        @DisplayName("a duplicate name is refused, whatever its case")
        void duplicateName() {
            when(statuses.findByNameIgnoreCase("on hold"))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));

            assertThatThrownBy(() -> service.create(
                    write("REWORK", "on hold", "IN_PROGRESS", "#8B5CF6")))
                    .isInstanceOfSatisfying(StatusService.DuplicateStatusException.class,
                            e -> assertThat(e.field()).isEqualTo("name"));
        }

        @Test
        @DisplayName("a different code on the patch is refused — a rename would orphan tickets")
        void codeIsImmutable() {
            when(statuses.findById(1)).thenReturn(Optional.of(status("NEW", "New", 10, true)));

            assertThatThrownBy(() -> service.update(1, patch("RESOLVED", null, null, null)))
                    .isInstanceOf(StatusService.ImmutableStatusCodeException.class)
                    .hasMessageContaining("orphan");
        }

        @Test
        @DisplayName("resending the stored code is a no-op, because the form submits the whole row")
        void resendingTheSameCodeIsFine() {
            Status row = status("NEW", "New", 10, true);
            when(statuses.findById(1)).thenReturn(Optional.of(row));

            assertThatCode(() -> service.update(1, patch("new", "Raised", null, null)))
                    .doesNotThrowAnyException();
            assertThat(row.getName()).isEqualTo("Raised");
        }

        @Test
        @DisplayName("a name clashing with the row's own is not a clash")
        void ownNameIsNotAClash() {
            Status row = status("NEW", "New", 10, true);
            row.setId(1);
            when(statuses.findById(1)).thenReturn(Optional.of(row));
            when(statuses.findByNameIgnoreCase("New")).thenReturn(Optional.of(row));

            assertThatCode(() -> service.update(1, patch(null, "New", null, null)))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // the contradiction
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("terminal and open contradict; DONE and open do not")
    class Contradiction {

        @Test
        @DisplayName("creating a status both terminal and open is refused")
        void terminalAndOpenOnCreate() {
            assertThatThrownBy(() -> service.create(new StatusDtos.StatusWrite(
                    "CLOSED", "Closed", "DONE", "#10B981", 70, true, true, null)))
                    .isInstanceOf(StatusService.ContradictoryStatusException.class)
                    .hasMessageContaining("drive to zero");
        }

        /**
         * The counter-example that stops the obvious fourth rule being written.
         * `RESOLVED` is DONE work on a ticket that stays open until sign-off, and
         * it is in the seed — a "DONE implies not open" guard would refuse the row
         * the blueprint asks for.
         */
        @Test
        @DisplayName("DONE while still open is accepted — RESOLVED is exactly that row")
        void doneAndOpenIsFine() {
            assertThatCode(() -> service.create(new StatusDtos.StatusWrite(
                    "RESOLVED", "Resolved", "DONE", "#14B8A6", 60, true, false, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TODO while terminal and not open is accepted — the flags are independent")
        void categoryDoesNotConstrainTheFlags() {
            assertThatCode(() -> service.create(new StatusDtos.StatusWrite(
                    "REOPENED", "Reopened", "TODO", "#EF4444", 80, false, true, null)))
                    .doesNotThrowAnyException();
        }

        /**
         * The ordering bug, in this feature's form. Each flag read from the stored
         * entity would let the pair through: the row is currently open and not
         * terminal, so a patch setting only `isTerminal` looks fine against the
         * stored `isOpen`, and would be — except the end state is the forbidden
         * one.
         */
        @Test
        @DisplayName("a patch setting only isTerminal is checked against the end state")
        void patchIsCheckedAgainstTheEndState() {
            Status open = status("ON_HOLD", "On Hold", 30, true);
            open.setOpen(true);
            open.setTerminal(false);
            when(statuses.findById(1)).thenReturn(Optional.of(open));

            assertThatThrownBy(() -> service.update(1,
                    new StatusDtos.StatusPatch(null, null, null, null, null, null, true, null)))
                    .isInstanceOf(StatusService.ContradictoryStatusException.class);
        }

        @Test
        @DisplayName("clearing isOpen in the same patch makes it legal again")
        void bothFlagsInOnePatch() {
            Status open = status("ON_HOLD", "On Hold", 30, true);
            open.setOpen(true);
            open.setTerminal(false);
            when(statuses.findById(1)).thenReturn(Optional.of(open));

            assertThatCode(() -> service.update(1,
                    new StatusDtos.StatusPatch(null, null, null, null, null, false, true, null)))
                    .doesNotThrowAnyException();
            assertThat(open.isTerminal()).isTrue();
            assertThat(open.isOpen()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // retiring
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("retiring refuses while tickets are there, and cascades to the matrix when they are not")
    class Retiring {

        @Test
        @DisplayName("a status with live tickets cannot be retired — they would be stranded")
        void retireBlockedByTickets() {
            Status row = status("ON_HOLD", "On Hold", 30, true);
            when(statuses.findById(1)).thenReturn(Optional.of(row));
            when(usage.forCode("ON_HOLD"))
                    .thenReturn(new StatusUsageRepository.Counts.Row(3L, 12));

            assertThatThrownBy(() -> service.update(1, patch(null, null, null, false)))
                    .isInstanceOfSatisfying(StatusService.StatusInUseException.class, e -> {
                        assertThat(e.ticketCount()).isEqualTo(3L);
                        assertThat(e).hasMessageContaining("no move offered");
                    });
        }

        @Test
        @DisplayName("the refusal reads in the singular for one ticket")
        void refusalGrammar() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));
            when(usage.forCode("ON_HOLD"))
                    .thenReturn(new StatusUsageRepository.Counts.Row(1L, 0));

            assertThatThrownBy(() -> service.update(1, patch(null, null, null, false)))
                    .hasMessageContaining("1 ticket is")
                    .hasMessageContaining("that ticket");
        }

        @Test
        @DisplayName("nothing is written and no transition is touched when the retire is refused")
        void refusedRetireWritesNothing() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));
            when(usage.forCode("ON_HOLD"))
                    .thenReturn(new StatusUsageRepository.Counts.Row(2L, 4));

            assertThatThrownBy(() -> service.update(1, patch(null, null, null, false)))
                    .isInstanceOf(StatusService.StatusInUseException.class);
            verify(statuses, never()).save(any());
            verify(transitions, never()).saveAll(any());
        }

        /**
         * The cascade, and the reason it exists: Stream C's gate reads the
         * transition row's `isActive` and never looks at the status, so a retire
         * that left the matrix alone would let tickets keep moving into a status
         * the master says is gone.
         */
        @Test
        @DisplayName("retiring deactivates every transition naming the status, on either end")
        void retireCascadesBothEnds() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));
            WorkflowTransition into = transition(null, "IN_PROGRESS", "ON_HOLD", "PM", true);
            WorkflowTransition outOf = transition(null, "ON_HOLD", "IN_PROGRESS", "PM", true);
            WorkflowTransition unrelated = transition(null, "NEW", "IN_PROGRESS", "PM", true);
            when(transitions.findAllByOrderByIdAsc())
                    .thenReturn(List.of(into, outOf, unrelated));

            Optional<StatusDtos.StatusView> result = service.update(1, patch(null, null, null, false));

            assertThat(into.isActive()).isFalse();
            assertThat(outOf.isActive()).isFalse();
            assertThat(unrelated.isActive()).isTrue();
            assertThat(result).get().extracting(StatusDtos.StatusView::deactivatedTransitions)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the on-create row into a retired status is caught too — it has a null from")
        void onCreateRowIsCaught() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("NEW", "New", 10, true)));
            WorkflowTransition onCreate = transition(null, null, "NEW", "ADMIN", true);
            when(transitions.findAllByOrderByIdAsc()).thenReturn(List.of(onCreate));

            service.update(1, patch(null, null, null, false));

            assertThat(onCreate.isActive()).isFalse();
        }

        @Test
        @DisplayName("an already-inactive transition is not counted a second time")
        void alreadyInactiveNotRecounted() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));
            when(transitions.findAllByOrderByIdAsc()).thenReturn(List.of(
                    transition(null, "IN_PROGRESS", "ON_HOLD", "PM", true),
                    transition(null, "ON_HOLD", "IN_PROGRESS", "QA", false)));

            assertThat(service.update(1, patch(null, null, null, false)))
                    .get().extracting(StatusDtos.StatusView::deactivatedTransitions)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an edit that is not a retire touches no transition and reports null")
        void ordinaryEditDoesNotCascade() {
            when(statuses.findById(1))
                    .thenReturn(Optional.of(status("ON_HOLD", "On Hold", 30, true)));

            assertThat(service.update(1, patch(null, "Paused", null, null)))
                    .get().extracting(StatusDtos.StatusView::deactivatedTransitions)
                    .isNull();
            verify(transitions, never()).saveAll(any());
        }

        @Test
        @DisplayName("retiring an already-retired status is a no-op, not a second cascade")
        void retiringTwiceDoesNotCascadeTwice() {
            Status retired = status("ON_HOLD", "On Hold", 30, false);
            when(statuses.findById(1)).thenReturn(Optional.of(retired));

            assertThat(service.update(1, patch(null, null, null, false)))
                    .get().extracting(StatusDtos.StatusView::deactivatedTransitions)
                    .isNull();
            verify(transitions, never()).saveAll(any());
        }

        @Test
        @DisplayName("reactivating does not bring the transitions back")
        void reactivateDoesNotRestore() {
            Status retired = status("ON_HOLD", "On Hold", 30, false);
            when(statuses.findById(1)).thenReturn(Optional.of(retired));
            WorkflowTransition dormant = transition(null, "IN_PROGRESS", "ON_HOLD", "PM", false);
            when(transitions.findAllByOrderByIdAsc()).thenReturn(List.of(dormant));

            service.update(1, patch(null, null, null, true));

            assertThat(retired.isActive()).isTrue();
            assertThat(dormant.isActive()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // create defaults
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create defaults")
    class Defaults {

        @Test
        @DisplayName("isOpen defaults to true and isTerminal to false")
        void flagDefaults() {
            StatusDtos.StatusView created =
                    service.create(write("REWORK", "Rework", "IN_PROGRESS", "#8B5CF6"));

            assertThat(created.isOpen()).isTrue();
            assertThat(created.isTerminal()).isFalse();
            assertThat(created.isActive()).isTrue();
        }

        @Test
        @DisplayName("an omitted seq sorts the new status to the end, ten past the highest")
        void seqDefaultsToEnd() {
            when(statuses.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                    status("NEW", "New", 10, true),
                    status("CLOSED", "Closed", 70, true)));

            assertThat(service.create(write("REWORK", "Rework", "IN_PROGRESS", "#8B5CF6")).seq())
                    .isEqualTo((short) 80);
        }

        /**
         * Zero is a legitimate seq — first in the lifecycle — so it must not be
         * read as "not stated". This is why the DTO field is a boxed Integer.
         */
        @Test
        @DisplayName("an explicit seq of zero is honoured, not treated as absent")
        void zeroSeqIsHonoured() {
            when(statuses.findAllByOrderBySeqAscIdAsc())
                    .thenReturn(List.of(status("CLOSED", "Closed", 70, true)));

            assertThat(service.create(new StatusDtos.StatusWrite(
                    "REWORK", "Rework", "IN_PROGRESS", "#8B5CF6", 0, null, null, null)).seq())
                    .isEqualTo((short) 0);
        }

        @Test
        @DisplayName("category is upper-cased and whitespace is trimmed off every string")
        void valuesAreNormalised() {
            StatusDtos.StatusView created = service.create(new StatusDtos.StatusWrite(
                    "  rework ", " Rework ", "in_progress", " #8B5CF6 ", null, null, null, null));

            assertThat(created.code()).isEqualTo("REWORK");
            assertThat(created.name()).isEqualTo("Rework");
            assertThat(created.category()).isEqualTo("IN_PROGRESS");
            assertThat(created.colour()).isEqualTo("#8B5CF6");
        }

        @Test
        @DisplayName("a new status has both counts at zero without asking the database")
        void countsAreKnownWithoutAsking() {
            StatusDtos.StatusView created =
                    service.create(write("REWORK", "Rework", "IN_PROGRESS", "#8B5CF6"));

            assertThat(created.ticketCount()).isZero();
            assertThat(created.transitionCount()).isZero();
            verify(usage, never()).forCode("REWORK");
        }
    }

    @Test
    @DisplayName("an unknown id is empty, not an exception — the controller turns it into 404")
    void unknownIdIsEmpty() {
        when(statuses.findById(404)).thenReturn(Optional.empty());

        assertThat(service.find(404)).isEmpty();
        assertThat(service.update(404, patch(null, "x", null, null))).isEmpty();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * {@code id} is set because {@code StatusView.id} is a primitive {@code int}
     * and the mapper unboxes it. A fixture without one throws an NPE inside the
     * mapper rather than failing the assertion, which reads as a production bug
     * and is not one — the identity column is never null on a row the repository
     * returned. Derived from the code so two fixtures in one test differ.
     */
    private static Status status(String code, String name, int seq, boolean active) {
        Status s = new Status();
        s.setId(Math.abs(code.hashCode() % 1000) + 1);
        s.setCode(code);
        s.setName(name);
        s.setCategory("IN_PROGRESS");
        s.setColour("#4F46E5");
        s.setSeq((short) seq);
        s.setOpen(true);
        s.setTerminal(false);
        s.setActive(active);
        return s;
    }

    private static WorkflowTransition transition(Integer id, String from, String to,
                                                 String role, boolean active) {
        WorkflowTransition t = new WorkflowTransition();
        t.setId(id);
        t.setFromStatus(from);
        t.setToStatus(to);
        t.setRoleCode(role);
        t.setActive(active);
        return t;
    }

    private static StatusDtos.StatusWrite write(String code, String name,
                                                String category, String colour) {
        return new StatusDtos.StatusWrite(code, name, category, colour, null, null, null, null);
    }

    private static StatusDtos.StatusPatch patch(String code, String name,
                                                String category, Boolean isActive) {
        return new StatusDtos.StatusPatch(code, name, category, null, null, null, null, isActive);
    }
}
