package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.portal.ClientPrincipal;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalAttachment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalComment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicket;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketReadRepository.AttachmentRow;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.api.feature.tickets.attachments.StorageKey;
import com.edunext.edutrack.api.security.scope.ClientScopeResolver;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-127 · the two gates, and what happens when either is shut.
 *
 * <p>Every assertion here is about a row <em>not</em> being reached. The
 * repository's own filters are SQL and belong to an integration test; what this
 * class pins is the layer above — that a caller with no ticketing client, or a
 * client whose {@code portal_access} has been withdrawn, is answered with
 * nothing rather than with a refusal, and that no read reaches the database
 * without both gates having been passed.
 */
class PortalTicketServiceTest {

    private static final long CLIENT_ID = 7;
    private static final String TICKET = "ACME-26-00042";
    private static final long TICKET_ROW = 4242;

    private final PortalTicketReadRepository repository = mock(PortalTicketReadRepository.class);
    private final ClientScopeResolver scope = mock(ClientScopeResolver.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final AttachmentProperties properties = new AttachmentProperties(
            Duration.ofMinutes(5), 10_485_760, 52_428_800, 20, Duration.ofMinutes(15),
            new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false),
            new AttachmentProperties.Thumbnail(true, 320, 50_000_000));

    private final Authentication caller = mock(Authentication.class);

    private PortalTicketService service;

    @BeforeEach
    void setUp() {
        service = new PortalTicketService(repository, scope, storage, properties);
    }

    /** The happy path: a linked client whose portal is open. */
    private void portalIsOpen() {
        when(scope.ticketingClientId(caller)).thenReturn(Optional.of(CLIENT_ID));
        when(repository.portalIsOpenFor(CLIENT_ID)).thenReturn(true);
    }

    private static PortalTicket ticket() {
        return new PortalTicket(1, TICKET, "Title", "Body", "IN_PROGRESS", "Acme ERP",
                "Bug", Instant.parse("2026-09-01T10:00:00Z"), null, null,
                Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Nested
    @DisplayName("a caller with no ticketing client")
    class Unlinked {

        @BeforeEach
        void noClient() {
            when(scope.ticketingClientId(caller)).thenReturn(Optional.empty());
        }

        /**
         * An onboarding client with no ticketing counterpart is the ordinary
         * case, not an error — plan §2.3 keeps the two masters disjoint.
         */
        @Test
        void listsNothingAndAsksTheDatabaseNothing() {
            CursorPage<PortalTicket> page = service.list(caller, null, false, null, null, 50);

            assertThat(page.data()).isEmpty();
            assertThat(page.meta().hasMore()).isFalse();
            verify(repository, never()).tickets(anyLong(), any(), anyBoolean(), anyBoolean(), any(), anyInt());
        }

        @Test
        void answersEmptyForADetailRead() {
            assertThat(service.get(caller, TICKET)).isEmpty();
            verify(repository, never()).ticket(anyLong(), anyString());
        }

        @Test
        void answersEmptyForBothChildListings() {
            assertThat(service.comments(caller, TICKET, null, 50)).isEmpty();
            assertThat(service.attachments(caller, TICKET, null, 50)).isEmpty();
            verify(repository, never()).ticketRowId(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("portal_access withdrawn")
    class AccessWithdrawn {

        @BeforeEach
        void closed() {
            when(scope.ticketingClientId(caller)).thenReturn(Optional.of(CLIENT_ID));
            when(repository.portalIsOpenFor(CLIENT_ID)).thenReturn(false);
        }

        /**
         * The flag is load-bearing. Without this the predicate could be deleted
         * from the service and every other test in the file would still pass.
         */
        @Test
        void closesEveryRead() {
            assertThat(service.list(caller, null, false, null, null, 50).data()).isEmpty();
            assertThat(service.get(caller, TICKET)).isEmpty();
            assertThat(service.comments(caller, TICKET, null, 50)).isEmpty();
            assertThat(service.attachments(caller, TICKET, null, 50)).isEmpty();

            verify(repository, never()).tickets(anyLong(), any(), anyBoolean(), anyBoolean(), any(), anyInt());
            verify(repository, never()).ticket(anyLong(), anyString());
            verify(repository, never()).ticketRowId(anyLong(), anyString());
        }

        /**
         * Empty, never 403.
         *
         * <p>A client outside the organisation must not be able to tell "your
         * access was withdrawn" from "there is nothing here". The service has no
         * way to signal the first, which is the property being asserted: the
         * return type carries no third state.
         */
        @Test
        void withoutDistinguishingItselfFromAnEmptyAccount() {
            when(scope.ticketingClientId(caller)).thenReturn(Optional.empty());
            CursorPage<PortalTicket> unlinked = service.list(caller, null, false, null, null, 50);

            when(scope.ticketingClientId(caller)).thenReturn(Optional.of(CLIENT_ID));
            CursorPage<PortalTicket> withdrawn = service.list(caller, null, false, null, null, 50);

            assertThat(withdrawn).isEqualTo(unlinked);
        }
    }

    @Nested
    @DisplayName("an open portal")
    class Open {

        @BeforeEach
        void open() {
            portalIsOpen();
        }

        @Test
        void scopesTheListToTheTokensClientAndNotToAnythingTheCallerSent() {
            when(repository.tickets(eq(CLIENT_ID), any(), anyBoolean(), anyBoolean(), any(), anyInt()))
                    .thenReturn(List.of(ticket()));

            assertThat(service.list(caller, null, false, null, null, 50).data()).hasSize(1);

            // The id comes from the resolver. There is no overload that takes one
            // from the caller, so this verify is the whole surface.
            verify(repository).tickets(eq(CLIENT_ID), any(), anyBoolean(), anyBoolean(), any(), anyInt());
        }

        @Test
        void aTicketOutsideTheScopeIsEmpty() {
            when(repository.ticket(CLIENT_ID, TICKET)).thenReturn(Optional.empty());

            assertThat(service.get(caller, TICKET)).isEmpty();
        }

        @Test
        void theChildListingsResolveTheTicketWithinTheScopeFirst() {
            when(repository.ticketRowId(CLIENT_ID, TICKET)).thenReturn(Optional.of(TICKET_ROW));
            when(repository.comments(eq(TICKET_ROW), any(), anyInt())).thenReturn(List.of(
                    new PortalComment(1, "Fixed in the 4.2 release.", "STAFF", "Priya N",
                            Instant.parse("2026-09-02T09:00:00Z"))));

            Optional<CursorPage<PortalComment>> page = service.comments(caller, TICKET, null, 50);

            assertThat(page).isPresent();
            assertThat(page.get().data()).hasSize(1);
            verify(repository).ticketRowId(CLIENT_ID, TICKET);
        }

        @Test
        void anUnreadableCursorIsTheFirstPageRatherThanAnError() {
            when(repository.tickets(eq(CLIENT_ID), any(), anyBoolean(), anyBoolean(), eq(null), anyInt()))
                    .thenReturn(List.of(ticket()));

            assertThat(service.list(caller, null, false, null, "not-a-cursor", 50).data()).hasSize(1);
        }

        /**
         * The contract's default, {@code -dateReported}, and the one other
         * direction it exposes — {@code TicketListSpecs.sortKey}'s own rule that
         * a leading {@code -} is descending and anything else, including a
         * column this endpoint does not serve, falls back rather than 400s.
         */
        @Test
        void aLeadingMinusIsDescendingAndAnythingElseFallsBackToIt() {
            when(repository.tickets(eq(CLIENT_ID), any(), anyBoolean(), anyBoolean(), any(), anyInt()))
                    .thenReturn(List.of(ticket()));

            service.list(caller, null, false, "-dateReported", null, 50);
            verify(repository).tickets(eq(CLIENT_ID), any(), anyBoolean(), eq(true), any(), anyInt());

            service.list(caller, null, false, "+dateReported", null, 50);
            verify(repository).tickets(eq(CLIENT_ID), any(), anyBoolean(), eq(false), any(), anyInt());

            service.list(caller, null, false, "level", null, 50);
            verify(repository, org.mockito.Mockito.times(2))
                    .tickets(eq(CLIENT_ID), any(), anyBoolean(), eq(true), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("signing a download URL")
    class Signing {

        private static final String KEY = "tickets/4242/3f2504e0-4f89-41d3-9a0c-0305e82c3301";

        @BeforeEach
        void open() {
            portalIsOpen();
            when(repository.ticketRowId(CLIENT_ID, TICKET)).thenReturn(Optional.of(TICKET_ROW));
        }

        private AttachmentRow row(String storageKey, String thumbnailKey) {
            return new AttachmentRow(9, "spec.pdf", "application/pdf", 1024,
                    storageKey, thumbnailKey, Instant.parse("2026-09-02T09:00:00Z"));
        }

        @Test
        void mintsOneForAKeyUnderThisTicket() {
            when(repository.attachments(eq(TICKET_ROW), any(), anyInt()))
                    .thenReturn(List.of(row(KEY, null)));
            when(storage.signedDownloadUrl(any(StorageKey.class), anyString(), anyString(), any()))
                    .thenReturn(URI.create("https://bucket.example/signed"));

            PortalAttachment served = service.attachments(caller, TICKET, null, 50)
                    .orElseThrow().data().getFirst();

            assertThat(served.downloadUrl()).isEqualTo("https://bucket.example/signed");
            assertThat(served.thumbnailUrl()).isNull();
        }

        /**
         * The check that holds even if {@link PortalStorageKey} is wrong.
         *
         * <p>A row whose {@code ticket_id} and whose key disagree about which
         * ticket the object belongs to is a data problem, and the portal must not
         * resolve it in the customer's favour by signing anyway.
         */
        @Test
        void refusesAKeyThatNamesADifferentTicket() {
            when(repository.attachments(eq(TICKET_ROW), any(), anyInt())).thenReturn(
                    List.of(row("tickets/9999/3f2504e0-4f89-41d3-9a0c-0305e82c3301", null)));

            PortalAttachment served = service.attachments(caller, TICKET, null, 50)
                    .orElseThrow().data().getFirst();

            assertThat(served.downloadUrl()).isNull();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void servesTheRowWithoutAUrlRatherThanDroppingIt() {
            when(repository.attachments(eq(TICKET_ROW), any(), anyInt()))
                    .thenReturn(List.of(row("tickets/4242/../secrets", null)));

            CursorPage<PortalAttachment> page =
                    service.attachments(caller, TICKET, null, 50).orElseThrow();

            // The file is genuinely there; hiding it would conceal a data problem
            // somebody needs to fix.
            assertThat(page.data()).hasSize(1);
            assertThat(page.data().getFirst().downloadUrl()).isNull();
        }
    }

    /** Mockito's {@code anyBoolean} under a name that reads in the verifies above. */
    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    /** Unused cursor helper kept out of the assertions; see {@link Cursor}. */
    @SuppressWarnings("unused")
    private static Cursor unusedCursor() {
        return new Cursor(Instant.EPOCH.toString(), 1);
    }
}
