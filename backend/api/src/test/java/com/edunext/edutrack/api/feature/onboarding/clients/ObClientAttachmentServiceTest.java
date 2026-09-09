package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentDtos;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentOwner;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentPipeline;
import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-107 · who may file a document, who may remove one, and which removals the
 * record keeps.
 *
 * <p>What earns a container is in {@code ObClientAttachmentsIT}: the CHECK
 * constraints, and the one thing no mock can assert — that a tombstoned row
 * survives and still resolves. Everything below is a decision made in Java
 * before any of that is reached, which is {@code ObRequirementServiceTest}'s own
 * split.
 *
 * <p>{@link ObAttachmentPipeline} is mocked here deliberately, and it is the one
 * collaborator where that needs saying: it is a security boundary, so it has its
 * own test over the real beans. What this class asserts is that the pipeline is
 * <em>reached only when it should be</em> — which a real one would obscure
 * rather than prove.
 */
class ObClientAttachmentServiceTest {

    private static final long CLIENT = 42L;
    private static final long CALLER = 3L;
    private static final long SOMEBODY_ELSE = 9L;
    private static final long ATTACHMENT = 101L;

    private static final Instant UPLOADED = Instant.parse("2026-09-08T10:00:00Z");
    private static final Instant SOON = UPLOADED.plus(Duration.ofMinutes(2));
    private static final Instant LATER = UPLOADED.plus(Duration.ofHours(3));

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope MANAGER = new ObClientScope(ObClientScope.OB_MANAGER, CALLER);
    private static final ObClientScope SALES = new ObClientScope(ObClientScope.OB_SALES, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);

    private ObClientService details;
    private ObAttachmentPipeline pipeline;
    private ObAttachmentRepository attachments;
    private UserRepository users;
    private UploadPipeline uploads;

    @BeforeEach
    void setUp() {
        details = mock(ObClientService.class);
        pipeline = mock(ObAttachmentPipeline.class);
        attachments = mock(ObAttachmentRepository.class);
        users = mock(UserRepository.class);
        uploads = mock(UploadPipeline.class);
        when(uploads.removalWindow()).thenReturn(Duration.ofMinutes(15));

        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
        when(users.findAllById(any())).thenReturn(List.of());
        when(pipeline.signedUrlFor(any())).thenReturn(Optional.empty());
    }

    // ── standing ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("standing")
    class Standing {

        /**
         * 404 before 403 and never the reverse — and here it buys something
         * extra: the sniffing, stripping and storing of a 10 MB file must not
         * happen for a client the caller cannot see.
         */
        @Test
        @DisplayName("a client out of scope is 404 before a single byte is examined")
        void outOfScopeClientIs404() {
            when(details.findDetail(any(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().upload(SALES, CLIENT, null, "msa.pdf", new byte[8]))
                    .isInstanceOf(ObClientNotFoundException.class);

            verifyNoInteractions(pipeline);
        }

        @Test
        @DisplayName("a Viewer may read the documents but not add one")
        void viewerReadsAndCannotWrite() {
            when(attachments.findByObClientIdOrderByIdAsc(CLIENT)).thenReturn(List.of(clean()));

            assertThat(service().list(VIEWER, CLIENT)).hasSize(1);

            assertThatThrownBy(() -> service().upload(VIEWER, CLIENT, null, "msa.pdf", new byte[8]))
                    .isInstanceOf(ObClientReadOnlyException.class);
            // `never().store` rather than `verifyNoInteractions`: the read above
            // legitimately asks the pipeline to sign a URL, which is the whole
            // point of a Viewer being able to read.
            verify(pipeline, never()).store(any(), anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Admin, Manager and Sales may all file a document")
        void writersMayUpload() {
            when(pipeline.store(any(), anyLong(), any(), any(), any(), any()))
                    .thenReturn(clean());

            for (ObClientScope writer : List.of(ADMIN, MANAGER, SALES)) {
                assertThat(service().upload(writer, CLIENT, null, "msa.pdf", new byte[8])).isNotNull();
            }
        }
    }

    // ── the kind narrowing ──────────────────────────────────────────────────

    @Nested
    @DisplayName("kind")
    class Kind {

        @Test
        @DisplayName("defaults to SUBMISSION, the column's own default")
        void defaultsToSubmission() {
            when(pipeline.store(any(), anyLong(), any(), any(), any(), any())).thenReturn(clean());

            service().upload(ADMIN, CLIENT, null, "msa.pdf", new byte[8]);

            ArgumentCaptor<ObAttachmentKind> kind = ArgumentCaptor.forClass(ObAttachmentKind.class);
            verify(pipeline).store(any(), anyLong(), kind.capture(), any(), any(), any());
            assertThat(kind.getValue()).isEqualTo(ObAttachmentKind.SUBMISSION);
        }

        @Test
        @DisplayName("accepts REFERENCE, the document staff attach for the client to read")
        void acceptsReference() {
            when(pipeline.store(any(), anyLong(), any(), any(), any(), any())).thenReturn(clean());

            service().upload(ADMIN, CLIENT, "reference", "form.pdf", new byte[8]);

            ArgumentCaptor<ObAttachmentKind> kind = ArgumentCaptor.forClass(ObAttachmentKind.class);
            verify(pipeline).store(any(), anyLong(), kind.capture(), any(), any(), any());
            assertThat(kind.getValue()).isEqualTo(ObAttachmentKind.REFERENCE);
        }

        /**
         * A category boundary the database cannot draw: its CHECK constrains
         * which owner column is set and says nothing about which kinds go with
         * which owner. Left open, the first caller to send EVIDENCE would create
         * a row B-116's sign-off reader has no way to find and OB-05 would
         * render as an ordinary document.
         */
        @Test
        @DisplayName("refuses the two kinds that belong to other owner arms")
        void refusesOtherArmsKinds() {
            for (String foreign : List.of("DELIVERABLE", "EVIDENCE")) {
                assertThatThrownBy(() ->
                        service().upload(ADMIN, CLIENT, foreign, "x.pdf", new byte[8]))
                        .isInstanceOf(ObClientValidationException.class);
            }
            verifyNoInteractions(pipeline);
        }

        @Test
        @DisplayName("always files against the client arm, whatever else is passed")
        void ownerIsAlwaysTheClient() {
            when(pipeline.store(any(), anyLong(), any(), any(), any(), any())).thenReturn(clean());

            service().upload(ADMIN, CLIENT, null, "msa.pdf", new byte[8]);

            ArgumentCaptor<ObAttachmentOwner> owner = ArgumentCaptor.forClass(ObAttachmentOwner.class);
            verify(pipeline).store(owner.capture(), anyLong(), any(), any(), any(), any());
            assertThat(owner.getValue()).isEqualTo(ObAttachmentOwner.CLIENT);
        }
    }

    // ── which tombstones are visible ────────────────────────────────────────

    @Nested
    @DisplayName("listing")
    class Listing {

        /**
         * Hiding them would make a scan delay indistinguishable from a failed
         * upload and would leave somebody re-attaching the same file. The rule is
         * that the file not become readable, and the absent URL is what enforces
         * that.
         */
        @Test
        @DisplayName("returns PENDING and INFECTED rows rather than hiding them")
        void returnsUnreadableRows() {
            when(attachments.findByObClientIdOrderByIdAsc(CLIENT))
                    .thenReturn(List.of(row(ObAttachmentScanStatus.PENDING, CALLER, null, null),
                            row(ObAttachmentScanStatus.INFECTED, CALLER, null, null)));

            List<ObAttachmentDtos.ObAttachmentView> views = service().list(ADMIN, CLIENT);

            assertThat(views).hasSize(2);
            assertThat(views).allSatisfy(view -> assertThat(view.downloadUrl()).isNull());
        }

        /**
         * Somebody who drops the wrong PDF and removes it ten seconds later has
         * not done anything the client record needs to remember, and a permanent
         * note for every mis-drop would train everyone to read past the line that
         * matters.
         */
        @Test
        @DisplayName("hides a tombstone the uploader made promptly")
        void hidesTheSelfCorrection() {
            when(attachments.findByObClientIdOrderByIdAsc(CLIENT))
                    .thenReturn(List.of(row(ObAttachmentScanStatus.CLEAN, CALLER, SOON, CALLER)));

            assertThat(service().list(ADMIN, CLIENT)).isEmpty();
        }

        /**
         * The uploader comparison is what makes the rule right rather than merely
         * time-based: a Manager removing a leaked document at minute two is
         * inside the window and is not the uploader, and that removal is exactly
         * the supervisory act the tombstone exists to record. A clock-only rule
         * would swallow it.
         */
        @Test
        @DisplayName("shows a tombstone somebody else made, even inside the window")
        void showsTheSupervisoryRemoval() {
            when(attachments.findByObClientIdOrderByIdAsc(CLIENT))
                    .thenReturn(List.of(row(ObAttachmentScanStatus.CLEAN, CALLER, SOON, SOMEBODY_ELSE)));

            List<ObAttachmentDtos.ObAttachmentView> views = service().list(ADMIN, CLIENT);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).isDeleted()).isTrue();
            assertThat(views.get(0).downloadUrl()).isNull();
        }

        @Test
        @DisplayName("shows a tombstone the uploader made after the window closed")
        void showsTheLateSelfRemoval() {
            when(attachments.findByObClientIdOrderByIdAsc(CLIENT))
                    .thenReturn(List.of(row(ObAttachmentScanStatus.CLEAN, CALLER, LATER, CALLER)));

            assertThat(service().list(ADMIN, CLIENT)).hasSize(1);
        }

        /**
         * A tombstone shown where it need not be is a cosmetic surprise; one
         * hidden that should have shown is the loss of the record itself. So
         * unplaceable data shows.
         */
        @Test
        @DisplayName("shows a tombstone it cannot place in time")
        void showsTheUnplaceable() {
            ObAttachment row = row(ObAttachmentScanStatus.CLEAN, CALLER, SOON, CALLER);
            setCreatedAt(row, null);

            when(attachments.findByObClientIdOrderByIdAsc(CLIENT)).thenReturn(List.of(row));

            assertThat(service().list(ADMIN, CLIENT)).hasSize(1);
        }
    }

    // ── who may remove ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("removal")
    class Removal {

        @Test
        @DisplayName("lets the uploader remove their own document")
        void uploaderMayRemove() {
            given(clean());

            service().delete(SALES, CLIENT, ATTACHMENT);

            verify(pipeline).tombstone(any(), anyLong());
        }

        @Test
        @DisplayName("lets an Admin or a Manager remove anybody's")
        void supervisorsMayRemove() {
            for (ObClientScope supervisor : List.of(ADMIN, MANAGER)) {
                ObAttachmentPipeline fresh = mock(ObAttachmentPipeline.class);
                pipeline = fresh;
                given(row(ObAttachmentScanStatus.CLEAN, SOMEBODY_ELSE, null, null));

                service().delete(supervisor, CLIENT, ATTACHMENT);

                verify(fresh).tombstone(any(), anyLong());
            }
        }

        /**
         * Sales may upload and may not remove somebody else's. Their scope
         * already limits them to clients they created; within those, the
         * supervisory removal belongs to the two roles plan §3 gives oversight
         * to.
         */
        @Test
        @DisplayName("refuses Sales removing a colleague's document — 403, not 404")
        void salesMayNotRemoveAnothersDocument() {
            given(row(ObAttachmentScanStatus.CLEAN, SOMEBODY_ELSE, null, null));

            assertThatThrownBy(() -> service().delete(SALES, CLIENT, ATTACHMENT))
                    .isInstanceOf(ObClientAttachmentRemovalNotPermittedException.class);

            verify(pipeline, never()).tombstone(any(), anyLong());
        }

        /**
         * A client's own submission has no staff uploader at all, so no staff
         * user is ever "the uploader" of one. Removing it is always a
         * supervisory act.
         */
        @Test
        @DisplayName("nobody is the uploader of a file that came from the portal")
        void portalUploadsHaveNoStaffUploader() {
            ObAttachment portalFile = row(ObAttachmentScanStatus.CLEAN, null, null, null);
            portalFile.setUploadedByType(ObAttachmentUploaderType.CLIENT);
            portalFile.setUploadedByContact(11L);
            given(portalFile);

            assertThatThrownBy(() -> service().delete(SALES, CLIENT, ATTACHMENT))
                    .isInstanceOf(ObClientAttachmentRemovalNotPermittedException.class);
        }

        /**
         * The caller asked for the file to be gone and it is gone. Refusing would
         * also distinguish "already removed" from "never existed" for anyone
         * allowed to ask.
         */
        @Test
        @DisplayName("a second removal is a no-op, not a 404 and not a 403")
        void isIdempotentAndDoesNotConsultTheRole() {
            given(row(ObAttachmentScanStatus.CLEAN, SOMEBODY_ELSE, LATER, SOMEBODY_ELSE));

            service().delete(VIEWER, CLIENT, ATTACHMENT);

            verify(pipeline, never()).tombstone(any(), anyLong());
        }

        @Test
        @DisplayName("an attachment id under another client is 404")
        void anotherClientsAttachmentIs404() {
            when(attachments.findByIdAndObClientId(ATTACHMENT, CLIENT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().delete(ADMIN, CLIENT, ATTACHMENT))
                    .isInstanceOf(ObClientAttachmentNotFoundException.class);
        }

        @Test
        @DisplayName("an out-of-scope client is 404 before the attachment is resolved")
        void outOfScopeClientIs404First() {
            when(details.findDetail(any(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().delete(ADMIN, CLIENT, ATTACHMENT))
                    .isInstanceOf(ObClientNotFoundException.class);

            verifyNoInteractions(attachments);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ObClientAttachmentService service() {
        return new ObClientAttachmentService(details, pipeline, attachments, users,
                uploads, Clock.fixed(LATER, ZoneOffset.UTC));
    }

    private void given(ObAttachment row) {
        when(attachments.findByIdAndObClientId(ATTACHMENT, CLIENT)).thenReturn(Optional.of(row));
    }

    private static ObAttachment clean() {
        return row(ObAttachmentScanStatus.CLEAN, CALLER, null, null);
    }

    private static ObAttachment row(ObAttachmentScanStatus status, Long uploadedBy,
                                    Instant deletedAt, Long deletedBy) {
        ObAttachment row = new ObAttachment();
        row.setId(ATTACHMENT);
        row.setObClientId(CLIENT);
        row.setKind(ObAttachmentKind.SUBMISSION);
        row.setUploadedByType(ObAttachmentUploaderType.STAFF);
        row.setUploadedByUser(uploadedBy);
        row.setFileName("msa.pdf");
        row.setContentType("application/pdf");
        row.setSizeBytes(64);
        // Written out rather than minted: ObAttachmentStorageKey is
        // package-private in the attachments package, which is right — nothing
        // outside the pipeline should be able to build a key. The shape is
        // pinned by ObAttachmentStorageKeyTest.
        row.setStorageKey("onboarding/clients/" + CLIENT + "/" + UUID.randomUUID());
        row.setScanStatus(status);
        row.setDeletedAt(deletedAt);
        row.setDeletedBy(deletedBy);
        setCreatedAt(row, UPLOADED);
        return row;
    }

    /**
     * {@code created_at} is written by the database and the entity maps it
     * {@code insertable = false}, so there is no setter. The tombstone-visibility
     * rule is measured from it, which makes it the one field this test cannot do
     * without.
     */
    private static void setCreatedAt(ObAttachment row, Instant at) {
        try {
            var field = ObAttachment.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(row, at);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("ObAttachment.createdAt has been renamed", impossible);
        }
    }

    private static ObClientDtos.ObClientDetail detailStub() {
        return new ObClientDtos.ObClientDetail(
                CLIENT, "Acme", LocalDate.of(2026, 9, 7), "ONBOARDING", null, "LOCKED", 1, 0,
                null, List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null);
    }
}
