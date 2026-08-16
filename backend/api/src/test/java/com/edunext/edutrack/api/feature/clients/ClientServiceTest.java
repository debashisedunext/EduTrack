package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-025 · the decisions S-32 makes, without Docker.
 *
 * <p>{@code ClientMasterIT} proves the same behaviour against real MySQL, where
 * the keyset ordering and the {@code statuses.is_terminal} join have opinions of
 * their own. This proves the decisions themselves.
 */
class ClientServiceTest {

    private ClientRepository clients;
    private ClientQueryRepository query;
    private ClientService service;

    @BeforeEach
    void setUp() {
        clients = mock(ClientRepository.class);
        query = mock(ClientQueryRepository.class);
        service = new ClientService(clients, query);

        when(query.openTicketCounts(anyCollection())).thenReturn(Map.of());
        when(query.lastTicketDates(anyCollection())).thenReturn(Map.of());
        when(query.projectsByClient(anyCollection())).thenReturn(Map.of());
        when(query.primaryContacts(anyCollection())).thenReturn(Map.of());
    }

    // ------------------------------------------------------------------
    // Paging
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the page boundary")
    class Paging {

        @Test
        @DisplayName("the extra row is evidence of a next page, never returned")
        void extraRowIsDroppedAndReportedAsHasMore() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme", "Bluewave", "Cormorant"));

            CursorPage<ClientDtos.Client> page = service.list(noFilter(), null, 2);

            assertThat(page.data()).extracting(ClientDtos.Client::name)
                    .containsExactly("Acme", "Bluewave");
            assertThat(page.meta().hasMore()).isTrue();
        }

        /**
         * The cursor names the last <em>returned</em> row, not the extra one.
         *
         * <p>Naming the extra row is the mistake A-053's javadoc lists second:
         * the next page then starts after a row nobody has seen, and it is
         * skipped silently.
         */
        @Test
        @DisplayName("nextCursor points at the last returned row")
        void cursorNamesTheLastReturnedRow() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme", "Bluewave", "Cormorant"));

            CursorPage<ClientDtos.Client> page = service.list(noFilter(), null, 2);
            Cursor resumeFrom = Cursor.decode(page.meta().nextCursor());

            assertThat(resumeFrom).isNotNull();
            assertThat(resumeFrom.sortKey()).isEqualTo("Bluewave");
        }

        /**
         * A full final page is not "probably more".
         *
         * <p>Deriving {@code hasMore} from {@code rows.size() == limit} is true
         * on the last page whenever the total divides evenly, and the client then
         * makes one more request for nothing.
         */
        @Test
        @DisplayName("a page that exactly fills the limit is still the last one")
        void exactlyFullPageIsNotMore() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme", "Bluewave"));

            CursorPage<ClientDtos.Client> page = service.list(noFilter(), null, 2);

            assertThat(page.data()).hasSize(2);
            assertThat(page.meta().hasMore()).isFalse();
            assertThat(page.meta().nextCursor()).isNull();
        }

        /** One more than the page, so the boundary has something to read. */
        @Test
        @DisplayName("the query is asked for limit + 1 rows")
        void fetchesOneMoreThanTheLimit() {
            when(query.page(any(), any(), anyInt())).thenReturn(List.of());

            service.list(noFilter(), null, 25);

            ArgumentCaptor<Integer> fetchSize = ArgumentCaptor.forClass(Integer.class);
            verify(query).page(any(), any(), fetchSize.capture());
            assertThat(fetchSize.getValue()).isEqualTo(26);
        }

        /** Out of range clamps rather than rejects — PageLimit's rule. */
        @Test
        @DisplayName("limit=1000 is clamped to 200, not refused")
        void limitIsClamped() {
            when(query.page(any(), any(), anyInt())).thenReturn(List.of());

            service.list(noFilter(), null, 1000);

            ArgumentCaptor<Integer> fetchSize = ArgumentCaptor.forClass(Integer.class);
            verify(query).page(any(), any(), fetchSize.capture());
            assertThat(fetchSize.getValue()).isEqualTo(201);
        }

        /** A bookmarked or forged cursor is the first page, not a 400. */
        @Test
        @DisplayName("an unreadable cursor starts at the beginning")
        void malformedCursorIsTheFirstPage() {
            when(query.page(any(), any(), anyInt())).thenReturn(List.of());

            service.list(noFilter(), "not-a-cursor", 50);

            ArgumentCaptor<Cursor> cursor = ArgumentCaptor.forClass(Cursor.class);
            verify(query).page(any(), cursor.capture(), anyInt());
            assertThat(cursor.getValue()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // The grid's derived columns
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the S-32 columns that are not on the client row")
    class Aggregates {

        /**
         * Four queries for the page whatever its size — never four per row.
         *
         * <p>An N+1 here is invisible at the four seeded clients and is not
         * invisible at the five thousand B-032's import allows.
         */
        @Test
        @DisplayName("aggregates are read once for the whole page")
        void aggregatesAreReadPerPageNotPerRow() {
            when(query.page(any(), any(), anyInt()))
                    .thenReturn(rows("Acme", "Bluewave", "Cormorant"));

            service.list(noFilter(), null, 50);

            verify(query).openTicketCounts(anyCollection());
            verify(query).lastTicketDates(anyCollection());
            verify(query).projectsByClient(anyCollection());
            verify(query).primaryContacts(anyCollection());
        }

        /** A client nothing has been raised against is zero, not absent. */
        @Test
        @DisplayName("a client with no tickets reports zero open and a null date")
        void clientWithNoTicketsIsZeroNotNull() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme"));

            ClientDtos.Client acme = service.list(noFilter(), null, 50).data().getFirst();

            assertThat(acme.openTicketCount()).isZero();
            assertThat(acme.lastTicketDate()).isNull();
            assertThat(acme.projects()).isEmpty();
        }

        @Test
        @DisplayName("counts and dates land on the client they belong to")
        void aggregatesAreKeyedByClient() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme", "Bluewave"));
            Instant reported = Instant.parse("2026-08-01T09:15:00Z");
            when(query.openTicketCounts(anyCollection())).thenReturn(Map.of(2L, 7L));
            when(query.lastTicketDates(anyCollection())).thenReturn(Map.of(2L, reported));

            List<ClientDtos.Client> page = service.list(noFilter(), null, 50).data();

            assertThat(page.getFirst().openTicketCount()).isZero();
            assertThat(page.get(1).openTicketCount()).isEqualTo(7L);
            assertThat(page.get(1).lastTicketDate()).isEqualTo(reported);
        }

        /**
         * <b>B-026 inverted this assertion, deliberately.</b>
         *
         * <p>B-025 wrote it as "{@code isActive} is {@code status = 'ACTIVE'}
         * rather than {@code status <> 'INACTIVE'}, so the third state blueprint
         * §4B.2 names does not silently read as live" — exact while the column
         * carried two values, and the wrong reading once the third arrived.
         *
         * <p>§4B.2 puts a client dropdown on the ticket create form
         * <b>filtered on this boolean</b>. Under the narrow reading every
         * Prospect would have vanished from that form the moment B-026 shipped
         * the field — silently, with nothing on screen saying why, and looking
         * exactly like a dropdown that had lost its data. A prospect is somebody
         * you are talking to; raising a pre-sales ticket against them is a normal
         * thing to want, and the blueprint reserves "blocks new tickets" for
         * deactivation alone.
         *
         * <p>Same call B-016 made on {@code Project.isActive}, which derives as
         * {@code status <> 'CLOSED'} so that putting a project on hold does not
         * remove it from five pickers. {@link ClientStatus} carries the full
         * argument, and {@code ClientStatusTest} pins the consequence for the
         * status setter.
         */
        @Test
        @DisplayName("isActive is not-INACTIVE, so a prospect stays selectable on a ticket")
        void isActiveIsDerivedFromStatus() {
            when(query.page(any(), any(), anyInt())).thenReturn(List.of(
                    row(1, "Acme", "ACTIVE"),
                    row(2, "Bluewave", "INACTIVE"),
                    row(3, "Cormorant", "PROSPECT")));

            List<ClientDtos.Client> page = service.list(noFilter(), null, 50).data();

            assertThat(page).extracting(ClientDtos.Client::isActive)
                    .containsExactly(true, false, true);
            // The status itself is on the wire beside it — B-026 — because a
            // boolean cannot render S-32's status chip once a prospect and a
            // contracted client are both active by that projection.
            assertThat(page).extracting(ClientDtos.Client::status)
                    .containsExactly("ACTIVE", "INACTIVE", "PROSPECT");
        }

        /**
         * The safe direction for a value nobody can interpret is the one that
         * does not put a client in front of a ticket form. A row written before
         * {@code ck_clients_status} existed, or by a hand-run {@code UPDATE}, can
         * carry anything.
         */
        @Test
        @DisplayName("a status nothing recognises reads as inactive, not as active")
        void unrecognisedStatusIsNotActive() {
            when(query.page(any(), any(), anyInt()))
                    .thenReturn(List.of(row(1, "Archived", "ARCHIVED")));

            assertThat(service.list(noFilter(), null, 50).data().getFirst().isActive())
                    .isFalse();
        }

        // ── B-028 · the gate §4B.2's ticket-form dropdown reads ───────────

        /**
         * <b>The rule as a boolean, on the row rather than derived at the call
         * site.</b>
         *
         * <p>{@code primaryContact} is {@code @JsonInclude(NON_NULL)}, so a
         * client without one omits the key entirely — a picker deriving the gate
         * from the object is reading {@code undefined}, which is one {@code ??}
         * away from offering every client on the form. The two are asserted
         * together here because they come off the same lookup and must never
         * disagree.
         */
        @Test
        @DisplayName("hasPrimaryContact is true exactly when a primary contact is present")
        void hasPrimaryContactTracksTheContact() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Acme", "Bluewave"));
            when(query.primaryContacts(anyCollection())).thenReturn(Map.of(
                    1L, new ClientDtos.Contact(9, "Sara Kapoor", "IT Director",
                            "sara@acme.example", null, true, true, false, true)));

            List<ClientDtos.Client> page = service.list(noFilter(), null, 50).data();

            assertThat(page.getFirst().hasPrimaryContact()).isTrue();
            assertThat(page.getFirst().primaryContact()).isNotNull();
            assertThat(page.get(1).hasPrimaryContact()).isFalse();
            assertThat(page.get(1).primaryContact()).isNull();
        }

        /**
         * The state every client is created in — B-026 cannot supply a contact
         * on a create, because there is no client id to hang one off until the
         * create returns. So "no primary contact" is ordinary rather than
         * exceptional, and the gate has to report it rather than treat it as a
         * data error.
         */
        @Test
        @DisplayName("a client with no contacts at all reports the gate as unmet")
        void aClientWithNoContactsFailsTheGate() {
            when(query.page(any(), any(), anyInt())).thenReturn(rows("Newco"));

            assertThat(service.list(noFilter(), null, 50).data().getFirst().hasPrimaryContact())
                    .isFalse();
        }

        /** The column is nullable, and an absent manager is a real state. */
        @Test
        @DisplayName("a client with no account manager serialises without one")
        void missingAccountManagerIsNull() {
            when(query.page(any(), any(), anyInt())).thenReturn(List.of(
                    new ClientQueryRepository.Row(1, "ACME", "Acme", "acme.example",
                            null, null, null, "Premium", null, "Asia/Kolkata", "ACTIVE")));

            assertThat(service.list(noFilter(), null, 50).data().getFirst().accountManager())
                    .isNull();
        }
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the status setters")
    class Status {

        @Test
        @DisplayName("deactivating writes INACTIVE, and never deletes")
        void deactivateFlipsStatus() {
            Client acme = entity(1, "ACTIVE");
            when(clients.findById(1L)).thenReturn(Optional.of(acme));
            when(query.byIds(anyCollection())).thenReturn(rows("Acme"));

            service.setStatus(1, false);

            assertThat(acme.getStatus()).isEqualTo("INACTIVE");
            // Flushed, not merely saved — the response is read back through
            // JdbcClient, which Hibernate's auto-flush does not see coming.
            verify(clients).saveAndFlush(acme);
            verify(clients, never()).delete(any());
            verify(clients, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("a client that is not there is empty, not an exception")
        void unknownSingleClientIsEmpty() {
            when(clients.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.setStatus(9, false)).isEmpty();
        }

        /**
         * The whole request fails, and nothing is written.
         *
         * <p>Skipping the unknown id would report success on a bulk action that
         * changed forty-nine of fifty rows, which is the partial-success story
         * this endpoint exists to replace.
         */
        @Test
        @DisplayName("one unknown id fails the batch before anything is saved")
        void unknownIdFailsTheWholeBatch() {
            when(clients.findAllById(anyCollection()))
                    .thenReturn(List.of(entity(1, "ACTIVE"), entity(2, "ACTIVE")));

            assertThatThrownBy(() -> service.setStatusBulk(List.of(1L, 2L, 99L), false))
                    .isInstanceOf(ClientService.UnknownClientException.class)
                    .hasMessageContaining("99");

            verify(clients, never()).saveAllAndFlush(any());
        }

        /** Every missing id, so the caller fixes them in one round. */
        @Test
        @DisplayName("the failure names every missing id, not the first")
        void unknownIdFailureNamesAllOfThem() {
            when(clients.findAllById(anyCollection())).thenReturn(List.of(entity(1, "ACTIVE")));

            assertThatThrownBy(() -> service.setStatusBulk(List.of(1L, 98L, 99L), false))
                    .isInstanceOf(ClientService.UnknownClientException.class)
                    .satisfies(e -> assertThat(
                            ((ClientService.UnknownClientException) e).clientIds())
                            .containsExactly(98L, 99L));
        }

        /**
         * A selection assembled across two pages should not have to be
         * de-duplicated before it is allowed to act.
         */
        @Test
        @DisplayName("the same id twice is one client, not a 404")
        void duplicateIdsAreCollapsed() {
            Client acme = entity(1, "ACTIVE");
            when(clients.findAllById(anyCollection())).thenReturn(List.of(acme));
            when(query.byIds(anyCollection())).thenReturn(rows("Acme"));

            service.setStatusBulk(List.of(1L, 1L, 1L), false);

            assertThat(acme.getStatus()).isEqualTo("INACTIVE");
            verify(clients).saveAllAndFlush(any());
        }

        @Test
        @DisplayName("a bulk activate writes ACTIVE to every named client")
        void bulkActivateWritesAllOfThem() {
            Client acme = entity(1, "INACTIVE");
            Client blue = entity(2, "INACTIVE");
            when(clients.findAllById(anyCollection())).thenReturn(List.of(acme, blue));
            when(query.byIds(anyCollection())).thenReturn(rows("Acme", "Bluewave"));

            service.setStatusBulk(List.of(1L, 2L), true);

            assertThat(acme.getStatus()).isEqualTo("ACTIVE");
            assertThat(blue.getStatus()).isEqualTo("ACTIVE");
        }
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    /*
     * B-027 · the contacts read moved to ClientContactService, and its tests
     * moved with it to ClientContactServiceTest.
     *
     * B-025 put the read on ClientService because there was nothing else for it
     * to live on. Keeping a second reader beside the writes would mean two
     * classes deciding what a client's contacts are, and they would disagree the
     * first time one honoured `includeInactive` and the other did not.
     */

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static ClientQueryRepository.Filter noFilter() {
        return new ClientQueryRepository.Filter(null, null, null, null, null);
    }

    private static List<ClientQueryRepository.Row> rows(String... names) {
        List<ClientQueryRepository.Row> rows = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            rows.add(row(i + 1, names[i], "ACTIVE"));
        }
        return rows;
    }

    private static ClientQueryRepository.Row row(long id, String name, String status) {
        return new ClientQueryRepository.Row(
                id, name.toUpperCase(java.util.Locale.ROOT), name,
                name.toLowerCase(java.util.Locale.ROOT) + ".example",
                2L, "Priya Menon", "PM", "Premium", null, "Asia/Kolkata", status);
    }

    private static Client entity(long id, String status) {
        Client client = new Client();
        client.setId(id);
        client.setClientCode("C" + id);
        client.setName("Client " + id);
        client.setStatus(status);
        return client;
    }
}
