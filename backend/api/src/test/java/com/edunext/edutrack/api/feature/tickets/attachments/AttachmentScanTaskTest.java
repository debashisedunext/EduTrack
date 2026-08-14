package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private TicketAttachment row;

    private AttachmentScanTask taskWith(boolean failOpen) {
        AttachmentProperties properties = new AttachmentProperties(
                Duration.ofMinutes(5), 10L * 1024 * 1024, 50L * 1024 * 1024, 20,
                new AttachmentProperties.Scan(true, "localhost", 3310, Duration.ofSeconds(30), failOpen));
        return new AttachmentScanTask(scanner, storage, attachments, executor, properties, transactionManager());
    }

    /**
     * A transaction manager that begins and commits nothing. The class under
     * test only needs a boundary to exist; asserting that JPA commits is
     * Hibernate's job, not this test's.
     */
    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
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
}
