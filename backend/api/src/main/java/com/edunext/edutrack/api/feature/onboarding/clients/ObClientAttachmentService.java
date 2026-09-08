package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentDtos;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentOwner;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentPipeline;
import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B-107 · OB-05's client documents — the signed contract, the PO, the branding
 * pack, whatever the boarding team files against the client record.
 *
 * <h2>What this task actually adds, and what it deliberately does not</h2>
 *
 * <p>The pipeline is not new. {@code AttachmentTypePolicy} (sniff the bytes,
 * reconcile against the declared name, refuse what is not on the allow-list),
 * {@code ImageMetadataStripper} (EXIF, which carries GPS) and
 * {@code AttachmentScanner} are the <b>same beans</b> C-025 and D-053 use — the
 * backlog entry says "the existing upload pipeline, unchanged" and that is
 * meant literally. What is new is an owner arm, its scope rule, and who may
 * remove a file.
 *
 * <p>{@code ob_attachments} already existed as A-102's migration with a
 * {@code ob_client_id} arm, and {@code ObAttachment} already mapped every
 * column. So B-107 adds <b>no migration at all</b>.
 *
 * <h2>The order of refusals is the security property</h2>
 *
 * <p>{@code ObRequirementService}'s order, unchanged, because the reasoning is
 * the same one: scoped client read (404) → write role (403) → attachment
 * resolution (404) → size (413) → type (415).
 *
 * <ul>
 *   <li>The scoped read is <b>first</b>, so an out-of-scope client id is
 *       indistinguishable from a missing one, and so a caller who may not see
 *       the client cannot spend the server's CPU on sniffing a 10 MB file for
 *       it.</li>
 *   <li>The write role precedes the attachment lookup, so a Viewer cannot use
 *       the 404/403 difference to probe which attachment ids exist under a
 *       client they can see.</li>
 *   <li>Size and type come last, inside {@code ObAttachmentPipeline}, because
 *       they are facts about the bytes and not about the caller.</li>
 * </ul>
 *
 * <h2>Attachments are not part of the client document, and that is on purpose</h2>
 *
 * <p>Every other panel on OB-05 — contacts, purchases, requirements — answers
 * with the whole {@code ObClientDetail} and a fresh {@code ETag}, so the page
 * never holds a stale tag. This one does not, and the reason is the AV scan:
 * {@code scanStatus} moves from PENDING to CLEAN seconds later on a background
 * thread, with no user action behind it. Folding attachments into the client
 * document would move the client's {@code ETag} for a change nobody made, and
 * whoever was editing the address bar at that moment would get a 412 they could
 * not account for — which is precisely the failure {@link ObClientETag}'s own
 * note describes as the hardest to reproduce. So these routes carry their own
 * response shape and no precondition.
 *
 * <h2>Who may remove a file</h2>
 *
 * <p>The uploader, an OB Admin, or an Onboarding Manager. §4B.4's ticket rule
 * one module over, and the widening has the same reason: a document filed
 * against the wrong client is a disclosure of one company's paperwork to
 * whoever opens another company's record, and waiting out a timer to remove it
 * would be absurd.
 *
 * <p><b>Sales is deliberately not on that list, although Sales may upload.</b>
 * {@code ObClientScope.mayWrite} lets Sales edit clients they created, and a
 * salesperson filing the signed order form is the ordinary case. Removing
 * somebody else's document is a different act: their scope already limits them
 * to their own clients, and within those the supervisory removal belongs to the
 * two roles §3 gives oversight to. A salesperson can still remove what they
 * uploaded, which is the whole of the mistake they can make.
 */
@Service
class ObClientAttachmentService {

    private final ObClientService details;
    private final ObAttachmentPipeline pipeline;
    private final ObAttachmentRepository attachments;
    private final UserRepository users;
    private final UploadPipeline uploads;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObClientWriteService}'s note
     * and {@code ObRequirementService}'s repeat of it: two constructors and no
     * annotation is not an ambiguity Spring resolves, it is a context that fails
     * to start with "No default constructor found".
     */
    @Autowired
    ObClientAttachmentService(ObClientService details,
                              ObAttachmentPipeline pipeline,
                              ObAttachmentRepository attachments,
                              UserRepository users,
                              UploadPipeline uploads) {
        this(details, pipeline, attachments, users, uploads, Clock.systemUTC());
    }

    /**
     * Test seam — a fixed clock is the only way to assert the fifteen-minute
     * silent-removal window without a test that sleeps.
     */
    ObClientAttachmentService(ObClientService details,
                              ObAttachmentPipeline pipeline,
                              ObAttachmentRepository attachments,
                              UserRepository users,
                              UploadPipeline uploads,
                              Clock clock) {
        this.details = details;
        this.pipeline = pipeline;
        this.attachments = attachments;
        this.users = users;
        this.uploads = uploads;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /**
     * File one document against a client.
     *
     * <p>The kind is the caller's, from a closed pair, and the pair is narrower
     * than the column's four values on purpose — see {@link #kindOf}.
     */
    @Transactional
    ObAttachmentDtos.ObAttachmentView upload(ObClientScope scope, long obClientId, String kind,
                                             String fileName, byte[] content) {

        requireWritableClient(scope, obClientId);

        ObAttachment saved = pipeline.store(ObAttachmentOwner.CLIENT, obClientId, kindOf(kind),
                ObAttachmentPipeline.Uploader.staff(scope.userId()), fileName, content);

        return view(saved, namesFor(List.of(saved)));
    }

    /**
     * A-102's four kinds, narrowed to the two a client-owned file can be.
     *
     * <p>{@code DELIVERABLE} belongs to a service and {@code EVIDENCE} to a
     * sign-off — the other two owner arms of the same table — and a document
     * filed against the client record is neither. Refusing them here is a
     * category boundary the database cannot draw, because the CHECK constrains
     * which owner column is set and says nothing about which kinds go with which
     * owner. Left open, the first caller to send {@code EVIDENCE} would create a
     * row B-116's sign-off reader has no way to find and OB-05 would render as
     * an ordinary document.
     *
     * <p>An absent kind is {@code SUBMISSION}, which is the column's own default
     * and the common case: what staff file here is overwhelmingly what the
     * client sent in.
     */
    private static ObAttachmentKind kindOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return ObAttachmentKind.SUBMISSION;
        }
        String requested = kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CLIENT_KINDS.contains(requested)) {
            throw new ObClientValidationException(Map.of("kind",
                    "A client document is REFERENCE or SUBMISSION."));
        }
        return ObAttachmentKind.valueOf(requested);
    }

    private static final Set<String> CLIENT_KINDS = Set.of("REFERENCE", "SUBMISSION");

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /**
     * Everything filed against a client this caller may see, plus the tombstones
     * worth keeping.
     *
     * <p><b>PENDING and INFECTED rows are returned, not hidden.</b> C-025's rule
     * and its reason: hiding them would make a scan delay indistinguishable from
     * a failed upload and would leave somebody re-attaching the same file. The
     * requirement is that the file not become <em>readable</em>, and that is
     * enforced by the absent download URL rather than by the row's absence.
     *
     * <p>A read is every module role's — §3 makes Viewer "everything,
     * read-only" — so this asks only for visibility, never for
     * {@code mayWrite}.
     */
    @Transactional(readOnly = true)
    List<ObAttachmentDtos.ObAttachmentView> list(ObClientScope scope, long obClientId) {
        requireVisibleClient(scope, obClientId);

        List<ObAttachment> rows = attachments.findByObClientIdOrderByIdAsc(obClientId).stream()
                .filter(row -> row.getDeletedAt() == null || isVisibleTombstone(row))
                .toList();

        Map<Long, String> names = namesFor(rows);
        return rows.stream().map(row -> view(row, names)).toList();
    }

    // ------------------------------------------------------------------
    // Remove
    // ------------------------------------------------------------------

    /**
     * Remove a document — the bytes always, the row never.
     *
     * <p>Every removal here writes the same tombstone; what differs is whether
     * anybody <em>sees</em> it, and that is decided at read time by
     * {@link #isVisibleTombstone} from data this method has already written. Two
     * delete paths writing different rows would be a second place for the rule
     * to live.
     *
     * <p><b>Idempotent.</b> A second removal of an already-tombstoned row answers
     * 204 rather than 404 or 403: the caller asked for the file to be gone and it
     * is gone, and refusing would distinguish "already removed" from "never
     * existed" for anyone allowed to ask.
     */
    @Transactional
    void delete(ObClientScope scope, long obClientId, long attachmentId) {
        // The scoped client read first, then the row. A Viewer is stopped by
        // requireMayRemove below rather than here, because a Viewer who may not
        // remove anything still may not learn which attachment ids exist — so
        // the row is resolved before the verb is judged, and both refusals are
        // reached only for a client this caller can already see.
        requireVisibleClient(scope, obClientId);

        ObAttachment row = attachments.findByIdAndObClientId(attachmentId, obClientId)
                .orElseThrow(() -> new ObClientAttachmentNotFoundException(obClientId, attachmentId));

        if (row.getDeletedAt() != null) {
            return;
        }

        requireMayRemove(scope, row);
        pipeline.tombstone(row, scope.userId());
    }

    /**
     * The uploader, an OB Admin, or an Onboarding Manager — the class note has
     * the argument for the shape and for Sales's absence.
     *
     * <p>The two refusal messages differ because the caller's next step does.
     * Inside the window somebody looking at a colleague's file may simply be
     * about to ask them; outside it, nobody but an Admin or a Manager can act at
     * all and saying so saves a wasted request. Neither message names the
     * uploader — the row already does, and a refusal phrased around a person
     * reads as permanent when the rule is temporal.
     *
     * <p>A file uploaded from the client portal has no {@code uploadedByUser} at
     * all, so no staff user is ever "the uploader" of one. That is the correct
     * reading rather than an oversight: a client's own submission is removed by
     * an Admin or a Manager, which is a supervisory act and leaves a visible
     * tombstone.
     */
    private void requireMayRemove(ObClientScope scope, ObAttachment row) {
        if (row.getUploadedByType() == ObAttachmentUploaderType.STAFF
                && Objects.equals(row.getUploadedByUser(), scope.userId())) {
            return;
        }
        if (ObClientScope.OB_ADMIN.equals(scope.moduleRole())
                || ObClientScope.OB_MANAGER.equals(scope.moduleRole())) {
            return;
        }
        throw withinRemovalWindow(row)
                ? ObClientAttachmentRemovalNotPermittedException.notTheUploader()
                : ObClientAttachmentRemovalNotPermittedException.windowClosed();
    }

    /**
     * Whether a removed row still says so on OB-05.
     *
     * <p>§4B.4 draws the line at the uploader fixing their own mistake promptly,
     * and the same line is right here: somebody who attaches the wrong PDF and
     * removes it ten seconds later has not done anything the client record needs
     * to remember, and a permanent "file removed by …" for every mis-drop would
     * train everyone to read past the line that matters.
     *
     * <h2>Derived, not stored</h2>
     *
     * <p>Both facts are already on the row, so this needed no column and no
     * migration: <b>who</b> removed it against who uploaded it, and <b>when</b>
     * against when it arrived. A stored flag would be a third thing to keep in
     * step with the two that decide it, and would let a hand-edited row present a
     * supervisory removal as a self-correction.
     *
     * <p>The uploader comparison is what makes the rule right rather than merely
     * time-based. A Manager removing a leaked document at minute three is inside
     * the window but is <em>not</em> the uploader, and that removal must leave a
     * mark — it is the supervisory act the tombstone exists to record.
     *
     * <p><b>Unknown data shows the tombstone.</b> A null stamp on either side
     * cannot be placed inside the window, and the safe direction is plainly the
     * visible one: a tombstone shown where it need not be is a cosmetic
     * surprise, where one hidden that should have shown is the loss of the record
     * itself.
     */
    private boolean isVisibleTombstone(ObAttachment row) {
        if (row.getDeletedAt() == null) {
            return false;
        }
        Instant uploadedAt = row.getCreatedAt();
        if (uploadedAt == null) {
            return true;
        }
        if (row.getUploadedByType() != ObAttachmentUploaderType.STAFF
                || row.getUploadedByUser() == null
                || !Objects.equals(row.getUploadedByUser(), row.getDeletedBy())) {
            return true;
        }
        return row.getDeletedAt().isAfter(uploadedAt.plus(uploads.removalWindow()));
    }

    /**
     * Whether the row is still inside the fifteen minutes, measured from upload.
     *
     * <p>Only ever asked to choose a refusal message — the permission decision
     * does not depend on it, and neither does the write. A row with no
     * {@code createdAt} is treated as outside the window, which is the message
     * that concedes less.
     */
    private boolean withinRemovalWindow(ObAttachment row) {
        Instant uploadedAt = row.getCreatedAt();
        return uploadedAt != null
                && !Instant.now(clock).isAfter(uploadedAt.plus(uploads.removalWindow()));
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /** The scoped read on its own — a read is every module role's. */
    private void requireVisibleClient(ObClientScope scope, long obClientId) {
        details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
    }

    /**
     * The scoped read, then the write role — 404 before 403 and never the
     * reverse. {@code ObRequirementService.requireWritableClient}'s reasoning
     * verbatim.
     */
    private void requireWritableClient(ObClientScope scope, long obClientId) {
        ObClientDtos.ObClientDetail client = details.findDetail(scope, obClientId)
                .orElseThrow(() -> new ObClientNotFoundException(obClientId));
        if (!scope.mayWrite()) {
            throw new ObClientReadOnlyException(client.name());
        }
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    /**
     * Staff display names for a page of rows, in one query.
     *
     * <p>One lookup for the whole listing rather than one per row — a client with
     * twenty documents would otherwise be twenty queries for a column that is
     * usually the same three people. Contacts are not resolved: no row can carry
     * one until B-126 gives a client a portal login, and the view renders the
     * client side from {@code uploadedByType} rather than from a name.
     */
    private Map<Long, String> namesFor(List<ObAttachment> rows) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (ObAttachment row : rows) {
            if (row.getUploadedByUser() != null) {
                ids.add(row.getUploadedByUser());
            }
            if (row.getDeletedBy() != null) {
                ids.add(row.getDeletedBy());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (User user : users.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    /**
     * The row as the contract's {@code ObAttachment}.
     *
     * <p>The download URL comes from {@link ObAttachmentPipeline#signedUrlFor},
     * which is the one method in the module that can turn a stored file into
     * something readable. Restating the CLEAN-and-not-tombstoned rule here would
     * be the second place it lives, and the second place is where "PENDING is
     * probably fine" eventually gets written.
     */
    private ObAttachmentDtos.ObAttachmentView view(ObAttachment row, Map<Long, String> names) {
        boolean removed = row.getDeletedAt() != null;
        return new ObAttachmentDtos.ObAttachmentView(
                row.getId(),
                row.getFileName(),
                row.getContentType(),
                row.getSizeBytes(),
                row.getKind().name(),
                row.getUploadedByType().name(),
                row.getScanStatus().name(),
                pipeline.signedUrlFor(row).map(java.net.URI::toString).orElse(null),
                removed,
                ObAttachmentDtos.ActorRef.of(row.getUploadedByUser(),
                        names.get(row.getUploadedByUser())),
                ObAttachmentDtos.ActorRef.of(row.getDeletedBy(), names.get(row.getDeletedBy())),
                row.getDeletedAt(),
                row.getCreatedAt());
    }
}
