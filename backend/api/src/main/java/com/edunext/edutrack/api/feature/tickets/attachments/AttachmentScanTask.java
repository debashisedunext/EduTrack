package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
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
 * C-025 · the half of the upload that happens after the response — blueprint
 * §4B.4's "anti-virus scan <b>before the file becomes visible</b>".
 *
 * <p>The contract's {@code uploadAttachment} answers 201 with
 * {@code scanStatus: PENDING}, which is the honest description of what has
 * happened: the bytes are stored privately, nothing can read them, and a verdict
 * is pending. Making the caller wait for clamd instead would put a multi-second
 * third-party round trip inside a request the user is watching, and would tie the
 * upload's success to the scanner's availability — a scanner outage would then
 * refuse uploads outright rather than deferring them.
 *
 * <h2>PENDING is the only state that survives a crash, and that is correct</h2>
 *
 * <p>If this process dies between the insert and the verdict, the row stays
 * PENDING and the file stays unreadable for ever. That is a leak of storage, not
 * of safety, and the table already carries {@code ix_attachments_scan} and
 * {@link TicketAttachmentRepository#findByScanStatus} for the sweeper that
 * reclaims them — the migration that created the index describes it as "the AV
 * worker's work queue". Building that sweeper means touching {@code worker/},
 * which is Stream D's, so it is flagged rather than written here.
 */
@Component
class AttachmentScanTask {

    private static final Logger log = LoggerFactory.getLogger(AttachmentScanTask.class);

    static final String PENDING = "PENDING";
    static final String CLEAN = "CLEAN";
    static final String INFECTED = "INFECTED";

    private final AttachmentScanner scanner;
    private final AttachmentStorage storage;
    private final TicketAttachmentRepository attachments;
    private final ExecutorService executor;
    private final AttachmentProperties properties;
    private final TransactionTemplate transaction;

    AttachmentScanTask(AttachmentScanner scanner,
                       AttachmentStorage storage,
                       TicketAttachmentRepository attachments,
                       ExecutorService attachmentScanExecutor,
                       AttachmentProperties properties,
                       PlatformTransactionManager transactionManager) {
        this.scanner = scanner;
        this.storage = storage;
        this.attachments = attachments;
        this.executor = attachmentScanExecutor;
        this.properties = properties;

        // A TransactionTemplate rather than @Transactional on scanNow, and the
        // reason is the bug it avoids: the executor's lambda calls scanNow on
        // `this`, not through the Spring proxy, so the annotation would never be
        // applied. Nothing would fail — the read would open its own connection,
        // the save would autocommit, and it would look like it worked, right up
        // to the first time two verdicts needed to land together. Making the
        // boundary explicit puts it where it cannot be bypassed by a call site.
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Queue a scan for a row that has just been inserted.
     *
     * <p>The attachment <b>id</b> is queued, not the row and not the bytes. The
     * entity would be detached the moment the request's persistence context
     * closed, and the bytes would pin up to 10 MB per queued task — with a queue
     * a hundred deep, that is a gigabyte of heap held hostage to a slow scanner.
     * The task re-reads both, which costs one indexed select and one object GET
     * and bounds the memory at the pool's width instead of at the queue's depth.
     */
    void submit(long attachmentId) {
        // Deferred to after commit, and this is the subtle part. The upload runs
        // in a transaction, so at the moment `submit` is called the row exists
        // only inside it — flushed, but not committed. A scan thread starting
        // now opens its own transaction, cannot see the row, finds nothing to do
        // and returns. The attachment would then stay PENDING for ever with no
        // error anywhere, and the race is won by the scanner exactly often
        // enough to look intermittent.
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
                // Nothing above this catches: the executor would swallow it and
                // the row would sit PENDING with no trace of why.
                log.error("attachment {} could not be scanned; it stays PENDING and unreadable",
                        attachmentId, failed);
            }
        });
    }

    /**
     * Scan one attachment and seal its verdict.
     *
     * <p>Package-private and directly callable, so a test can drive the whole
     * verdict path on the calling thread without an executor in the way.
     */
    void scanNow(long attachmentId) {
        transaction.executeWithoutResult(status -> resolve(attachmentId));
    }

    private void resolve(long attachmentId) {
        TicketAttachment attachment = attachments.findById(attachmentId).orElse(null);
        if (attachment == null || !PENDING.equals(attachment.getScanStatus())) {
            // Already resolved, or deleted inside C-028's window before the scan
            // got to it. Either way there is nothing to decide.
            return;
        }

        AttachmentStorageKey key = AttachmentStorageKey.parse(attachment.getStorageKey());
        byte[] content = storage.read(key).orElse(null);
        if (content == null) {
            log.warn("attachment {} has no stored object at {}; leaving it PENDING", attachmentId, key);
            return;
        }

        AttachmentScanner.Verdict verdict = scanner.scan(attachment.getFileName(), content);
        switch (verdict) {
            case CLEAN -> seal(attachment, CLEAN);
            case INFECTED -> {
                // The object goes immediately. The row stays, so the History
                // timeline can still say a file was attached and removed — and
                // so a second upload of the same file is recognisable rather
                // than looking like a first.
                storage.delete(key);
                seal(attachment, INFECTED);
                log.warn("attachment {} ({}) was infected; the stored object has been deleted",
                        attachmentId, attachment.getFileName());
            }
            case UNKNOWN -> {
                if (properties.scan().failOpen()) {
                    // Local development only — AttachmentScanConfig refuses to
                    // start with this set anywhere else.
                    log.warn("no scan verdict for attachment {}; fail-open is set, marking it CLEAN",
                            attachmentId);
                    seal(attachment, CLEAN);
                    return;
                }
                log.warn("no scan verdict for attachment {}; it stays PENDING and is not downloadable",
                        attachmentId);
            }
        }
    }

    private void seal(TicketAttachment attachment, String status) {
        attachment.setScanStatus(status);
        attachments.save(attachment);
    }
}
