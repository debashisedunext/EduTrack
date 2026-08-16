package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-025 · the verdict path — and specifically what happens when there is no
 * verdict.
 *
 * <p>The scanner client has its own tests; what is asserted here is the
 * consequence each verdict has for the row and for the stored object.
 */
class AttachmentScanTaskTest {

    private static final long ATTACHMENT = 9001L;

    private final AttachmentScanner scanner = mock(AttachmentScanner.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final TicketAttachmentRepository attachments = mock(TicketAttachmentRepository.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final ThumbnailTask thumbnails = mock(ThumbnailTask.class);

    /**
     * A transaction manager that begins and commits nothing. The class under
     * test only needs a boundary to exist; asserting that JPA commits is
     * Hibernate's job, not this test's.
     *
     * <p>Held as a field rather than built per call so C-026's ordering test can
     * verify {@code commit} happened before the thumbnail was attempted — which
     * is the actual guarantee, and is not observable any other way.
     */
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private TicketAttachment row;

    private AttachmentScanTask taskWith(boolean failOpen) {
        AttachmentProperties properties = new AttachmentProperties(
                Duration.ofMinutes(5), 10L * 1024 * 1024, 50L * 1024 * 1024, 20, Duration.ofMinutes(15),
                new AttachmentProperties.Scan(true, "localhost", 3310, Duration.ofSeconds(30), failOpen),
                new AttachmentProperties.Thumbnail(true, 320, 50_000_000L));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AttachmentScanTask(
                scanner, storage, attachments, executor, properties, thumbnails, transactions);
    }

    @BeforeEach
    void aPendingAttachmentWithAStoredObject() {
        row = new TicketAttachment();
        row.setId(ATTACHMENT);
        row.setTicketId(347L);
        row.setFileName("signoff.pdf");
        row.setStorageKey(AttachmentStorageKey.mint(347).toString());
        row.setMimeType("application/pdf");
        row.setScanStatus("PENDING");

        when(attachments.findById(ATTACHMENT)).thenReturn(Optional.of(row));
        when(storage.read(any())).thenReturn(Optional.of(AttachmentFixtures.pdf()));
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        void cleanSealsTheRowAndLeavesTheObjectAlone() {
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.CLEAN);

            taskWith(false).scanNow(ATTACHMENT);

            assertThat(row.getScanStatus()).isEqualTo("CLEAN");
            verify(attachments).save(row);
            verify(storage, never()).delete(any());
        }

        @Test
        void infectedDeletesTheObjectButKeepsTheRow() {
            // The tombstone is the point: the History timeline still says a file
            // was attached and removed, and a second upload of the same file is
            // recognisable rather than looking like a first.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.INFECTED);

            taskWith(false).scanNow(ATTACHMENT);

            assertThat(row.getScanStatus()).isEqualTo("INFECTED");
            verify(storage).delete(any());
            verify(attachments).save(row);
        }
    }

    @Nested
    @DisplayName("no verdict fails closed — the default that matters most")
    class FailsClosed {

        @Test
        void unknownLeavesTheRowPendingAndTheFileUnreadable() {
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.UNKNOWN);

            taskWith(false).scanNow(ATTACHMENT);

            assertThat(row.getScanStatus()).isEqualTo("PENDING");
            verify(attachments, never()).save(any());
            verify(storage, never()).delete(any());
        }

        @Test
        void aMissingStoredObjectLeavesTheRowPendingRatherThanSealingIt() {
            when(storage.read(any())).thenReturn(Optional.empty());

            taskWith(false).scanNow(ATTACHMENT);

            assertThat(row.getScanStatus()).isEqualTo("PENDING");
            verify(scanner, never()).scan(anyString(), any());
            verify(attachments, never()).save(any());
        }

        @Test
        void failOpenIsTheOnlyThingThatTurnsUnknownIntoClean() {
            // Local development only. AttachmentScanConfig refuses to start with
            // this set anywhere else, which is what keeps the exception from
            // becoming the rule.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.UNKNOWN);

            taskWith(true).scanNow(ATTACHMENT);

            assertThat(row.getScanStatus()).isEqualTo("CLEAN");
        }
    }

    @Nested
    @DisplayName("a row that has already moved on is left alone")
    class Idempotence {

        @Test
        void anAlreadySealedRowIsNotRescanned() {
            row.setScanStatus("CLEAN");
            taskWith(false).scanNow(ATTACHMENT);

            verify(scanner, never()).scan(anyString(), any());
            verify(attachments, never()).save(any());
        }

        @Test
        void aRowDeletedBeforeTheScanRanIsNotResurrected() {
            when(attachments.findById(ATTACHMENT)).thenReturn(Optional.empty());
            taskWith(false).scanNow(ATTACHMENT);

            verify(scanner, never()).scan(anyString(), any());
        }
    }

    @Nested
    @DisplayName("C-026 · a thumbnail is built only where §4B.4 allows the file to be seen")
    class Thumbnails {

        @Test
        void aCleanVerdictHandsTheStoredBytesStraightToTheThumbnailTask() {
            // The bytes rather than the id, and the bytes we already read for the
            // scanner — a second object GET per upload to build a preview would
            // double this feature's storage traffic for nothing.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.CLEAN);
            byte[] stored = AttachmentFixtures.pdf();
            when(storage.read(any())).thenReturn(Optional.of(stored));

            taskWith(false).scanNow(ATTACHMENT);

            verify(thumbnails).generateFor(ATTACHMENT, stored);
        }

        @Test
        void anInfectedVerdictBuildsNothing() {
            // A thumbnail is the file on screen. Reducing an infected upload
            // would mean running an image decoder over bytes clamd has just
            // condemned, and then serving the result.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.INFECTED);

            taskWith(false).scanNow(ATTACHMENT);

            verify(thumbnails, never()).generateFor(anyLong(), any());
        }

        @Test
        void noVerdictBuildsNothing() {
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.UNKNOWN);

            taskWith(false).scanNow(ATTACHMENT);

            verify(thumbnails, never()).generateFor(anyLong(), any());
        }

        @Test
        void aRowThatWasAlreadySealedBuildsNothing() {
            // Guards against a re-queued scan quietly re-reducing every
            // attachment it touches.
            row.setScanStatus("CLEAN");

            taskWith(false).scanNow(ATTACHMENT);

            verify(thumbnails, never()).generateFor(anyLong(), any());
        }

        @Test
        void failOpenReachesTheThumbnailPathToo() {
            // Local development is the only place this runs, and it is where
            // every screenshot in the product is first looked at — a gallery that
            // silently had no thumbnails on a laptop would read as broken.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.UNKNOWN);

            taskWith(true).scanNow(ATTACHMENT);

            verify(thumbnails).generateFor(eq(ATTACHMENT), any());
        }

        @Test
        void theVerdictIsCommittedBeforeAThumbnailIsEvenAttempted() {
            // This is the whole reason the two are separate transactions, and it
            // is asserted as an ordering rather than as a swallowed exception on
            // purpose. What protects the verdict is not a catch block — it is
            // that the CLEAN has already been committed by the time any decoder
            // runs. Were the thumbnail moved inside `resolve`, a malformed image
            // could roll the verdict back to PENDING and leave a perfectly good
            // file permanently unreadable because its *preview* failed —
            // intermittently, since it would depend on the image.
            //
            // Verifying `commit` and not merely `save` is what makes this test
            // able to see that difference: a version that generated inside the
            // transaction would still save first and would still pass a
            // save-then-generate assertion.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.CLEAN);

            InOrder order = inOrder(attachments, transactions, thumbnails);
            taskWith(false).scanNow(ATTACHMENT);

            order.verify(attachments).save(row);
            order.verify(transactions).commit(any());
            order.verify(thumbnails).generateFor(eq(ATTACHMENT), any());
        }

        @Test
        void aThumbnailFailureLeavesTheSealedVerdictStanding() {
            // ThumbnailTask swallows its own failures — ThumbnailTaskTest pins
            // that — so this asserts the belt rather than the braces: even for a
            // collaborator that breaks its contract and throws, the row keeps the
            // CLEAN it was given, because that transaction closed first.
            when(scanner.scan(anyString(), any())).thenReturn(AttachmentScanner.Verdict.CLEAN);
            doThrow(new IllegalStateException("decoder exploded"))
                    .when(thumbnails).generateFor(anyLong(), any());

            assertThatThrownBy(() -> taskWith(false).scanNow(ATTACHMENT))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(row.getScanStatus()).isEqualTo("CLEAN");
            verify(attachments).save(row);
            verify(transactions).commit(any());
        }
    }
}
