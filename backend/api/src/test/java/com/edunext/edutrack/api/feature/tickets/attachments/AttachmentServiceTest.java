package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-025 · the pipeline, and the order it runs in.
 *
 * <p>Most of these assertions are about <em>sequence</em> rather than outcome,
 * because the sequence is the security property: each step exists to stop the
 * next one from seeing something it should not, and a pipeline that produced the
 * same rejections in a different order would still store a file it was about to
 * refuse.
 */
class AttachmentServiceTest {

    private static final long TICKET = 347L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketAttachmentRepository attachments = mock(TicketAttachmentRepository.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final AttachmentScanTask scans = mock(AttachmentScanTask.class);
    private final ImageMetadataStripper stripper = new ImageMetadataStripper();
    private final AttachmentTypePolicy types = new AttachmentTypePolicy(new AttachmentSniffer());

    private final AttachmentProperties properties = new AttachmentProperties(
            Duration.ofMinutes(5), 10L * 1024 * 1024, 50L * 1024 * 1024, 20,
            new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false));

    private final AttachmentService service =
            new AttachmentService(tickets, attachments, types, stripper, storage, scans, properties);

    private final Authentication caller = new TestingAuthenticationToken("ravi", "n/a");

    private final List<TicketAttachment> existing = new ArrayList<>();

    @BeforeEach
    void ticketExistsAndIsInScope() {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET);
        when(tickets.require(any(), eq(TICKET))).thenReturn(ticket);
        when(attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET)).thenReturn(existing);
        when(attachments.saveAndFlush(any())).thenAnswer(call -> {
            TicketAttachment row = call.getArgument(0);
            row.setId(9001L);
            return row;
        });
    }

    private AttachmentService.Upload upload(String fileName, byte[] content) {
        return new AttachmentService.Upload(fileName, content, false, null);
    }

    private static TicketAttachment stored(long sizeBytes, String scanStatus) {
        TicketAttachment row = new TicketAttachment();
        row.setId(1L);
        row.setTicketId(TICKET);
        row.setFileName("prior.png");
        row.setStorageKey(AttachmentStorageKey.mint(TICKET).toString());
        row.setMimeType("image/png");
        row.setSizeBytes(sizeBytes);
        row.setScanStatus(scanStatus);
        return row;
    }

    @Nested
    @DisplayName("scope is asked first, before anything is read or stored")
    class Scope {

        @Test
        void anOutOfScopeTicketIs404AndNothingIsStored() {
            when(tickets.require(any(), eq(999L))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.upload(caller, 999L, upload("a.png", AttachmentFixtures.pngWithExif())))
                    .isInstanceOf(TicketNotFoundException.class);

            // A-035: absence, not refusal — and no side effect at all, so a
            // caller cannot learn a ticket exists by watching what happens.
            verifyNoInteractions(storage);
            verify(attachments, never()).saveAndFlush(any());
            verifyNoInteractions(scans);
        }
    }

    @Nested
    @DisplayName("a refused file never reaches storage")
    class NothingStoredBeforeAcceptance {

        @Test
        void aDisguisedExecutableIsRefusedAndNoObjectIsWritten() {
            assertThatThrownBy(() -> service.upload(caller, TICKET,
                    upload("payroll.pdf", AttachmentFixtures.windowsExecutable())))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class);

            verifyNoInteractions(storage);
            verify(attachments, never()).saveAndFlush(any());
        }

        @Test
        void anOversizedFileIsRefusedBeforeItsBytesAreEvenExamined() {
            byte[] huge = new byte[11 * 1024 * 1024];
            System.arraycopy(AttachmentFixtures.pngSignature(), 0, huge, 0, 8);

            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("big.png", huge)))
                    .isInstanceOf(AttachmentLimitExceededException.class)
                    .hasMessageContaining("10 MB");

            verifyNoInteractions(storage);
        }
    }

    @Nested
    @DisplayName("§4B.4's per-ticket caps")
    class Limits {

        @Test
        void aTwentyFirstAttachmentIsRefused() {
            for (int i = 0; i < 20; i++) {
                existing.add(stored(1024, "CLEAN"));
            }
            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("a.png", AttachmentFixtures.pngWithExif())))
                    .isInstanceOf(AttachmentLimitExceededException.class)
                    .hasMessageContaining("20 attachments");
        }

        @Test
        void aFileThatWouldTakeTheTicketPastFiftyMegabytesIsRefused() {
            existing.add(stored(49L * 1024 * 1024, "CLEAN"));

            byte[] twoMegabytes = new byte[2 * 1024 * 1024];
            System.arraycopy(AttachmentFixtures.pdf(), 0, twoMegabytes, 0, AttachmentFixtures.pdf().length);

            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("report.pdf", twoMegabytes)))
                    .isInstanceOf(AttachmentLimitExceededException.class)
                    .hasMessageContaining("50 MB");
        }

        @Test
        void aPendingUploadStillCountsTowardsTheTicketTotal() {
            // Otherwise twenty files uploaded in parallel each see nineteen.
            existing.add(stored(49L * 1024 * 1024, "PENDING"));

            byte[] twoMegabytes = new byte[2 * 1024 * 1024];
            System.arraycopy(AttachmentFixtures.pdf(), 0, twoMegabytes, 0, AttachmentFixtures.pdf().length);

            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("report.pdf", twoMegabytes)))
                    .isInstanceOf(AttachmentLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("what is stored, and in what order")
    class Pipeline {

        @Test
        void theStrippedBytesAreStoredAndNotTheOriginals() {
            service.upload(caller, TICKET, upload("screenshot.png", AttachmentFixtures.pngWithExif()));

            var content = org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(storage).put(any(), content.capture(), eq("image/png"));
            assertThat(new String(content.getValue(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("51.5074")
                    .doesNotContain("eXIf");
        }

        @Test
        void theStoredContentTypeIsTheSniffedOneAndNotTheClientsClaim() {
            // The Upload record carries no client content type at all, which is
            // the strongest form of "it is never consulted": there is nothing to
            // consult. This asserts what does get used.
            service.upload(caller, TICKET, upload("export.csv", AttachmentFixtures.text("id,name\n1,Priya\n")));
            verify(storage).put(any(), any(), eq("text/csv"));
        }

        @Test
        void theObjectIsWrittenBeforeTheRow() {
            // A row pointing at an object that failed to write is a broken
            // attachment the user can see; an object with no row is invisible
            // litter the PENDING sweeper collects.
            service.upload(caller, TICKET, upload("signoff.pdf", AttachmentFixtures.pdf()));

            InOrder order = inOrder(storage, attachments, scans);
            order.verify(storage).put(any(), any(), anyString());
            order.verify(attachments).saveAndFlush(any());
            order.verify(scans).submit(anyLong());
        }

        @Test
        void theRowIsInsertedPendingWithAWellFormedKey() {
            var saved = org.mockito.ArgumentCaptor.forClass(TicketAttachment.class);
            service.upload(caller, TICKET, upload("signoff.pdf", AttachmentFixtures.pdf()));
            verify(attachments).saveAndFlush(saved.capture());

            TicketAttachment row = saved.getValue();
            assertThat(row.getScanStatus()).isEqualTo("PENDING");
            assertThat(row.getStorageKey()).matches("^tickets/347/[0-9a-f-]{36}$");
            assertThat(row.getMimeType()).isEqualTo("application/pdf");
            assertThat(row.getFileName()).isEqualTo("signoff.pdf");
        }

        @Test
        void theSizeRecordedIsTheStrippedSizeAndNotTheUploadedOne() {
            // The stripped file is what storage holds and what the per-ticket
            // budget is spent on. Recording the pre-strip size would make the
            // 50 MB cap drift away from what is actually stored.
            byte[] original = AttachmentFixtures.pngWithExif();
            var saved = org.mockito.ArgumentCaptor.forClass(TicketAttachment.class);

            service.upload(caller, TICKET, upload("screenshot.png", original));
            verify(attachments).saveAndFlush(saved.capture());

            assertThat(saved.getValue().getSizeBytes()).isLessThan(original.length);
        }

        @Test
        void theScanIsQueuedForTheRowThatWasJustInserted() {
            service.upload(caller, TICKET, upload("signoff.pdf", AttachmentFixtures.pdf()));
            verify(scans).submit(9001L);
        }
    }

    @Nested
    @DisplayName("a signed URL is issued only for a file that has been vouched for")
    class Visibility {

        @BeforeEach
        void storageSigns() {
            when(storage.signedDownloadUrl(any(), anyString(), anyString(), any()))
                    .thenReturn(URI.create("https://minio.example/signed"));
        }

        @Test
        void cleanGetsOne() {
            assertThat(service.signedUrlFor(stored(1024, "CLEAN")))
                    .contains(URI.create("https://minio.example/signed"));
        }

        @Test
        void pendingDoesNot() {
            // §4B.4: "before the file becomes visible". This is that sentence.
            assertThat(service.signedUrlFor(stored(1024, "PENDING"))).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void infectedDoesNot() {
            assertThat(service.signedUrlFor(stored(1024, "INFECTED"))).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void aDeletedAttachmentDoesNotEvenIfItWasClean() {
            TicketAttachment tombstone = stored(1024, "CLEAN");
            tombstone.setDeleted(true);
            assertThat(service.signedUrlFor(tombstone)).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void theUrlIsSignedWithTheConfiguredShortTtl() {
            service.signedUrlFor(stored(1024, "CLEAN"));
            verify(storage).signedDownloadUrl(any(), eq("prior.png"), eq("image/png"), eq(Duration.ofMinutes(5)));
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        void pendingAndInfectedRowsAreListedRatherThanHidden() {
            // Hiding them would make a scan delay indistinguishable from a
            // failed upload and leave the user re-attaching the same file. The
            // rule §4B.4 states is about readability, and that is enforced by
            // the absent download URL.
            existing.add(stored(1024, "PENDING"));
            existing.add(stored(2048, "INFECTED"));

            assertThat(service.list(caller, TICKET, null, null)).hasSize(2);
        }

        @Test
        void anOutOfScopeTicketIs404OnTheReadPathToo() {
            when(tickets.require(any(), eq(999L))).thenThrow(new TicketNotFoundException());
            assertThatThrownBy(() -> service.list(caller, 999L, null, null))
                    .isInstanceOf(TicketNotFoundException.class);
        }

        @Test
        void clientVisibleOnlyFiltersToTheFlaggedRows() {
            TicketAttachment internal = stored(1024, "CLEAN");
            TicketAttachment shared = stored(2048, "CLEAN");
            shared.setClientVisible(true);
            existing.add(internal);
            existing.add(shared);

            assertThat(service.list(caller, TICKET, null, true))
                    .containsExactly(shared);
        }

        @Test
        void cycleFiltersToOneCyclesAttachments() {
            TicketAttachment first = stored(1024, "CLEAN");
            first.setCycleNo((short) 1);
            TicketAttachment second = stored(2048, "CLEAN");
            second.setCycleNo((short) 2);
            existing.add(first);
            existing.add(second);

            assertThat(service.list(caller, TICKET, 1, null)).containsExactly(first);
            assertThat(service.list(caller, TICKET, 2, null)).containsExactly(second);
        }
    }

    @Nested
    @DisplayName("the cycle and stage stamp comes from the ticket, never from the request")
    class Stamping {

        @Test
        void aClientCannotFileEvidenceIntoACycleOfItsChoosing() {
            Ticket ticket = new Ticket();
            ticket.setId(TICKET);
            ticket.setCurrentCycleNo((short) 2);
            ticket.setCurrentStage("QA");
            when(tickets.require(any(), eq(TICKET))).thenReturn(ticket);

            var saved = org.mockito.ArgumentCaptor.forClass(TicketAttachment.class);
            service.upload(caller, TICKET, upload("signoff.pdf", AttachmentFixtures.pdf()));
            verify(attachments).saveAndFlush(saved.capture());

            assertThat(saved.getValue().getCycleNo()).isEqualTo((short) 2);
            assertThat(saved.getValue().getStageCode()).isEqualTo("QA");
            // And the Upload record has no field for either, which is what makes
            // the guarantee structural rather than a rule to remember.
            assertThat(AttachmentService.Upload.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("fileName", "content", "clientVisible", "commentId");
        }
    }

    @Nested
    @DisplayName("an attachment that is not on the list is never issued a URL")
    class Sanity {

        @Test
        void aRowWithAMalformedStorageKeyFailsLoudlyRatherThanBeingSigned() {
            TicketAttachment tampered = stored(1024, "CLEAN");
            tampered.setStorageKey("../../etc/passwd");

            assertThatThrownBy(() -> service.signedUrlFor(tampered))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(storage);
        }
    }

    @Nested
    @DisplayName("Optional is the return, so a caller cannot forget the check")
    class Shape {

        @Test
        void signedUrlForReturnsAnOptionalRatherThanANullableUri() {
            assertThat(service.signedUrlFor(stored(1024, "PENDING")))
                    .isInstanceOf(Optional.class)
                    .isEmpty();
        }
    }
}
