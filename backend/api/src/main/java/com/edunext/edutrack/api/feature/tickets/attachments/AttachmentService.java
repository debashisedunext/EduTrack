package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * C-025 · the upload pipeline and the read path — blueprint §4B.4.
 *
 * <h2>The order of the pipeline is the security property</h2>
 *
 * <p>Each step exists to stop the next one from ever seeing something it should
 * not, so the sequence is not interchangeable:
 *
 * <ol>
 *   <li><b>Scope</b> — {@link ScopedTickets#require} first, so a caller who may
 *       not see the ticket cannot learn anything by uploading to it, and cannot
 *       spend the server's CPU on sniffing a 10 MB file for a ticket that is not
 *       theirs. Out of scope is 404, never 403 (A-035).</li>
 *   <li><b>Limits</b> — before the bytes are examined, because refusing on size
 *       is cheap and the caps are the bound on everything after it.</li>
 *   <li><b>Type</b> — extension <em>and</em> sniffing, reconciled
 *       ({@link AttachmentTypePolicy}). Nothing is stored until this passes, so
 *       a rejected file never occupies a key.</li>
 *   <li><b>Strip</b> — metadata goes before the bytes leave this process. Doing
 *       it after storage would mean the unstripped original had already been
 *       written, and an object store keeps versions.</li>
 *   <li><b>Store, then insert, then scan.</b> Storage before the row: a row
 *       pointing at an object that failed to write is a broken attachment the
 *       user can see, where an object with no row is invisible litter the
 *       PENDING sweeper collects.</li>
 * </ol>
 *
 * <h2>The scan status is the visibility rule, in one place</h2>
 *
 * <p>{@link #signedUrlFor} and C-026's {@link #thumbnailUrlFor} are the only two
 * methods in the application that can turn a stored attachment into something
 * readable, and <b>both</b> ask {@link #isReadable}, which returns false for
 * anything that is not CLEAN or has been deleted. Every list, gallery and download
 * therefore inherits the rule without restating it — which is the point, because a
 * second restatement is where "PENDING is probably fine" gets written, and a
 * thumbnail is the most tempting place to write it: it looks like a preview rather
 * than like the file.
 */
@Service
class AttachmentService {

    private final ScopedTickets tickets;
    private final TicketAttachmentRepository attachments;
    private final AttachmentTypePolicy types;
    private final ImageMetadataStripper stripper;
    private final AttachmentStorage storage;
    private final AttachmentScanTask scans;
    private final AttachmentProperties properties;

    AttachmentService(ScopedTickets tickets,
                      TicketAttachmentRepository attachments,
                      AttachmentTypePolicy types,
                      ImageMetadataStripper stripper,
                      AttachmentStorage storage,
                      AttachmentScanTask scans,
                      AttachmentProperties properties) {
        this.tickets = tickets;
        this.attachments = attachments;
        this.types = types;
        this.stripper = stripper;
        this.storage = storage;
        this.scans = scans;
        this.properties = properties;
    }

    /**
     * Store one file against a ticket.
     *
     * @return the row as inserted — {@code scanStatus} is PENDING and
     *         {@code downloadUrl} is absent, which is what the contract's 201
     *         describes
     */
    @Transactional
    TicketAttachment upload(Authentication caller, long ticketId, Upload upload) {
        Ticket ticket = tickets.require(caller, ticketId);

        byte[] content = upload.content();
        enforceLimits(ticketId, content.length);

        AttachmentTypePolicy.Accepted accepted = types.reconcile(upload.fileName(), content);
        byte[] cleaned = stripper.strip(accepted.type(), content);

        AttachmentStorageKey key = AttachmentStorageKey.mint(ticketId);
        storage.put(key, cleaned, accepted.mediaType());

        TicketAttachment row = new TicketAttachment();
        row.setTicketId(ticketId);
        row.setCommentId(upload.commentId());
        // Stamped from the ticket, not from the request. §4B.4's attachments tab
        // groups by cycle and stage, and a client that could name its own would
        // be able to file evidence into a sealed cycle's journey.
        row.setCycleNo(ticket.getCurrentCycleNo());
        row.setStageCode(ticket.getCurrentStage());
        row.setFileName(upload.fileName());
        row.setStorageKey(key.toString());
        row.setMimeType(accepted.mediaType());
        row.setSizeBytes(cleaned.length);
        row.setClientVisible(upload.clientVisible());
        row.setScanStatus(AttachmentScanTask.PENDING);
        row.setUploadedBy(CallerIdentity.of(caller).map(CallerIdentity::userId).orElse(null));

        TicketAttachment saved = attachments.saveAndFlush(row);
        scans.submit(saved.getId());
        return saved;
    }

    /**
     * Everything attached to a ticket the caller may see.
     *
     * <p>PENDING and INFECTED rows are <b>returned, not hidden</b>. Hiding them
     * would make a virus-scan delay indistinguishable from a failed upload and
     * would leave a user re-attaching the same file; §4B.4's requirement is that
     * the file not become <em>readable</em>, and that is enforced by the absent
     * download URL rather than by the row's absence.
     */
    @Transactional(readOnly = true)
    List<TicketAttachment> list(Authentication caller, long ticketId, Integer cycle, Boolean clientVisibleOnly) {
        tickets.require(caller, ticketId);

        return attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> cycle == null || (row.getCycleNo() != null && row.getCycleNo() == cycle.shortValue()))
                .filter(row -> !Boolean.TRUE.equals(clientVisibleOnly) || row.isClientVisible())
                .sorted(Comparator.comparing(TicketAttachment::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * A short-lived signed URL, or nothing.
     *
     * <p>The refusals are §4B.4's visibility rule and they live in
     * {@link #isReadable} rather than at the call sites: a deleted row has no
     * object to point at, an INFECTED one had its object removed by the scanner,
     * and a PENDING one has not been vouched for. Note that the PENDING case is
     * the <em>common</em> one during the seconds after an upload — it is not an
     * error state, and the client is expected to poll or reload.
     */
    Optional<URI> signedUrlFor(TicketAttachment attachment) {
        if (!isReadable(attachment)) {
            return Optional.empty();
        }
        AttachmentStorageKey key = AttachmentStorageKey.parse(attachment.getStorageKey());
        return Optional.of(storage.signedDownloadUrl(
                key, attachment.getFileName(), attachment.getMimeType(), properties.signedUrlTtl()));
    }

    /**
     * The same, for C-026's reduced copy — or nothing, which is the ordinary case.
     *
     * <p>Most attachments have no thumbnail and never will: a PDF, a log, a
     * spreadsheet, a WebP the JVM cannot decode, or an image already smaller than
     * the target box. {@code thumbnail_key} is null for all of them and the client
     * renders an icon or the full image.
     *
     * <p>Three things are worth noting about how narrow this is:
     *
     * <ul>
     *   <li>It goes through the <b>same</b> {@link #isReadable} as the original.
     *       A thumbnail is the file on screen, so a PENDING or INFECTED row must
     *       not have one signed any more than it has its original signed — and
     *       stating the rule twice is how the two would eventually disagree.</li>
     *   <li>The key is <b>validated against the row's own ticket</b>. It came out
     *       of the database, and a row whose {@code thumbnail_key} had been edited
     *       to name another ticket's object would otherwise have that object
     *       signed and served, which is a cross-ticket read through a column
     *       nobody watches. {@link AttachmentStorageKey#belongsTo} answers false
     *       rather than throwing, so a bad row costs its thumbnail and not the
     *       whole listing.</li>
     *   <li>The content type is the <b>constant</b> {@link ThumbnailGenerator#MEDIA_TYPE},
     *       never {@code mimeType}. A thumbnail is always a PNG this application
     *       encoded, whatever the original was, so the row has no say in what the
     *       browser is told it is receiving.</li>
     * </ul>
     */
    Optional<URI> thumbnailUrlFor(TicketAttachment attachment) {
        String thumbnailKey = attachment.getThumbnailKey();
        if (thumbnailKey == null || !isReadable(attachment)) {
            return Optional.empty();
        }
        if (!AttachmentStorageKey.belongsTo(thumbnailKey, attachment.getTicketId())) {
            return Optional.empty();
        }
        return Optional.of(storage.signedDownloadUrl(
                AttachmentStorageKey.parse(thumbnailKey),
                thumbnailFileName(attachment.getFileName()),
                ThumbnailGenerator.MEDIA_TYPE,
                properties.signedUrlTtl()));
    }

    /** §4B.4's "the file becomes visible only after the scan passes", in one place. */
    private static boolean isReadable(TicketAttachment attachment) {
        return !attachment.isDeleted() && AttachmentScanTask.CLEAN.equals(attachment.getScanStatus());
    }

    /**
     * What a saved thumbnail is called.
     *
     * <p>The original's stem with a {@code .png} extension, because the bytes
     * <em>are</em> a PNG whatever the source was — handing back
     * {@code screenshot.jpg} for PNG bytes would put a file on someone's disk
     * that no viewer opens by double-click. The stem is kept so the file is still
     * recognisable as belonging to the attachment it came from.
     */
    private static String thumbnailFileName(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            return "thumbnail.png";
        }
        int dot = name.lastIndexOf('.');
        return (dot <= 0 ? name : name.substring(0, dot)) + ".png";
    }

    /**
     * §4B.4's three caps, per ticket.
     *
     * <p>Counted over the ticket's live rows rather than tracked on a column: a
     * running total maintained by hand would need C-028's delete to decrement it,
     * and a total that drifts upward locks a ticket out of attachments for ever
     * with no visible cause. The query is indexed by {@code ix_attachments_ticket}
     * and the row count is at most twenty by definition of the rule it enforces.
     *
     * <p>Deleted rows do not count. A tombstone records that something was
     * attached and removed; charging the ticket for storage it no longer uses
     * would make the 15-minute delete window pointless.
     */
    private void enforceLimits(long ticketId, long sizeBytes) {
        if (sizeBytes > properties.maxFileBytes()) {
            throw AttachmentLimitExceededException.fileTooLarge(sizeBytes, properties.maxFileBytes());
        }

        List<TicketAttachment> existing = attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId);
        if (existing.size() + 1 > properties.maxFiles()) {
            throw AttachmentLimitExceededException.tooManyFiles(properties.maxFiles());
        }

        long used = existing.stream().mapToLong(TicketAttachment::getSizeBytes).sum();
        if (used + sizeBytes > properties.maxTicketBytes()) {
            throw AttachmentLimitExceededException.ticketFull(used, sizeBytes, properties.maxTicketBytes());
        }
    }

    /**
     * One upload, already read into memory.
     *
     * <p>A {@code byte[]} rather than the {@code MultipartFile}: the sniffer, the
     * stripper and the store each need the whole content, and a stream would have
     * to be read three times or buffered anyway. The container's
     * {@code max-file-size} is what bounds it, which is why that setting and
     * {@link AttachmentProperties#maxFileBytes} must stay in step.
     */
    record Upload(String fileName, byte[] content, boolean clientVisible, Long commentId) {
    }
}
