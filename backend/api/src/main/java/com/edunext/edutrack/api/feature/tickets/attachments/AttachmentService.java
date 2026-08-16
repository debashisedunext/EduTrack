package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.permission.RolePermissions;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
    private final AttachmentRows rows;
    private final AttachmentTypePolicy types;
    private final ImageMetadataStripper stripper;
    private final AttachmentStorage storage;
    private final AttachmentScanTask scans;
    private final AttachmentProperties properties;
    private final AttachmentSettingsService limits;
    private final Clock clock;

    @Autowired
    AttachmentService(ScopedTickets tickets,
                      TicketAttachmentRepository attachments,
                      AttachmentRows rows,
                      AttachmentTypePolicy types,
                      ImageMetadataStripper stripper,
                      AttachmentStorage storage,
                      AttachmentScanTask scans,
                      AttachmentProperties properties,
                      AttachmentSettingsService limits) {
        this(tickets, attachments, rows, types, stripper, storage, scans, properties, limits, Clock.systemUTC());
    }

    /**
     * Test seam — a fixed clock is the only way to assert C-028's fifteen-minute
     * window without a test that sleeps, following {@code TicketCodeGenerator}'s
     * pair of constructors for the same reason.
     */
    AttachmentService(ScopedTickets tickets,
                      TicketAttachmentRepository attachments,
                      AttachmentRows rows,
                      AttachmentTypePolicy types,
                      ImageMetadataStripper stripper,
                      AttachmentStorage storage,
                      AttachmentScanTask scans,
                      AttachmentProperties properties,
                      AttachmentSettingsService limits,
                      Clock clock) {
        this.tickets = tickets;
        this.attachments = attachments;
        this.rows = rows;
        this.types = types;
        this.stripper = stripper;
        this.storage = storage;
        this.scans = scans;
        this.properties = properties;
        this.limits = limits;
        this.clock = clock;
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
     * Everything attached to a ticket the caller may see, plus C-028's tombstones.
     *
     * <p>PENDING and INFECTED rows are <b>returned, not hidden</b>. Hiding them
     * would make a virus-scan delay indistinguishable from a failed upload and
     * would leave a user re-attaching the same file; §4B.4's requirement is that
     * the file not become <em>readable</em>, and that is enforced by the absent
     * download URL rather than by the row's absence.
     *
     * <p><b>C-028 · deleted rows are no longer uniformly excluded.</b> §4B.4 asks
     * for a tombstone — "file removed by X on date" — "so the record of it
     * existing survives", and a listing that filtered every {@code is_deleted}
     * row could not produce one. Which deletions leave a visible mark is
     * {@link #isVisibleTombstone}'s question, not this method's.
     *
     * <p>{@code clientVisibleOnly} is applied to tombstones exactly as it is to
     * live rows, and that is the important half: an internal debug log removed
     * after the window must not surface its own name on the client portal. The
     * tombstone inherits the visibility of the file it replaces, because it is
     * still a statement about that file.
     */
    @Transactional(readOnly = true)
    List<TicketAttachment> list(Authentication caller, long ticketId, Integer cycle, Boolean clientVisibleOnly) {
        tickets.require(caller, ticketId);

        return rows.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> !row.isDeleted() || isVisibleTombstone(row))
                .filter(row -> cycle == null || (row.getCycleNo() != null && row.getCycleNo() == cycle.shortValue()))
                .filter(row -> !Boolean.TRUE.equals(clientVisibleOnly) || row.isClientVisible())
                .sorted(Comparator.comparing(TicketAttachment::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * C-028 · whether a deleted row still says so on the ticket.
     *
     * <p>§4B.4 draws the line at the uploader fixing their own mistake promptly:
     * "the uploader may delete within 15 minutes; <em>after that</em> it is a soft
     * delete leaving a tombstone". A support agent who pastes the wrong screenshot
     * and removes it ten seconds later has not done anything the ticket needs to
     * remember, and a permanent "file removed by …" for every mis-paste would
     * train everyone to read past the line that matters.
     *
     * <h2>Derived, not stored</h2>
     *
     * <p>Both facts are already on the row, so no column and no migration was
     * needed for this: <b>who</b> removed it against who uploaded it, and
     * <b>when</b> against when it arrived. A stored {@code is_silent} flag would
     * be a third thing to keep in step with the two that decide it, and would let
     * a hand-edited row present a supervisory removal as a self-correction.
     *
     * <p>The uploader comparison is what makes the rule right rather than merely
     * time-based. A PM removing a leaked client-visible file at minute three is
     * inside the window but is <em>not</em> the uploader, and that removal must
     * leave a mark — it is the supervisory act the tombstone exists to record.
     * A clock-only test would silently swallow it.
     *
     * <p><b>Unknown data shows the tombstone.</b> A null {@code deletedAt} or
     * {@code createdAt} — a row deleted before this task existed, or one written
     * by hand — cannot be placed inside the window, and the safe direction is
     * plainly the visible one: a tombstone shown where it need not be is a
     * cosmetic surprise, where one hidden that should have shown is the loss of
     * the record §4B.4 asked for.
     */
    private boolean isVisibleTombstone(TicketAttachment row) {
        if (!row.isDeleted()) {
            return false;
        }
        Instant uploadedAt = row.getCreatedAt();
        Instant removedAt = row.getDeletedAt();
        if (uploadedAt == null || removedAt == null) {
            return true;
        }
        if (row.getUploadedBy() == null || !Objects.equals(row.getUploadedBy(), row.getDeletedBy())) {
            return true;
        }
        return removedAt.isAfter(uploadedAt.plus(properties.deleteWindow()));
    }

    /**
     * C-028 · remove an attachment — blueprint §4B.4's deletion rule.
     *
     * <h2>The row always survives; only the bytes go</h2>
     *
     * <p>Every delete here is the same write: {@code is_deleted}, {@code deleted_by}
     * and {@code deleted_at} are stamped and the stored objects are removed. There
     * is no branch that issues a {@code DELETE}, and the contract's summary — which
     * calls the in-window case a "hard delete" — overstates it. The baseline
     * migration that created this table settled the question in its own comment
     * ("deletion inside the 15-minute window (C-028) removes the object, but the row
     * stays with is_deleted = 1"), and so does {@link AttachmentStorage#delete}'s.
     * Both are right: {@code deleted_by} and {@code deleted_at} are columns that
     * exist only to be read afterwards, and C-034's History timeline cannot place
     * an attachment whose row is gone.
     *
     * <p>What actually differs between the two cases is whether anyone
     * <em>sees</em> the tombstone, and that is decided at read time by
     * {@link #isVisibleTombstone} from data this method has already written. Two
     * delete paths that wrote different rows would be a second place for the rule
     * to live.
     *
     * <h2>Who may</h2>
     *
     * <p>The uploader, a PM, or an Admin. §4B.4 names the uploader and the window
     * together, and a PM or Admin is added for the case the flag beside it exists
     * for: an internal debug log attached as client-visible is a disclosure, and
     * waiting out a window to remove it would be absurd. Their removal is never
     * silent — they are not the uploader, so {@link #isVisibleTombstone} marks it —
     * which is the right asymmetry: a supervisory deletion is exactly the kind the
     * ticket should remember.
     *
     * <p>Everyone else gets 403 and not 404, because the caller is looking at the
     * row: see {@link AttachmentDeletionNotPermittedException}.
     *
     * <h2>Idempotent</h2>
     *
     * <p>A second delete of an already-deleted row is a no-op answering 204, not a
     * 404 and not a 403. The client removes optimistically
     * ({@code useTicketAttachments}) and a retry after a dropped response is
     * ordinary; the caller asked for the file to be gone and it is gone. Refusing
     * would also be a small information leak — it would distinguish "already
     * removed" from "never existed" for anyone allowed to ask.
     */
    @Transactional
    void delete(Authentication caller, long ticketId, long attachmentId) {
        tickets.require(caller, ticketId);

        // Loaded by id and then checked against the path's ticket rather than
        // queried by both. The check has to exist either way, and doing it here
        // means one lookup answers "no such row" and "not on this ticket" with the
        // same exception from the same line — which is what keeps them
        // indistinguishable (AttachmentNotFoundException).
        TicketAttachment row = attachments.findById(attachmentId)
                .filter(candidate -> candidate.getTicketId() != null && candidate.getTicketId() == ticketId)
                .orElseThrow(AttachmentNotFoundException::new);

        if (row.isDeleted()) {
            return;
        }

        CallerIdentity identity = CallerIdentity.of(caller).orElseThrow(AttachmentNotFoundException::new);
        requireMayDelete(identity, row);

        // The objects go before the row is stamped, and the order matters less
        // than the transaction does: both storage deletes are idempotent, so a
        // rollback after them leaves a live row pointing at bytes that are gone —
        // recoverable, visible, and strictly better than the reverse, which is a
        // tombstoned row whose bytes are still in the bucket and still reachable
        // by anyone holding an unexpired signed URL.
        AttachmentStorageKey key = AttachmentStorageKey.parse(row.getStorageKey());
        storage.delete(key);
        // C-026's reduction is a second object under a derived key. Deleting the
        // original alone would leave a thumbnail of a removed file in the bucket —
        // a small, legible picture of the thing that was supposed to be gone.
        // Derived rather than read from thumbnail_key so this is total: a row whose
        // key was never written still had one built if the scan reached it.
        storage.delete(key.thumbnail());

        row.setDeleted(true);
        row.setDeletedBy(identity.userId());
        row.setDeletedAt(Instant.now(clock));
        attachments.save(row);
    }

    /**
     * §4B.4's "the uploader may delete", widened to the two roles that supervise
     * the ticket.
     *
     * <p>The two refusal messages differ because the caller's next step does. Inside
     * the window a Developer looking at a colleague's file may simply be about to
     * ask them; outside it, nobody but a PM or Admin can act at all and saying so
     * saves a wasted request. Neither message names the uploader — the row already
     * does, and a refusal phrased around a person reads as permanent when the rule
     * is temporal.
     */
    private void requireMayDelete(CallerIdentity identity, TicketAttachment row) {
        if (Objects.equals(row.getUploadedBy(), identity.userId())) {
            return;
        }
        if (RolePermissions.ADMIN.equals(identity.roleCode()) || RolePermissions.PM.equals(identity.roleCode())) {
            return;
        }
        throw withinDeleteWindow(row)
                ? AttachmentDeletionNotPermittedException.notTheUploader()
                : AttachmentDeletionNotPermittedException.windowClosed();
    }

    /**
     * Whether the row is still inside §4B.4's fifteen minutes, measured from
     * upload.
     *
     * <p>Only ever asked to choose a refusal message — the permission decision
     * above does not depend on it, and neither does the write. A row with no
     * {@code createdAt} is treated as outside the window, which is the message
     * that concedes less.
     */
    private boolean withinDeleteWindow(TicketAttachment row) {
        Instant uploadedAt = row.getCreatedAt();
        return uploadedAt != null
                && !Instant.now(clock).isAfter(uploadedAt.plus(properties.deleteWindow()));
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
     *
     * <p><b>C-027 · the caps come from {@link AttachmentSettingsService} and not
     * from {@link AttachmentProperties}.</b> §4B.4 wants them configurable in
     * system settings rather than only at deploy, and this is the method that
     * has to see the change — the {@code GET} the client validates against and
     * this guard read the same {@code effective()}, so a file the picker accepts
     * is one this method accepts. Resolved per upload rather than held in a
     * field: a setting an administrator changes must apply to the next upload,
     * not to the next restart, which is the entire difference the task is
     * about.</p>
     */
    private void enforceLimits(long ticketId, long sizeBytes) {
        AttachmentLimits caps = limits.effective();

        if (sizeBytes > caps.maxFileBytes()) {
            throw AttachmentLimitExceededException.fileTooLarge(sizeBytes, caps.maxFileBytes());
        }

        List<TicketAttachment> existing = attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(ticketId);
        if (existing.size() + 1 > caps.maxFiles()) {
            throw AttachmentLimitExceededException.tooManyFiles(caps.maxFiles());
        }

        long used = existing.stream().mapToLong(TicketAttachment::getSizeBytes).sum();
        if (used + sizeBytes > caps.maxTicketBytes()) {
            throw AttachmentLimitExceededException.ticketFull(used, sizeBytes, caps.maxTicketBytes());
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
