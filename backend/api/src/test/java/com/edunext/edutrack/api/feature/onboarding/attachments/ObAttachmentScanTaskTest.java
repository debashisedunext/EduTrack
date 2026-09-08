package com.edunext.edutrack.api.feature.onboarding.attachments;

import com.edunext.edutrack.api.upload.UploadKey;
import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-107 · the verdict path for an onboarding file — and specifically what
 * happens when there is no verdict.
 *
 * <p>The scanner itself is C-025's bean and has its own tests; this asserts the
 * consequence each verdict has for the row, for the stored object, and for
 * {@code scanned_at} — the column {@code ticket_attachments} does not have and
 * that this task is the only writer of.
 */
class ObAttachmentScanTaskTest {

    private static final long ATTACHMENT = 9001L;
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private final UploadPipeline uploads = mock(UploadPipeline.class);
    private final ObAttachmentRepository attachments = mock(ObAttachmentRepository.class);
    private final ExecutorService executor = mock(ExecutorService.class);

    /**
     * A transaction manager that begins and commits nothing. The class under
     * test only needs a boundary to exist; asserting that JPA commits is
     * Hibernate's job, not this test's.
     */
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private ObAttachment row;

    @BeforeEach
    void setUp() {
        row = new ObAttachment();
        row.setId(ATTACHMENT);
        row.setObClientId(42L);
        row.setKind(ObAttachmentKind.SUBMISSION);
        row.setUploadedByType(ObAttachmentUploaderType.STAFF);
        row.setUploadedByUser(3L);
        row.setFileName("msa.pdf");
        row.setContentType("application/pdf");
        row.setSizeBytes(64);
        row.setStorageKey(new ObAttachmentStorageKey(ObAttachmentOwner.CLIENT, 42,
                UUID.randomUUID()).toString());
        row.setScanStatus(ObAttachmentScanStatus.PENDING);

        when(attachments.findById(ATTACHMENT)).thenReturn(Optional.of(row));
        when(uploads.read(any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("a CLEAN verdict seals the status and stamps when it was reached")
    void cleanSealsAndStamps() {
        when(uploads.scan(anyString(), any())).thenReturn(UploadPipeline.Verdict.CLEAN);

        taskWith(false).scanNow(ATTACHMENT);

        assertThat(row.getScanStatus()).isEqualTo(ObAttachmentScanStatus.CLEAN);
        assertThat(row.getScannedAt()).isEqualTo(NOW);
        verify(uploads, never()).delete(any(UploadKey.class));
    }

    /**
     * The object goes immediately and the row stays. A-102's own comment: a file
     * referenced by a sign-off or a completed step is evidence, and the row has
     * to keep resolving.
     */
    @Test
    @DisplayName("an INFECTED verdict deletes the object and keeps the row")
    void infectedDeletesTheObject() {
        when(uploads.scan(anyString(), any())).thenReturn(UploadPipeline.Verdict.INFECTED);

        taskWith(false).scanNow(ATTACHMENT);

        assertThat(row.getScanStatus()).isEqualTo(ObAttachmentScanStatus.INFECTED);
        assertThat(row.getScannedAt()).isEqualTo(NOW);
        verify(uploads).delete(any(UploadKey.class));
    }

    /**
     * <b>The single most important case in this class.</b> Every outage, timeout
     * and misconfiguration arrives as UNKNOWN, and widening it to CLEAN would
     * serve every upload unscanned for the whole duration with nothing about the
     * product looking different while it happened.
     *
     * <p>The null {@code scannedAt} is the other half: "never looked at" and
     * "looked at and inconclusive" have to stay distinguishable, which is the
     * question an operator asks after the outage.
     */
    @Test
    @DisplayName("no verdict leaves the row PENDING, unstamped and unreadable")
    void unknownStaysPending() {
        when(uploads.scan(anyString(), any())).thenReturn(UploadPipeline.Verdict.UNKNOWN);

        taskWith(false).scanNow(ATTACHMENT);

        assertThat(row.getScanStatus()).isEqualTo(ObAttachmentScanStatus.PENDING);
        assertThat(row.getScannedAt()).isNull();
        verify(uploads, never()).delete(any(UploadKey.class));
    }

    /**
     * The local-development escape hatch, which {@code AttachmentScanConfig}
     * refuses to let out of local development. Asserted so that the switch is
     * known to work rather than assumed — a fail-open that silently did nothing
     * would leave a developer thinking their scanner was running.
     */
    @Test
    @DisplayName("fail-open marks it CLEAN, and only when explicitly configured")
    void failOpenMarksClean() {
        when(uploads.scan(anyString(), any())).thenReturn(UploadPipeline.Verdict.UNKNOWN);

        taskWith(true).scanNow(ATTACHMENT);

        assertThat(row.getScanStatus()).isEqualTo(ObAttachmentScanStatus.CLEAN);
        assertThat(row.getScannedAt()).isEqualTo(NOW);
    }

    /**
     * A leak of storage, never of safety. The bytes are missing, so there is
     * nothing to form a verdict about and PENDING is the honest state.
     */
    @Test
    @DisplayName("leaves the row PENDING when the stored object has gone")
    void missingObjectStaysPending() {
        when(uploads.read(any())).thenReturn(Optional.empty());

        taskWith(false).scanNow(ATTACHMENT);

        assertThat(row.getScanStatus()).isEqualTo(ObAttachmentScanStatus.PENDING);
        verify(uploads, never()).scan(anyString(), any());
    }

    /**
     * A second sweep must not re-scan or re-stamp a row somebody already
     * settled — including one an operator marked INFECTED by hand.
     */
    @Test
    @DisplayName("does nothing to a row that is no longer PENDING")
    void ignoresSettledRows() {
        row.setScanStatus(ObAttachmentScanStatus.CLEAN);
        Instant sealedEarlier = NOW.minus(Duration.ofHours(3));
        row.setScannedAt(sealedEarlier);

        taskWith(false).scanNow(ATTACHMENT);

        assertThat(row.getScannedAt()).isEqualTo(sealedEarlier);
        verify(uploads, never()).scan(anyString(), any());
    }

    private ObAttachmentScanTask taskWith(boolean failOpen) {
        when(uploads.scanFailOpen()).thenReturn(failOpen);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new ObAttachmentScanTask(uploads, attachments, executor, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
