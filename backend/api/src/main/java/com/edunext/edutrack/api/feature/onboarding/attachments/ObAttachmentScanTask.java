package com.edunext.edutrack.api.feature.onboarding.attachments;

import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

/**
 * B-107 · the AV verdict for a file uploaded anywhere in the onboarding module.
 *
 * <h2>What is shared with C-025, and what is not</h2>
 *
 * <p><b>The verdict is not reimplemented.</b> {@link UploadPipeline#scan} reaches
 * the one bean that actually decides whether bytes are safe, so onboarding, chat
 * and tickets get the same answer from the same clamd, with the same
 * {@code UNKNOWN}-is-not-{@code CLEAN} rule and the same {@code failOpen} switch
 * that C-025's configuration refuses to let out of local development.
 * D-053 stated the worry this answers — a second pipeline putting two answers
 * next to each other for "is this file safe" — and it is a worry about the
 * verdict, not about the row plumbing.
 *
 * <p>What <em>is</em> written a third time is the plumbing: read an
 * {@code ob_attachments} row, seal its status. {@code AttachmentScanTask} is
 * bound to {@code TicketAttachmentRepository} and an onboarding file has none of
 * a ticket file's columns; making that class generic over three repositories
 * would be a larger and riskier change to another stream's code than these forty
 * lines.
 *
 * <p>Every non-obvious decision below is C-025's and is copied deliberately,
 * with its reason:
 *
 * <ul>
 *   <li><b>The id is queued, not the row or the bytes.</b> A detached entity and
 *       up to 10 MB per queued task; a hundred deep, that is a gigabyte of heap
 *       held hostage to a slow scanner.</li>
 *   <li><b>Deferred to after commit.</b> The upload runs in a transaction, so a
 *       scan thread starting now cannot see the row, finds nothing to do, and
 *       leaves the file PENDING for ever — a race won often enough to look
 *       intermittent.</li>
 *   <li><b>A {@code TransactionTemplate}, not {@code @Transactional}.</b> The
 *       executor's lambda calls through {@code this}, not the Spring proxy, so
 *       the annotation would silently never apply.</li>
 *   <li><b>PENDING survives a crash, and that is correct.</b> A leak of storage,
 *       never of safety.</li>
 * </ul>
 *
 * <h2>{@code scanned_at} is written here and nowhere else</h2>
 *
 * <p>A-102 gave this table a {@code scanned_at} that {@code ticket_attachments}
 * has no column for, and it is stamped only alongside a settled verdict. A row
 * that stays PENDING keeps a null stamp, so "never looked at" and "looked at and
 * inconclusive" stay distinguishable — which is the question an operator asks
 * after a scanner outage, and the one a status column alone cannot answer.
 */
@Component
public class ObAttachmentScanTask {

    private static final Logger log = LoggerFactory.getLogger(ObAttachmentScanTask.class);

    private final UploadPipeline uploads;
    private final ObAttachmentRepository attachments;
    private final ExecutorService executor;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** {@code @Autowired} for {@code ObAttachmentPipeline}'s reason: two constructors, one context. */
    @Autowired
    ObAttachmentScanTask(UploadPipeline uploads,
                         ObAttachmentRepository attachments,
                         ExecutorService attachmentScanExecutor,
                         PlatformTransactionManager transactionManager) {
        this(uploads, attachments, attachmentScanExecutor, transactionManager, Clock.systemUTC());
    }

    /** Test seam — a fixed clock, so an assertion on {@code scannedAt} is not an assertion on now. */
    ObAttachmentScanTask(UploadPipeline uploads,
                         ObAttachmentRepository attachments,
                         ExecutorService attachmentScanExecutor,
                         PlatformTransactionManager transactionManager,
                         Clock clock) {
        this.uploads = uploads;
        this.attachments = attachments;
        this.executor = attachmentScanExecutor;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
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
                log.error("onboarding attachment {} could not be scanned; it stays PENDING "
                        + "and unreadable", attachmentId, failed);
            }
        });
    }

    /** Package-private and directly callable, so a test can drive the verdict on its own thread. */
    void scanNow(long attachmentId) {
        transaction.executeWithoutResult(status -> resolve(attachmentId));
    }

    private void resolve(long attachmentId) {
        ObAttachment attachment = attachments.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.getScanStatus() != ObAttachmentScanStatus.PENDING) {
            return;
        }

        ObAttachmentStorageKey key = ObAttachmentStorageKey.parse(attachment.getStorageKey());
        byte[] content = uploads.read(key).orElse(null);
        if (content == null) {
            log.warn("onboarding attachment {} has no stored object at {}; leaving it PENDING",
                    attachmentId, key);
            return;
        }

        switch (uploads.scan(attachment.getFileName(), content)) {
            case CLEAN -> seal(attachment, ObAttachmentScanStatus.CLEAN);
            case INFECTED -> {
                // The object goes immediately; the row stays, so the record that
                // a file was uploaded and removed survives — A-102's own comment
                // on why removal here is a tombstone rather than a DELETE.
                uploads.delete(key);
                seal(attachment, ObAttachmentScanStatus.INFECTED);
                log.warn("onboarding attachment {} ({}) was infected; the stored object has been "
                        + "deleted", attachmentId, attachment.getFileName());
            }
            case UNKNOWN -> {
                if (uploads.scanFailOpen()) {
                    log.warn("no scan verdict for onboarding attachment {}; fail-open is set, "
                            + "marking it CLEAN", attachmentId);
                    seal(attachment, ObAttachmentScanStatus.CLEAN);
                    return;
                }
                log.warn("no scan verdict for onboarding attachment {}; it stays PENDING and is "
                        + "not downloadable", attachmentId);
            }
        }
    }

    /** A verdict and its stamp move together, never one without the other. */
    private void seal(ObAttachment attachment, ObAttachmentScanStatus verdict) {
        attachment.setScanStatus(verdict);
        attachment.setScannedAt(clock.instant());
    }
}
