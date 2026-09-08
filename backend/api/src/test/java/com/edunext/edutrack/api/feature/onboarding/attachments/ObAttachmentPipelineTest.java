package com.edunext.edutrack.api.feature.onboarding.attachments;

import com.edunext.edutrack.api.upload.UnsupportedUploadTypeException;
import com.edunext.edutrack.api.upload.UploadKey;
import com.edunext.edutrack.api.upload.UploadPipeline;
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
import org.mockito.InOrder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-107 · the shared upload pipeline, without a database or a bucket.
 *
 * <p>What is asserted here is the <b>order</b> and the <b>visibility rule</b>,
 * because those are the two things that make the pipeline a security boundary
 * rather than a file copy. Everything else — that a PDF is a PDF, that EXIF goes
 * — belongs to C-025's own tests over the beans this class injects, and
 * re-asserting it here would only prove the mocks were configured.
 */
class ObAttachmentPipelineTest {

    private static final long CLIENT = 42L;
    private static final long UPLOADER = 3L;
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private ObAttachmentRepository attachments;
    private UploadPipeline uploads;
    private ObAttachmentScanTask scans;
    private ObAttachmentPipeline pipeline;

    @BeforeEach
    void setUp() {
        attachments = mock(ObAttachmentRepository.class);
        uploads = mock(UploadPipeline.class);
        scans = mock(ObAttachmentScanTask.class);

        pipeline = new ObAttachmentPipeline(attachments, uploads, scans,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(uploads.maxFileBytes()).thenReturn(10_000L);
        when(uploads.signedUrlTtl()).thenReturn(Duration.ofMinutes(5));
        when(uploads.vet(anyString(), any())).thenAnswer(call ->
                new UploadPipeline.Vetted("application/pdf", call.getArgument(1)));
        when(attachments.saveAndFlush(any())).thenAnswer(call -> {
            ObAttachment row = call.getArgument(0);
            row.setId(101L);
            return row;
        });
    }

    @Nested
    @DisplayName("storing")
    class Storing {

        /**
         * Each step exists to stop the next one from seeing something it should
         * not, so the sequence is the property rather than an implementation
         * detail. Reversing store and insert leaves a real hole, and it would
         * pass a test that only checked the saved row. (Sniff-before-strip is one
         * step behind the port — {@code UploadPipeline.vet} is deliberately not
         * separable, so no caller can store what it has not identified.)
         */
        @Test
        @DisplayName("caps, vets, stores, inserts, then queues the scan — in that order")
        void keepsThePipelineOrder() {
            pipeline.store(ObAttachmentOwner.CLIENT, CLIENT, ObAttachmentKind.SUBMISSION,
                    ObAttachmentPipeline.Uploader.staff(UPLOADER), "msa.pdf", bytes(64));

            InOrder order = inOrder(uploads, attachments, scans);
            order.verify(uploads).maxFileBytes();
            order.verify(uploads).vet(anyString(), any());
            order.verify(uploads).put(any(), any(), anyString());
            order.verify(attachments).saveAndFlush(any());
            order.verify(scans).submit(101L);
        }

        /**
         * The cap is checked before the bytes are examined. Refusing on size is
         * cheap and it bounds everything after it — a 200 MB file must not be
         * sniffed, stripped or stored to find that out.
         */
        @Test
        @DisplayName("refuses an oversized file before anything looks at the bytes")
        void refusesOversizedBeforeSniffing() {
            assertThatThrownBy(() -> pipeline.store(ObAttachmentOwner.CLIENT, CLIENT,
                    ObAttachmentKind.SUBMISSION, ObAttachmentPipeline.Uploader.staff(UPLOADER),
                    "huge.pdf", bytes(10_001)))
                    .isInstanceOf(ObAttachmentTooLargeException.class);

            verify(uploads, never()).vet(anyString(), any());
            verify(uploads, never()).put(any(), any(), anyString());
            verifyNoInteractions(attachments, scans);
        }

        /**
         * A rejected file must never occupy a key. Storing first and validating
         * afterwards would leave an object in the bucket for every refused
         * upload, unreferenced and unswept.
         */
        @Test
        @DisplayName("stores nothing when the type policy refuses the file")
        void storesNothingForARefusedType() {
            when(uploads.vet(anyString(), any()))
                    .thenThrow(new UnsupportedUploadTypeException("we do not accept .exe", null));

            assertThatThrownBy(() -> pipeline.store(ObAttachmentOwner.CLIENT, CLIENT,
                    ObAttachmentKind.SUBMISSION, ObAttachmentPipeline.Uploader.staff(UPLOADER),
                    "payload.exe", bytes(32)))
                    .isInstanceOf(UnsupportedUploadTypeException.class);

            verify(uploads, never()).put(any(), any(), anyString());
            verifyNoInteractions(attachments, scans);
        }

        @Test
        @DisplayName("writes the owner into its own column and nowhere else")
        void writesOneOwnerColumn() {
            ObAttachment saved = store();

            assertThat(saved.getObClientId()).isEqualTo(CLIENT);
            assertThat(saved.getStepId()).isNull();
            assertThat(saved.getSignoffId()).isNull();
            assertThat(saved.getPrereqTemplateTaskId()).isNull();
        }

        /**
         * The stored type is the sniffed one. It is served back on a presigned
         * GET, and serving back a caller-supplied {@code text/html} is stored XSS
         * against whoever opens the link.
         */
        @Test
        @DisplayName("stores the sniffed content type, never the declared one")
        void storesTheSniffedType() {
            assertThat(store().getContentType()).isEqualTo("application/pdf");

            ArgumentCaptor<String> served = ArgumentCaptor.forClass(String.class);
            verify(uploads).put(any(), any(), served.capture());
            assertThat(served.getValue()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("lands PENDING, so nothing is readable before the scan has run")
        void landsPending() {
            ObAttachment saved = store();

            assertThat(saved.getScanStatus()).isEqualTo(ObAttachmentScanStatus.PENDING);
            assertThat(saved.getScannedAt()).isNull();
            assertThat(pipeline.isReadable(saved)).isFalse();
        }

        /**
         * {@code ck_ob_attachments_uploader} refuses any other arrangement at the
         * column; the factories are what make it unconstructible in Java too.
         */
        @Test
        @DisplayName("sets exactly one uploader column, matching the portal side")
        void setsOneUploaderColumn() {
            ObAttachment saved = store();
            assertThat(saved.getUploadedByType()).isEqualTo(ObAttachmentUploaderType.STAFF);
            assertThat(saved.getUploadedByUser()).isEqualTo(UPLOADER);
            assertThat(saved.getUploadedByContact()).isNull();
        }

        /**
         * The hash is over the <b>stripped</b> bytes. Two uploads of one photo
         * from different phones match only after their EXIF is gone, which is the
         * case A-102 put the column there for.
         */
        @Test
        @DisplayName("hashes what was stored, not what arrived")
        void hashesTheStoredBytes() {
            when(uploads.vet(anyString(), any()))
                    .thenReturn(new UploadPipeline.Vetted("image/jpeg", new byte[]{1, 2, 3}));

            ObAttachment saved = pipeline.store(ObAttachmentOwner.CLIENT, CLIENT,
                    ObAttachmentKind.SUBMISSION, ObAttachmentPipeline.Uploader.staff(UPLOADER),
                    "photo.jpg", bytes(64));

            // SHA-256 of the three stripped bytes, not of the sixty-four sent.
            assertThat(saved.getContentSha256())
                    .isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
            assertThat(saved.getSizeBytes()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("readability")
    class Readability {

        @Test
        @DisplayName("signs a URL only for a CLEAN, untombstoned row")
        void signsOnlyClean() {
            when(uploads.signedDownloadUrl(any(), anyString(), anyString(), any()))
                    .thenReturn(URI.create("https://minio.local/signed"));

            ObAttachment row = stored(ObAttachmentScanStatus.CLEAN, null);
            assertThat(pipeline.signedUrlFor(row)).isPresent();
        }

        @Test
        @DisplayName("signs nothing for PENDING, INFECTED, FAILED or a tombstone")
        void refusesEverythingElse() {
            assertThat(pipeline.signedUrlFor(stored(ObAttachmentScanStatus.PENDING, null))).isEmpty();
            assertThat(pipeline.signedUrlFor(stored(ObAttachmentScanStatus.INFECTED, null))).isEmpty();
            assertThat(pipeline.signedUrlFor(stored(ObAttachmentScanStatus.FAILED, null))).isEmpty();
            assertThat(pipeline.signedUrlFor(stored(ObAttachmentScanStatus.CLEAN, NOW))).isEmpty();

            verify(uploads, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }

        /**
         * The cross-client read the owner check exists to stop. The key came out
         * of the database, and a row edited to name another client's object would
         * otherwise have that object signed and served.
         */
        @Test
        @DisplayName("signs nothing when the row's key names another client's object")
        void refusesAForeignKey() {
            ObAttachment row = stored(ObAttachmentScanStatus.CLEAN, null);
            row.setStorageKey(new ObAttachmentStorageKey(ObAttachmentOwner.CLIENT, 999,
                    java.util.UUID.randomUUID()).toString());

            assertThat(pipeline.signedUrlFor(row)).isEmpty();
            verify(uploads, never()).signedDownloadUrl(any(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("tombstoning")
    class Tombstoning {

        @Test
        @DisplayName("removes the object and stamps the row")
        void removesTheObjectAndStamps() {
            ObAttachment row = stored(ObAttachmentScanStatus.CLEAN, null);

            pipeline.tombstone(row, 9L);

            verify(uploads).delete(any(UploadKey.class));
            assertThat(row.getDeletedAt()).isEqualTo(NOW);
            assertThat(row.getDeletedBy()).isEqualTo(9L);
        }

        /**
         * A retried DELETE must not rewrite who removed a file. The stamp is the
         * record, and re-stamping it would quietly reassign the act.
         */
        @Test
        @DisplayName("does nothing to an already-tombstoned row")
        void isIdempotent() {
            Instant earlier = NOW.minus(Duration.ofHours(2));
            ObAttachment row = stored(ObAttachmentScanStatus.CLEAN, earlier);
            row.setDeletedBy(5L);

            pipeline.tombstone(row, 9L);

            assertThat(row.getDeletedAt()).isEqualTo(earlier);
            assertThat(row.getDeletedBy()).isEqualTo(5L);
            verify(uploads, never()).delete(any(UploadKey.class));
        }

        /**
         * A key this application cannot parse addresses no object it can reach,
         * so there is nothing to remove and the tombstone is still the right
         * outcome. Refusing would leave a row nobody can ever retire.
         */
        @Test
        @DisplayName("still tombstones a row whose key cannot be parsed")
        void tombstonesDespiteABadKey() {
            ObAttachment row = stored(ObAttachmentScanStatus.CLEAN, null);
            row.setStorageKey("../etc/passwd");

            pipeline.tombstone(row, 9L);

            assertThat(row.getDeletedAt()).isEqualTo(NOW);
            verify(uploads, never()).delete(any(UploadKey.class));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ObAttachment store() {
        return pipeline.store(ObAttachmentOwner.CLIENT, CLIENT, ObAttachmentKind.SUBMISSION,
                ObAttachmentPipeline.Uploader.staff(UPLOADER), "msa.pdf", bytes(64));
    }

    private static ObAttachment stored(ObAttachmentScanStatus status, Instant deletedAt) {
        ObAttachment row = new ObAttachment();
        row.setId(101L);
        row.setObClientId(CLIENT);
        row.setKind(ObAttachmentKind.SUBMISSION);
        row.setUploadedByType(ObAttachmentUploaderType.STAFF);
        row.setUploadedByUser(UPLOADER);
        row.setFileName("msa.pdf");
        row.setContentType("application/pdf");
        row.setSizeBytes(64);
        row.setStorageKey(new ObAttachmentStorageKey(ObAttachmentOwner.CLIENT, CLIENT,
                java.util.UUID.randomUUID()).toString());
        row.setScanStatus(status);
        row.setDeletedAt(deletedAt);
        if (deletedAt != null) {
            row.setDeletedBy(9L);
        }
        return row;
    }

    private static byte[] bytes(int length) {
        return new byte[length];
    }
}
