package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.domain.clients.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-027 · what {@link ClientContactService} decides, with the SQL mocked out.
 *
 * <p>The claims about the <em>statements</em> — that an edit cannot resurrect a
 * removed contact, that the duplicate-email check agrees with
 * {@code utf8mb4_0900_ai_ci}, that a removal clears the primary flag — are about
 * SQL, so they are in {@code ClientMasterIT} against a real container. A mocked
 * repository answers whatever it was told, and B-013 already documented what
 * that costs on the resource form's collation check.
 */
class ClientContactServiceTest {

    private ClientRepository clients;
    private ClientQueryRepository query;
    private ClientContactWriteRepository write;
    private ClientContactService service;

    @BeforeEach
    void setUp() {
        clients = mock(ClientRepository.class);
        query = mock(ClientQueryRepository.class);
        write = mock(ClientContactWriteRepository.class);
        service = new ClientContactService(clients, query, write);

        when(clients.existsById(1L)).thenReturn(true);
        when(write.findConflictingEmail(anyLong(), any(), any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the list")
    class Listing {

        /**
         * An empty grid and a mistyped id look identical to a user, and only one
         * of them is worth reporting. B-025's call, unchanged.
         */
        @Test
        @DisplayName("an unknown client is 404, not an empty list")
        void unknownClientIsEmptyOptional() {
            when(clients.existsById(9L)).thenReturn(false);

            assertThat(service.list(9, false)).isEmpty();
        }

        @Test
        @DisplayName("a known client with no contacts is an empty list")
        void knownClientWithNoContactsIsAnEmptyList() {
            when(query.contactsOf(1L, false)).thenReturn(List.of());

            assertThat(service.list(1, false)).contains(List.of());
        }

        /**
         * The flag is passed through rather than being decided here. The whole
         * point of it is that the grid and the picker read the same route with
         * different answers.
         */
        @Test
        @DisplayName("includeInactive reaches the query untouched")
        void includeInactiveIsPassedThrough() {
            when(query.contactsOf(eq(1L), anyBoolean())).thenReturn(List.of());

            service.list(1, true);

            verify(query).contactsOf(1L, true);
        }
    }

    // ------------------------------------------------------------------
    // Adding
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("adding")
    class Adding {

        @Test
        @DisplayName("an unknown client is 404 and nothing is written")
        void unknownClientWritesNothing() {
            when(clients.existsById(9L)).thenReturn(false);

            assertThat(service.add(9, request("Sara", "sara@acme.example", false))).isEmpty();
            verify(write, never()).insert(anyLong(), any());
        }

        /**
         * <b>Insert first, demote second.</b> Demoting before the insert leaves a
         * window in which the client has no primary at all, and makes
         * {@code exceptId} a sentinel that has to mean "nothing" rather than a
         * real id.
         */
        @Test
        @DisplayName("a new primary is inserted before the others are demoted")
        void insertsBeforeDemoting() {
            when(write.insert(eq(1L), any())).thenReturn(7L);
            when(query.contactOf(1L, 7L)).thenReturn(Optional.of(contact(7, true, true)));

            service.add(1, request("Sara", "sara@acme.example", true));

            var order = org.mockito.Mockito.inOrder(write);
            order.verify(write).insert(eq(1L), any());
            order.verify(write).demoteOtherPrimaries(1L, 7L);
        }

        /**
         * The demotion is the expensive half and it is skipped when nothing is
         * being promoted — a client with nine contacts should not have all nine
         * rewritten because somebody added a tenth.
         */
        @Test
        @DisplayName("a non-primary add demotes nobody")
        void aNonPrimaryAddDemotesNobody() {
            when(write.insert(eq(1L), any())).thenReturn(7L);
            when(query.contactOf(1L, 7L)).thenReturn(Optional.of(contact(7, false, true)));

            service.add(1, request("Dev", "dev@acme.example", false));

            verify(write, never()).demoteOtherPrimaries(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a duplicate email at the same client is refused, and named")
        void duplicateEmailIsRefused() {
            when(write.findConflictingEmail(1L, "sara@acme.example", null))
                    .thenReturn(Optional.of("Sara Kapoor"));

            assertThatThrownBy(() -> service.add(1, request("Sara K", "sara@acme.example", false)))
                    .isInstanceOf(ClientContactService.ContactValidationException.class)
                    .extracting(e ->
                            ((ClientContactService.ContactValidationException) e).errors())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    // Naming the holder is the difference between a message and a
                    // search: "already in use" on a client with nine contacts
                    // tells an admin nothing about which row to look at.
                    .hasEntrySatisfying("email", message ->
                            assertThat(String.valueOf(message)).contains("Sara Kapoor"));

            verify(write, never()).insert(anyLong(), any());
        }
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("editing")
    class Editing {

        /**
         * The scoping is the {@code client_id} predicate on the read, and the
         * service must not be able to tell the two 404s apart either — an unknown
         * client and a contact belonging to somebody else are one answer.
         */
        @Test
        @DisplayName("a contact belonging to another client is 404 and nothing is written")
        void anotherClientsContactIsNotFound() {
            when(query.contactOf(1L, 999L)).thenReturn(Optional.empty());

            assertThat(service.edit(1, 999, request("Dev", "dev@acme.example", false))).isEmpty();
            verify(write, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("promoting through an edit demotes the others")
        void promotingThroughAnEditDemotes() {
            when(query.contactOf(1L, 2L)).thenReturn(Optional.of(contact(2, false, true)));

            service.edit(1, 2, request("Dev", "dev@acme.example", true));

            verify(write).demoteOtherPrimaries(1L, 2L);
        }

        /**
         * <b>Demoting the last primary is allowed</b>, and this is the assertion
         * that says so deliberately rather than by omission.
         *
         * <p>B-021 refused the mirror case — clearing the last escalation trigger
         * — and the two differ in kind. A level with no escalation target
         * silently switches off one of §1's headline behaviours with nothing on
         * screen wrong. A client with no primary contact is the state *every*
         * client is created in, B-028 reports it where a caller can act on it,
         * and the person may simply have left. Refusing it here while the
         * {@code DELETE} produces the same state anyway would be one rule with
         * two answers.
         */
        @Test
        @DisplayName("clearing the flag on the only primary is allowed")
        void demotingTheLastPrimaryIsAllowed() {
            when(query.contactOf(1L, 2L)).thenReturn(Optional.of(contact(2, true, true)));

            assertThat(service.edit(1, 2, request("Sara", "sara@acme.example", false)))
                    .isPresent();

            verify(write).update(eq(1L), eq(2L), any());
            verify(write, never()).demoteOtherPrimaries(anyLong(), anyLong());
        }

        /**
         * Correcting the spelling of a departed contact's name, so a historical
         * ticket reads properly, is a real thing to want — and the edit cannot
         * bring them back, because {@code is_active} is not in the statement.
         * That half is asserted against real MySQL in {@code ClientMasterIT}.
         */
        @Test
        @DisplayName("a removed contact is still editable")
        void aRemovedContactIsEditable() {
            when(query.contactOf(1L, 5L)).thenReturn(Optional.of(contact(5, false, false)));

            assertThat(service.edit(1, 5, request("Ravi Menon", "ravi@acme.example", false)))
                    .isPresent();
        }

        /**
         * The whole reason {@code exceptId} exists: this body is the whole
         * representation and carries the email on every save, so without it every
         * ordinary edit would report the contact's own address as taken — the
         * {@code u.id <> ?} B-013 had to document on the resource form.
         */
        @Test
        @DisplayName("the edited contact is excluded from its own uniqueness check")
        void anEditExcludesItself() {
            when(query.contactOf(1L, 2L)).thenReturn(Optional.of(contact(2, false, true)));

            service.edit(1, 2, request("Dev", "dev@acme.example", false));

            verify(write).findConflictingEmail(1L, "dev@acme.example", 2L);
        }
    }

    // ------------------------------------------------------------------
    // Removing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("removing")
    class Removing {

        @Test
        @DisplayName("a contact that is not this client's is 404 and nothing is written")
        void anotherClientsContactIsNotFound() {
            when(query.contactOf(1L, 999L)).thenReturn(Optional.empty());

            assertThat(service.remove(1, 999)).isFalse();
            verify(write, never()).deactivate(anyLong(), anyLong());
        }

        /**
         * A setter, so the second half of a double-click must not be an error
         * about something that did happen — B-014's {@code UNCHANGED} argument
         * and B-017's on removing somebody who is not on the team.
         */
        @Test
        @DisplayName("removing one that is already removed still succeeds")
        void removingTwiceSucceeds() {
            when(query.contactOf(1L, 5L)).thenReturn(Optional.of(contact(5, false, false)));

            assertThat(service.remove(1, 5)).isTrue();
        }

        @Test
        @DisplayName("removing the primary is allowed")
        void removingThePrimaryIsAllowed() {
            when(query.contactOf(1L, 2L)).thenReturn(Optional.of(contact(2, true, true)));

            assertThat(service.remove(1, 2)).isTrue();
            verify(write).deactivate(1L, 2L);
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ClientDtos.ContactWriteRequest request(String name,
                                                          String email,
                                                          boolean isPrimary) {
        return new ClientDtos.ContactWriteRequest(
                name, null, email, null, isPrimary, null, null);
    }

    private static ClientDtos.Contact contact(long id, boolean isPrimary, boolean isActive) {
        return new ClientDtos.Contact(
                id, "Contact " + id, null, "c" + id + "@acme.example", null,
                isPrimary, true, false, isActive);
    }
}
