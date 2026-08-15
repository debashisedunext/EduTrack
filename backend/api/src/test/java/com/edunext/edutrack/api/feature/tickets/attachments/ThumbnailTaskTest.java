package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-026 · when a reduction is stored, and — mostly — when it is not.
 *
 * <p>{@link ThumbnailGeneratorTest} covers the pixels. What is asserted here is
 * the guard around them: that a thumbnail is written only for a row §4B.4 says
 * may be seen, that the object lands before the column that points at it, and
 * that nothing this class does can cost an attachment the verdict it already has.
 */
class ThumbnailTaskTest {

    private static final long ATTACHMENT = 9001L;
    private static final long TICKET = 347L;

    private final ThumbnailGenerator generator = mock(ThumbnailGenerator.class);
    private final AttachmentStorage storage = mock(AttachmentStorage.class);
    private final TicketAttachmentRepository attachments = mock(TicketAttachmentRepository.class);

    private TicketAttachment row;
    private ThumbnailTask task;

    /** A transaction manager that begins and commits nothing — see AttachmentScanTaskTest. */
    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }

    @BeforeEach
    void aCleanPngAttachment() {
        row = new TicketAttachment();
        row.setId(ATTACHMENT);
        row.setTicketId(TICKET);
        row.setFileName("screenshot.png");
        row.setStorageKey(AttachmentStorageKey.mint(TICKET).toString());
        row.setMimeType("image/png");
        row.setScanStatus("CLEAN");

        when(attachments.findById(ATTACHMENT)).thenReturn(Optional.of(row));
        when(generator.generate(any(), any())).thenReturn(Optional.of(new byte[]{1, 2, 3}));

        task = new ThumbnailTask(generator, storage, attachments, transactionManager());
    }

    @Nested
    @DisplayName("the happy path")
    class Stored {

        @Test
        void theObjectGoesToTheDerivedThumbnailKeyAsAPng() {
            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            ArgumentCaptor<AttachmentStorageKey> key = ArgumentCaptor.forClass(AttachmentStorageKey.class);
            verify(storage).put(key.capture(), eq(new byte[]{1, 2, 3}), eq("image/png"));

            assertThat(key.getValue().variant()).isEqualTo(AttachmentStorageKey.Variant.THUMBNAIL);
            assertThat(key.getValue().toString()).isEqualTo(row.getStorageKey() + "-thumb");
        }

        @Test
        void theColumnIsSetOnlyAfterTheObjectIsWritten() {
            // A crash between the two leaves an object nothing points at, which
            // is invisible litter. The reverse leaves a thumbnail_key pointing at
            // nothing — a broken image for everyone who opens the ticket.
            var order = org.mockito.Mockito.inOrder(storage, attachments);

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            order.verify(storage).put(any(), any(), any());
            order.verify(attachments).save(row);
            assertThat(row.getThumbnailKey()).isEqualTo(row.getStorageKey() + "-thumb");
        }

        @Test
        void theGeneratorIsGivenTheRowsSniffedTypeRatherThanAnythingFromTheRequest() {
            byte[] content = ThumbnailFixtures.png(800, 600);

            task.generateFor(ATTACHMENT, content);

            verify(generator).generate("image/png", content);
        }
    }

    @Nested
    @DisplayName("nothing is stored")
    class Skipped {

        @Test
        void whenTheGeneratorDeclines() {
            // A PDF, a WebP, or an image already small enough. All ordinary.
            when(generator.generate(any(), any())).thenReturn(Optional.empty());

            task.generateFor(ATTACHMENT, AttachmentFixtures.pdf());

            verify(storage, never()).put(any(), any(), any());
            verify(attachments, never()).save(any());
            assertThat(row.getThumbnailKey()).isNull();
        }

        @Test
        void whenTheRowIsNotCleanAfterAll() {
            // Re-read inside this transaction rather than trusted from the
            // caller: a thumbnail is the file on screen, so §4B.4's visibility
            // rule has to hold here too and not only at the scan.
            row.setScanStatus("PENDING");

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            verify(generator, never()).generate(any(), any());
            verify(storage, never()).put(any(), any(), any());
        }

        @Test
        void whenTheRowWasDeletedWhileTheScanWasRunning() {
            // C-028's window is fifteen minutes and this takes milliseconds, but
            // the loser of that race would be an orphaned object on a ticket the
            // user believes they cleared.
            row.setDeleted(true);

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            verify(storage, never()).put(any(), any(), any());
        }

        @Test
        void whenTheRowIsGoneEntirely() {
            when(attachments.findById(ATTACHMENT)).thenReturn(Optional.empty());

            assertThatCode(() -> task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600)))
                    .doesNotThrowAnyException();
            verify(storage, never()).put(any(), any(), any());
        }

        @Test
        void whenOneHasAlreadyBeenBuilt() {
            // Idempotent, so a re-queued scan does not rewrite every object it
            // touches — and does not leave a second orphan behind if the key
            // derivation ever changes.
            row.setThumbnailKey(row.getStorageKey() + "-thumb");

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            verify(generator, never()).generate(any(), any());
            verify(storage, never()).put(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("a failure here is cosmetic and must stay that way")
    class Failures {

        @Test
        void aStorageFailureDoesNotEscapeToTheCaller() {
            // The caller is AttachmentScanTask, whose own catch would report this
            // as a *scan* failure — wrong, and alarming.
            doThrow(new IllegalStateException("MinIO is down")).when(storage).put(any(), any(), any());

            assertThatCode(() -> task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600)))
                    .doesNotThrowAnyException();
        }

        @Test
        void aFailedStoreLeavesTheColumnNullRatherThanNamingAnObjectThatIsNotThere() {
            doThrow(new IllegalStateException("MinIO is down")).when(storage).put(any(), any(), any());

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            assertThat(row.getThumbnailKey()).isNull();
            verify(attachments, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the derived key")
    class Key {

        @Test
        void sharesTheOriginalsUuidSoNothingHasToStoreASecondOne() {
            AttachmentStorageKey original = AttachmentStorageKey.parse(row.getStorageKey());

            task.generateFor(ATTACHMENT, ThumbnailFixtures.png(800, 600));

            AttachmentStorageKey thumbnail = AttachmentStorageKey.parse(row.getThumbnailKey());
            assertThat(thumbnail.objectId()).isEqualTo(original.objectId());
            assertThat(thumbnail.ticketId()).isEqualTo(TICKET);
        }
    }
}
