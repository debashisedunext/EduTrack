package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * C-026 · storing the reduction, once the file is allowed to be seen.
 *
 * <p>Runs on the scan pool, immediately after {@link AttachmentScanTask} has
 * sealed a CLEAN verdict, on the same thread and with the same bytes already in
 * hand. There is no second executor and no second read of the object.
 *
 * <h2>After the verdict, and in its own transaction</h2>
 *
 * <p>This is the ordering that matters and it is not a style preference. Two
 * separate rules force it apart from the scan:
 *
 * <ul>
 *   <li><b>A thumbnail must never cost an attachment its verdict.</b> Sharing the
 *       scan's transaction would mean a decoder that threw — on a truncated GIF,
 *       an exotic colour profile, anything — rolling the CLEAN back to PENDING.
 *       The file would then be permanently unreadable because its <em>preview</em>
 *       failed, which is an absurd trade and an intermittent one, since it would
 *       depend on the image. {@link ThumbnailGenerator} returns empty rather than
 *       throwing for exactly this reason; the separate transaction is the belt to
 *       that braces.</li>
 *   <li><b>Nothing is reduced before it is scanned.</b> §4B.4's rule is that a
 *       file is not visible until the scan passes, and a thumbnail is visibility —
 *       it is the file, on screen, smaller. Generating one for a PENDING row
 *       would also mean running a decoder over bytes clamd has not looked at,
 *       which is the exposure {@link ThumbnailGenerator}'s javadoc explains this
 *       feature is only acceptable without.</li>
 * </ul>
 *
 * <h2>Object first, then the column</h2>
 *
 * <p>Same order, and the same reasoning, as the upload path: a crash between the
 * two leaves an object nothing points at, which is invisible litter, where the
 * reverse leaves a {@code thumbnail_key} pointing at nothing — a broken image in
 * the gallery of every user who opens the ticket.
 */
@Component
class ThumbnailTask {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailTask.class);

    private final ThumbnailGenerator generator;
    private final AttachmentStorage storage;
    private final TicketAttachmentRepository attachments;
    private final TransactionTemplate transaction;

    ThumbnailTask(ThumbnailGenerator generator,
                  AttachmentStorage storage,
                  TicketAttachmentRepository attachments,
                  PlatformTransactionManager transactionManager) {
        this.generator = generator;
        this.storage = storage;
        this.attachments = attachments;

        // REQUIRES_NEW for the same reason AttachmentScanTask uses it: this is
        // called from a pool thread with no ambient transaction, and if a caller
        // ever does have one, this work must not be able to roll it back.
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Build and store a thumbnail for a row that has just been sealed CLEAN.
     *
     * @param content the stored, EXIF-stripped bytes — passed in rather than
     *                re-read, because the caller has just had them out of storage
     *                to hand to clamd and a second GET would double the object
     *                store traffic of every upload to save nothing
     */
    void generateFor(long attachmentId, byte[] content) {
        try {
            transaction.executeWithoutResult(status -> render(attachmentId, content));
        } catch (RuntimeException failed) {
            // Nothing above this catches — the caller is an executor lambda whose
            // own catch would report this as a *scan* failure, which is both
            // wrong and alarming. A missing thumbnail is cosmetic; say so.
            log.warn("attachment {} was scanned and stored but could not be given a thumbnail",
                    attachmentId, failed);
        }
    }

    private void render(long attachmentId, byte[] content) {
        TicketAttachment attachment = attachments.findById(attachmentId).orElse(null);
        if (attachment == null) {
            return;
        }

        // Re-checked here rather than trusted from the caller. This runs in its
        // own transaction, after the scan's committed, so the row may have moved
        // on — C-028's delete window is fifteen minutes and this takes
        // milliseconds, but the race is real and the loser would be an orphaned
        // object on a ticket the user believes they cleared.
        if (attachment.isDeleted()
                || !AttachmentScanTask.CLEAN.equals(attachment.getScanStatus())
                || attachment.getThumbnailKey() != null) {
            return;
        }

        Optional<byte[]> reduced = generator.generate(attachment.getMimeType(), content);
        if (reduced.isEmpty()) {
            // Not an image, a WebP, already small enough, or undecodable. All of
            // them leave thumbnail_key null, which the contract renders as
            // `thumbnailUrl: null` and the client renders as a file icon or the
            // full image. No further action and nothing to report.
            return;
        }

        AttachmentStorageKey key = AttachmentStorageKey.parse(attachment.getStorageKey()).thumbnail();
        storage.put(key, reduced.get(), ThumbnailGenerator.MEDIA_TYPE);

        attachment.setThumbnailKey(key.toString());
        attachments.save(attachment);
    }
}
