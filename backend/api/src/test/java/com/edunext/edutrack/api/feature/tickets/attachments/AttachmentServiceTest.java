package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
            Duration.ofMinutes(5), 10L * 1024 * 1024, 50L * 1024 * 1024, 20, Duration.ofMinutes(15),
            new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false),
            new AttachmentProperties.Thumbnail(true, 320, 50_000_000L));

    /**
     * C-027 · the caps now come from here rather than from the properties.
     *
     * <p>Stubbed with §4B.4's own numbers, so every limit assertion below reads
     * exactly as it did when they were properties. The point of the seam is
     * proved in {@code AttachmentSettingsServiceTest} — that this method is
     * consulted <em>per upload</em> rather than once, which is the difference
     * between a setting that takes effect on the next attachment and one that
     * takes effect on the next restart, is pinned by
     * {@code readsTheLimitsOnEveryUpload} at the foot of this file.
     */
    private final AttachmentSettingsService limits = mock(AttachmentSettingsService.class);

    /**
     * C-028 · the listing reads through here so it can see tombstones, which
     * {@code TicketAttachmentRepository}'s {@code …IsDeletedFalse…} finder cannot.
     */
    private final AttachmentRows rows = mock(AttachmentRows.class);

    /**
     * C-028 · fixed, because §4B.4's fifteen-minute window is otherwise only
     * testable by waiting fifteen minutes. Every timestamp in this file is
     * expressed as an offset from {@link #NOW} so the window's two sides are
     * chosen rather than raced for.
     */
    private static final Instant NOW = Instant.parse("2026-08-16T14:30:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AttachmentService service = new AttachmentService(
            tickets, attachments, rows, types, stripper, storage, scans, properties, limits, clock);

    private final Authentication caller = new TestingAuthenticationToken("ravi", "n/a");

    private final List<TicketAttachment> existing = new ArrayList<>();

    @BeforeEach
    void ticketExistsAndIsInScope() {
        when(limits.effective()).thenReturn(
                AttachmentLimits.of(10L * 1024 * 1024, 50L * 1024 * 1024, 20));

        Ticket ticket = new Ticket();
        ticket.setId(TICKET);
        when(tickets.require(any(), eq(TICKET))).thenReturn(ticket);
        // C-028 · both finders are driven from `existing`, and the difference
        // between them is reproduced rather than ignored: the upload path's finder
        // filters deleted rows the way its query does, and the listing's returns
        // everything the way its query does. Answers rather than fixed returns, so
        // a test that adds a row after this runs still sees it.
        //
        // Stubbing both to the same unfiltered list would have been simpler and
        // would have quietly broken the one assertion C-028 most needs to hold —
        // that a tombstone stops counting towards §4B.4's twenty-file cap. The
        // 15-minute window is pointless if removing a file does not make room.
        when(attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET))
                .thenAnswer(call -> existing.stream().filter(row -> !row.isDeleted()).toList());
        when(rows.findByTicketIdOrderByCreatedAtAsc(TICKET)).thenAnswer(call -> List.copyOf(existing));
        when(attachments.saveAndFlush(any())).thenAnswer(call -> {
            TicketAttachment row = call.getArgument(0);
            row.setId(9001L);
            return row;
        });
    }

    private AttachmentService.Upload upload(String fileName, byte[] content) {
        return new AttachmentService.Upload(fileName, content, false, null);
    }

    /**
     * C-028 · stamp {@code created_at} on a detached row.
     *
     * <p>Reflection because the column is {@code @Generated(INSERT)} and has no
     * setter — the database owns it, which is right, and is exactly why a unit
     * test has no other way to place a row on one side of §4B.4's fifteen-minute
     * window. The alternative was adding a setter to a Stream A entity so a Stream
     * C test could reach it, which trades a real invariant for a test convenience.
     */
    private static void setCreatedAt(TicketAttachment row, Instant createdAt) {
        try {
            java.lang.reflect.Field field = TicketAttachment.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(row, createdAt);
        } catch (ReflectiveOperationException unreachable) {
            // The field is declared on the class above. If this ever throws, the
            // entity was renamed and every window assertion below is meaningless —
            // so it fails the test rather than defaulting to "now".
            throw new AssertionError("TicketAttachment.createdAt could not be set", unreachable);
        }
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

        /**
         * C-027 · the caps are resolved per upload, not held in a field.
         *
         * <p>This is the whole difference the task is about. A version that read
         * {@code effective()} once in the constructor would pass every other
         * assertion in this class and would mean an administrator's change took
         * effect on the next <em>restart</em> — which is exactly the behaviour
         * the properties already had, and exactly what §4B.4's "configurable in
         * system settings" is asking to be rid of.
         */
        @Test
        void theCapsAreReadOnEveryUploadSoAChangeAppliesToTheNextOne() {
            service.upload(caller, TICKET, upload("first.png", AttachmentFixtures.pngWithExif()));

            // An administrator drops the ticket cap below what is already stored.
            existing.add(stored(1024, "CLEAN"));
            when(limits.effective()).thenReturn(AttachmentLimits.of(512, 512, 20));

            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("second.png", AttachmentFixtures.pngWithExif())))
                    .isInstanceOf(AttachmentLimitExceededException.class);
            verify(limits, times(2)).effective();
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

    @Nested
    @DisplayName("C-026 · a thumbnail is signed on exactly the terms the file is")
    class ThumbnailUrls {

        /**
         * The port returns a {@code URI} and never null, so the double has to as
         * well — a bare mock answers null, and {@code Optional.of} on that fails
         * as an NPE inside the service rather than as anything a reader could act
         * on.
         */
        @BeforeEach
        void thePresignerAnswersAUrl() {
            when(storage.signedDownloadUrl(any(), anyString(), anyString(), any()))
                    .thenReturn(URI.create("https://minio.example/thumb?sig=abc"));
        }

        private TicketAttachment withThumbnail(String scanStatus) {
            TicketAttachment row = stored(1024, scanStatus);
            row.setThumbnailKey(AttachmentStorageKey.parse(row.getStorageKey()).thumbnail().toString());
            return row;
        }

        @Test
        void aCleanRowWithAThumbnailIsSigned() {
            assertThat(service.thumbnailUrlFor(withThumbnail("CLEAN"))).isPresent();
        }

        @Test
        void aRowWithNoThumbnailKeyIsTheOrdinaryCaseAndSignsNothing() {
            // A PDF, a log, a spreadsheet, a WebP, or an image already small
            // enough. None of them is an error and none of them is worth a call.
            assertThat(service.thumbnailUrlFor(stored(1024, "CLEAN"))).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void aPendingRowIsRefusedEvenThoughAThumbnailExists() {
            // The trap this whole nested class exists for. A thumbnail looks like
            // a preview and *is* the file on screen — §4B.4's "not visible until
            // the scan passes" covers it exactly as it covers the download.
            assertThat(service.thumbnailUrlFor(withThumbnail("PENDING"))).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void anInfectedRowIsRefused() {
            assertThat(service.thumbnailUrlFor(withThumbnail("INFECTED"))).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void aDeletedRowIsRefused() {
            TicketAttachment tombstone = withThumbnail("CLEAN");
            tombstone.setDeleted(true);

            assertThat(service.thumbnailUrlFor(tombstone)).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void theContentTypeIsAlwaysPngAndNeverTheRowsMimeType() {
            // A thumbnail is always a PNG this application encoded, whatever the
            // original was. Passing mimeType through would let a JPEG row have
            // its PNG bytes announced as image/jpeg — and, worse, would give the
            // row a say in what the browser is told it is receiving.
            TicketAttachment jpeg = withThumbnail("CLEAN");
            jpeg.setMimeType("image/jpeg");
            jpeg.setFileName("screenshot.jpg");

            service.thumbnailUrlFor(jpeg);

            verify(storage).signedDownloadUrl(any(), eq("screenshot.png"), eq("image/png"), eq(Duration.ofMinutes(5)));
        }

        @Test
        void aThumbnailKeyPointingAtAnotherTicketIsRefusedRatherThanSigned() {
            // The column came out of the database. A row edited to name another
            // ticket's object would otherwise have that object signed and served
            // — a cross-ticket read through a column nobody watches.
            TicketAttachment tampered = stored(1024, "CLEAN");
            tampered.setThumbnailKey(AttachmentStorageKey.mint(999).thumbnail().toString());

            assertThat(service.thumbnailUrlFor(tampered)).isEmpty();
            verify(storage, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        @Test
        void aMalformedThumbnailKeyCostsItsThumbnailAndNotTheWholeListing() {
            // belongsTo answers false rather than throwing, deliberately: one bad
            // row must not 500 a gallery of nineteen good ones.
            TicketAttachment tampered = stored(1024, "CLEAN");
            tampered.setThumbnailKey("../../etc/passwd");

            assertThat(service.thumbnailUrlFor(tampered)).isEmpty();
            verifyNoInteractions(storage);
        }

        @Test
        void theSavedNameCarriesThePngExtensionTheBytesActuallyHave() {
            TicketAttachment noExtension = withThumbnail("CLEAN");
            noExtension.setFileName("screenshot");

            service.thumbnailUrlFor(noExtension);

            verify(storage).signedDownloadUrl(any(), eq("screenshot.png"), anyString(), any());
        }

        @Test
        void itUsesTheSameShortTtlAsTheDownload() {
            service.thumbnailUrlFor(withThumbnail("CLEAN"));

            verify(storage).signedDownloadUrl(any(), anyString(), anyString(), eq(Duration.ofMinutes(5)));
        }
    }

    /**
     * C-028 · §4B.4's deletion rule — "the uploader may delete within 15 minutes;
     * after that it is a soft delete leaving a tombstone".
     *
     * <p>The rule has two independent halves and they are tested apart, because
     * conflating them is the mistake the implementation is shaped to avoid:
     * <b>who may remove a file</b> (this class) and <b>whether the removal leaves a
     * mark</b> ({@link Tombstones}). A version that answered the second from the
     * clock alone passes every test in this class and silently swallows the case
     * the rule exists for — a PM removing somebody else's leaked file inside the
     * window.
     */
    @Nested
    @DisplayName("C-028 · who may remove an attachment")
    class Deletion {

        private static final long RAVI = 41L;
        private static final long ANIL = 42L;

        /**
         * A caller the service can actually identify.
         *
         * <p>{@code setAuthenticated(true)} is load-bearing and is the reason this
         * helper exists rather than a bare token inline: {@code CallerIdentity.of}
         * returns empty for anything not authenticated, and an unidentifiable
         * caller is scoped to nothing — so every deletion here would have failed
         * as a 404 with the permission logic never reached, which reads as a bug
         * in the rule rather than in the fixture.
         */
        private Authentication user(long userId, String role) {
            DevPrincipal principal =
                    new DevPrincipal(userId, "u" + userId, "User " + userId, role, List.of(), List.of());
            TestingAuthenticationToken token = new TestingAuthenticationToken(principal, "n/a");
            token.setAuthenticated(true);
            return token;
        }

        private TicketAttachment uploaded(Duration ago) {
            TicketAttachment row = stored(1024, "CLEAN");
            row.setUploadedBy(RAVI);
            setCreatedAt(row, NOW.minus(ago));
            when(attachments.findById(row.getId())).thenReturn(Optional.of(row));
            return row;
        }

        @Test
        void theUploaderMayRemoveTheirOwnFile() {
            TicketAttachment row = uploaded(Duration.ofMinutes(3));

            service.delete(user(RAVI, "DEVELOPER"), TICKET, row.getId());

            assertThat(row.isDeleted()).isTrue();
            assertThat(row.getDeletedBy()).isEqualTo(RAVI);
            assertThat(row.getDeletedAt()).isEqualTo(NOW);
        }

        @Test
        void aColleagueMayNot() {
            TicketAttachment row = uploaded(Duration.ofMinutes(3));

            assertThatThrownBy(() -> service.delete(user(ANIL, "DEVELOPER"), TICKET, row.getId()))
                    .isInstanceOf(AttachmentDeletionNotPermittedException.class)
                    // The wording is asserted, not just the type. Both refusals
                    // are the same exception and the same 403, and the only thing
                    // separating them is the sentence the user reads — so a
                    // regression in the choice is invisible to a type assertion
                    // and visible to nobody until it is on screen. Inside the
                    // window the honest message is that they may simply be about
                    // to ask the uploader.
                    .hasMessageContaining("first few minutes");

            // And nothing happened — refusing after removing the object would
            // leave a row pointing at bytes that are gone.
            assertThat(row.isDeleted()).isFalse();
            verifyNoInteractions(storage);
        }

        @Test
        void aColleagueIsToldTheWindowHasClosedOnceItHas() {
            // The other half of the same refusal. Outside the window nobody but a
            // PM or Admin can act at all, and saying so saves a wasted request —
            // where "wait and you may be able to" would be false.
            TicketAttachment row = uploaded(Duration.ofDays(1));

            assertThatThrownBy(() -> service.delete(user(ANIL, "DEVELOPER"), TICKET, row.getId()))
                    .isInstanceOf(AttachmentDeletionNotPermittedException.class)
                    .hasMessageContaining("window for removing this file has passed");

            assertThat(row.isDeleted()).isFalse();
        }

        @Test
        void aPmMayRemoveSomebodyElsesFile() {
            // §4B.4 puts is_client_visible on the same row as the deletion rule,
            // and this is the case the pairing anticipates: an internal debug log
            // attached as client-visible is a disclosure, and it cannot wait for a
            // timer to expire or for its uploader to come back from leave.
            TicketAttachment row = uploaded(Duration.ofMinutes(3));

            service.delete(user(ANIL, "PM"), TICKET, row.getId());

            assertThat(row.isDeleted()).isTrue();
            assertThat(row.getDeletedBy()).isEqualTo(ANIL);
        }

        @Test
        void soMayAnAdmin() {
            TicketAttachment row = uploaded(Duration.ofHours(30));

            service.delete(user(ANIL, "ADMIN"), TICKET, row.getId());

            assertThat(row.isDeleted()).isTrue();
        }

        @Test
        void theUploaderMayStillRemoveItLongAfterTheWindow() {
            // The window decides whether the removal is silent, NOT whether the
            // uploader is still allowed to make it. §4B.4 never withdraws their
            // permission, and a version that did would leave the person who
            // attached a file the only one who could not take it off.
            TicketAttachment row = uploaded(Duration.ofDays(2));

            service.delete(user(RAVI, "DEVELOPER"), TICKET, row.getId());

            assertThat(row.isDeleted()).isTrue();
        }

        @Test
        void bothStoredObjectsGoAndTheRowStays() {
            // The row surviving is the whole design: deleted_by and deleted_at
            // exist to be read afterwards, and C-034's timeline cannot place an
            // attachment whose row is gone. The thumbnail goes with it, because a
            // small legible picture of the removed file is still the removed file.
            TicketAttachment row = uploaded(Duration.ofMinutes(1));
            AttachmentStorageKey key = AttachmentStorageKey.parse(row.getStorageKey());

            service.delete(user(RAVI, "DEVELOPER"), TICKET, row.getId());

            verify(storage).delete(key);
            verify(storage).delete(key.thumbnail());
            verify(attachments).save(row);
            verify(attachments, never()).delete(any());
            verify(attachments, never()).deleteById(anyLong());
        }

        @Test
        void aSecondDeleteIsANoOpRatherThanARefusal() {
            // The client removes optimistically and a retry after a dropped
            // response is ordinary. Refusing would also distinguish "already
            // removed" from "never existed" for anyone allowed to ask.
            TicketAttachment row = uploaded(Duration.ofMinutes(1));
            service.delete(user(RAVI, "DEVELOPER"), TICKET, row.getId());
            Instant firstRemoval = row.getDeletedAt();

            service.delete(user(ANIL, "DEVELOPER"), TICKET, row.getId());

            // Not merely "did not throw" — the original tombstone is intact. A
            // second delete that re-stamped it would rewrite who removed the file.
            assertThat(row.getDeletedBy()).isEqualTo(RAVI);
            assertThat(row.getDeletedAt()).isEqualTo(firstRemoval);
            // Two, not one: the first delete removes the object and its thumbnail.
            // The point is that the second added none — an early return before the
            // storage calls, not merely before the row is re-stamped.
            verify(storage, times(2)).delete(any());
            verify(attachments, times(1)).save(any());
        }

        @Test
        void anAttachmentOnAnotherTicketIs404AndNot403() {
            // The probe this closes: ids are bare BIGINTs, so without the check
            // /tickets/347/attachments/{id} would answer differently depending on
            // whether the id belongs to ticket 347 — which enumerates them.
            TicketAttachment elsewhere = stored(1024, "CLEAN");
            elsewhere.setTicketId(999L);
            when(attachments.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));

            assertThatThrownBy(() -> service.delete(user(RAVI, "ADMIN"), TICKET, elsewhere.getId()))
                    .isInstanceOf(AttachmentNotFoundException.class);
        }

        @Test
        void anOutOfScopeTicketIs404BeforeTheAttachmentIsEvenLookedUp() {
            when(tickets.require(any(), eq(999L))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.delete(user(RAVI, "ADMIN"), 999L, 1L))
                    .isInstanceOf(TicketNotFoundException.class);

            // Row scope is asked first, so an out-of-scope caller cannot even
            // learn that an attachment id resolves.
            verifyNoInteractions(storage);
            verify(attachments, never()).findById(anyLong());
        }

        @Test
        void aTombstoneStopsCountingTowardsTheTwentyFileCap() {
            // Without this the 15-minute window is pointless: a user who hits the
            // cap, removes a file and tries again would still be refused, and the
            // ticket would be permanently full with nineteen files on it.
            for (int i = 0; i < 20; i++) {
                existing.add(stored(1024, "CLEAN"));
            }
            assertThatThrownBy(() -> service.upload(caller, TICKET, upload("a.png", AttachmentFixtures.pngWithExif())))
                    .isInstanceOf(AttachmentLimitExceededException.class);

            existing.getFirst().setDeleted(true);

            assertThatCode(() -> service.upload(caller, TICKET, upload("a.png", AttachmentFixtures.pngWithExif())))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * C-028 · whether a removal leaves a visible mark — the half decided at read
     * time, from data already on the row.
     */
    @Nested
    @DisplayName("C-028 · tombstones")
    class Tombstones {

        private static final long RAVI = 41L;
        private static final long ANIL = 42L;

        private TicketAttachment removed(Long uploader, Long remover, Duration after) {
            TicketAttachment row = stored(1024, "CLEAN");
            row.setUploadedBy(uploader);
            setCreatedAt(row, NOW);
            row.setDeleted(true);
            row.setDeletedBy(remover);
            row.setDeletedAt(NOW.plus(after));
            existing.add(row);
            return row;
        }

        @Test
        void theUploaderRemovingTheirOwnFilePromptlyLeavesNothingBehind() {
            // A support agent pastes the wrong screenshot and removes it. The
            // ticket has no reason to remember that, and a permanent "file removed
            // by …" for every mis-paste trains everyone to read past the line that
            // matters.
            removed(RAVI, RAVI, Duration.ofMinutes(3));

            assertThat(service.list(caller, TICKET, null, null)).isEmpty();
        }

        @Test
        void theUploaderRemovingItAfterTheWindowLeavesATombstone() {
            TicketAttachment row = removed(RAVI, RAVI, Duration.ofMinutes(16));

            assertThat(service.list(caller, TICKET, null, null)).containsExactly(row);
        }

        @Test
        void someoneElseRemovingItInsideTheWindowStillLeavesATombstone() {
            // The assertion this whole class exists for. A clock-only rule would
            // read three minutes and hide a PM's supervisory removal — which is
            // precisely the deletion the ticket most needs to record.
            TicketAttachment row = removed(RAVI, ANIL, Duration.ofMinutes(3));

            assertThat(service.list(caller, TICKET, null, null)).containsExactly(row);
        }

        @Test
        void aTombstoneIsNeverDownloadable() {
            // The bytes are gone, not hidden — isReadable already refuses a deleted
            // row, so the tombstone inherits it rather than restating it.
            TicketAttachment row = removed(RAVI, ANIL, Duration.ofMinutes(3));

            assertThat(service.signedUrlFor(row)).isEmpty();
            assertThat(service.thumbnailUrlFor(row)).isEmpty();
        }

        @Test
        void anInternalFilesTombstoneDoesNotSurfaceOnTheClientPortal() {
            // The tombstone inherits the visibility of the file it replaces,
            // because it is still a statement about that file — "debug-log.txt was
            // removed" names the internal file as surely as serving it would.
            removed(RAVI, ANIL, Duration.ofMinutes(3));

            assertThat(service.list(caller, TICKET, null, true)).isEmpty();
        }

        @Test
        void aRowWithNoRemovalTimestampShowsRatherThanHides() {
            // A row deleted before this task existed, or written by hand, cannot be
            // placed inside the window. Showing it is the safe direction: a
            // tombstone shown where it need not be is a cosmetic surprise, one
            // hidden that should have shown is the loss of the record §4B.4 asked
            // for.
            TicketAttachment row = removed(RAVI, RAVI, Duration.ZERO);
            row.setDeletedAt(null);

            assertThat(service.list(caller, TICKET, null, null)).containsExactly(row);
        }
    }
}
