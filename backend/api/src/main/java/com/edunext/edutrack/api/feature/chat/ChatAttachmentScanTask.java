package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentScanner;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.domain.chat.ChatAttachment;
import com.edunext.edutrack.domain.chat.ChatAttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutorService;

/**
 * D-053 · the AV verdict for a file shared into a chat thread.
 *
 * <h2>What is shared with C-025, and what is not</h2>
 *
 * <p><b>The verdict is not reimplemented.</b> {@link AttachmentScanner} — the
 * bean that actually decides whether bytes are safe — is injected, so chat and
 * tickets get the same answer from the same clamd, with the same
 * {@code UNKNOWN}-is-not-{@code CLEAN} rule and the same {@code failOpen}
 * switch that {@code AttachmentScanConfig} refuses to let out of local
 * development. That was D-053's whole worry: *"a second pipeline here would put
 * two answers next to each other for 'is this file safe'"*. There is one
 * answer.
 *
 * <p>What <em>is</em> written twice is the row plumbing — read a
 * {@code chat_attachments} row, seal its status — because
 * {@code AttachmentScanTask} is bound to {@code TicketAttachmentRepository} and
 * a chat file has none of a ticket file's columns. Making that class generic
 * over two repositories would be a larger and riskier change to C-025's code
 * than writing forty lines here.
 *
 * <p>Every non-obvious decision below is C-025's and is copied deliberately,
 * with its reason:
 *
 * <ul>
 *   <li><b>The id is queued, not the row or the bytes.</b> A detached entity
 *       and up to 10 MB per queued task; a hundred deep, that is a gigabyte of
 *       heap held hostage to a slow scanner.</li>
 *   <li><b>Deferred to after commit.</b> The upload runs in a transaction, so a
 *       scan thread starting now cannot see the row, finds nothing to do, and
 *       leaves the file PENDING for ever — a race won often enough to look
 *       intermittent.</li>
 *   <li><b>A {@code TransactionTemplate}, not {@code @Transactional}.</b> The
 *       executor's lambda calls through {@code this}, not the Spring proxy, so
 *       the annotation would silently never apply.</li>
 *   <li><b>PENDING survives a crash, and that is correct.</b> A leak of
 *       storage, never of safety.</li>
 * </ul>
 *
 * <p>No thumbnail branch: C-026's reduction is a ticket-gallery feature, and
 * an image in a chat thread is rendered from the original. If chat ever wants
 * one, this is where it goes and {@code ThumbnailTask} is the bean to call.
 */
@Component
class ChatAttachmentScanTask {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentScanTask.class);

    static final String PENDING = "PENDING";
    static final String CLEAN = "CLEAN";
    static final String INFECTED = "INFECTED";

    private final AttachmentScanner scanner;
    private final AttachmentStorage storage;
    private final ChatAttachmentRepository attachments;
    private final ExecutorService executor;
    private final AttachmentProperties properties;
    private final TransactionTemplate transaction;

    ChatAttachmentScanTask(AttachmentScanner scanner,
                           AttachmentStorage storage,
                           ChatAttachmentRepository attachments,
                           ExecutorService attachmentScanExecutor,
                           AttachmentProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.scanner = scanner;
        this.storage = storage;
        this.attachments = attachments;
        this.executor = attachmentScanExecutor;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void submit(long attachmentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(attachmentId);
                }
            });
            return;
        }
        enqueue(attachmentId);
    }

    private void enqueue(long attachmentId) {
        executor.execute(() -> {
            try {
                scanNow(attachmentId);
            } catch (RuntimeException failed) {
                log.error("chat attachment {} could not be scanned; it stays PENDING and unreadable",
                        attachmentId, failed);
            }
        });
    }

    /** Package-private and directly callable, so a test can drive the verdict on its own thread. */
    void scanNow(long attachmentId) {
        transaction.executeWithoutResult(status -> resolve(attachmentId));
    }

    private void resolve(long attachmentId) {
        ChatAttachment attachment = attachments.findById(attachmentId).orElse(null);
        if (attachment == null || !PENDING.equals(attachment.getScanStatus())) {
            return;
        }

        ChatAttachmentStorageKey key = ChatAttachmentStorageKey.parse(attachment.getStorageKey());
        byte[] content = storage.read(key).orElse(null);
        if (content == null) {
            log.warn("chat attachment {} has no stored object at {}; leaving it PENDING", attachmentId, key);
            return;
        }

        switch (scanner.scan(attachment.getFileName(), content)) {
            case CLEAN -> attachment.setScanStatus(CLEAN);
            case INFECTED -> {
                // The object goes immediately; the row stays, so §7.6's record
                // that a file was shared and removed survives — the same reason
                // a deleted message keeps its row.
                storage.delete(key);
                attachment.setScanStatus(INFECTED);
                log.warn("chat attachment {} ({}) was infected; the stored object has been deleted",
                        attachmentId, attachment.getFileName());
            }
            case UNKNOWN -> {
                if (properties.scan().failOpen()) {
                    log.warn("no scan verdict for chat attachment {}; fail-open is set, marking it CLEAN",
                            attachmentId);
                    attachment.setScanStatus(CLEAN);
                    return;
                }
                log.warn("no scan verdict for chat attachment {}; it stays PENDING and is not downloadable",
                        attachmentId);
            }
        }
    }
}
