package com.edunext.edutrack.api.feature.onboarding.attachments;

import com.edunext.edutrack.api.upload.UploadPipeline;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * B-107 · the module's shared upload pipeline — what a file <em>is</em>, and
 * whether it is safe.
 *
 * <p>This is the "module's shared attachment route" B-124's
 * {@code addObPrereqTemplateTaskDoc} already refers to and the contract already
 * describes. It answers exactly two questions and deliberately not a third:
 *
 * <ul>
 *   <li><b>May these bytes be stored at all?</b> Size, extension, sniffed type,
 *       EXIF.</li>
 *   <li><b>May this stored row be read?</b> {@link #isReadable} — CLEAN and not
 *       tombstoned, in one place.</li>
 * </ul>
 *
 * <p><b>Who may</b> is the question it does not answer. Scope, module role and
 * ownership are the calling feature's, because each owner arm scopes
 * differently: a client document is scoped by {@code ObClientScope}, a step
 * upload by the journey resolver, a sign-off's evidence by a token on a public
 * page. A pipeline that tried to decide all three would hold every scope rule in
 * the module and would be the one place a mistake reaches all of them.
 *
 * <h2>The order of the pipeline is the security property</h2>
 *
 * <p>C-025's sequence, unchanged, because each step exists to stop the next one
 * from seeing something it should not:
 *
 * <ol>
 *   <li><b>Limits</b> — before the bytes are examined. Refusing on size is
 *       cheap and it bounds everything after it.</li>
 *   <li><b>Type and strip</b> — extension <em>and</em> sniffing, reconciled, then
 *       metadata removed ({@link UploadPipeline#vet}). Nothing is stored until
 *       this passes, so a rejected file never occupies a key; and metadata goes
 *       before the bytes leave this process, because stripping after storage
 *       would mean the unstripped original had already been written and an
 *       object store keeps versions.</li>
 *   <li><b>Store, then insert, then scan.</b> Storage before the row: a row
 *       pointing at an object that failed to write is a broken attachment
 *       somebody can see, where an object with no row is invisible litter.</li>
 * </ol>
 *
 * <p>The caller's own authorisation runs <b>before</b> any of it, which is why
 * {@link #store} takes a resolved owner rather than an id it would have to check
 * — an unauthorised caller must not be able to spend the server's CPU sniffing a
 * 10 MB file for a client that is not theirs.
 *
 * <h2>The caps are the per-file one, and the gap is named rather than invented</h2>
 *
 * <p>{@link UploadPipeline#maxFileBytes} is enforced here, exactly as
 * {@code ChatAttachmentService} enforces it: the same configured number, so an
 * operator raising it raises it everywhere at once. C-027's runtime-editable
 * caps are <em>not</em> read, for a mundane reason — {@code AttachmentSettingsService}
 * is package-private in Stream C's package, and widening a security-relevant
 * class in another stream's code to reach it needs that owner's sign-off. Chat
 * made the same call.
 *
 * <p>What is genuinely absent is a <b>per-owner total</b>: §4B.4's 50 MB and
 * 20-file caps are stated per <em>ticket</em>, and the onboarding plan publishes
 * no equivalent for a client — §11 names "upload caps" only for the client
 * portal, which B-126 gates and CP-04 uses. Inventing a number here would refuse
 * a legitimate twenty-first document on a large client on nobody's authority.
 * So the per-file cap holds and the per-owner one is owed: the task that builds
 * CP-04's upload is where plan §11's caps get decided, and this is the method
 * that enforces them when they are.
 */
@Service
public class ObAttachmentPipeline {

    private final ObAttachmentRepository attachments;
    private final UploadPipeline uploads;
    private final ObAttachmentScanTask scans;
    private final Clock clock;

    /**
     * {@code @Autowired} is not decorative — {@code ObClientWriteService}'s own
     * note, proved again here: two constructors and no annotation is not an
     * ambiguity Spring resolves, it is a context that fails to start with "No
     * default constructor found", reported by {@code ContractConformanceTest}
     * as context-load errors naming nothing in this package.
     */
    @Autowired
    ObAttachmentPipeline(ObAttachmentRepository attachments,
                         UploadPipeline uploads,
                         ObAttachmentScanTask scans) {
        this(attachments, uploads, scans, Clock.systemUTC());
    }

    /** Test seam — {@code AttachmentService}'s pair of constructors, for its reason. */
    ObAttachmentPipeline(ObAttachmentRepository attachments,
                         UploadPipeline uploads,
                         ObAttachmentScanTask scans,
                         Clock clock) {
        this.attachments = attachments;
        this.uploads = uploads;
        this.scans = scans;
        this.clock = clock;
    }

    /**
     * Who uploaded a file, from whichever side of the portal they came.
     *
     * <p>A-102 carries this as {@code uploaded_by_type} plus two nullable
     * columns rather than one id, for {@code ob_step_communications}' reason: a
     * client contact and a staff user live in different tables, and
     * {@code ck_ob_attachments_uploader} refuses any other arrangement at the
     * column. The two factories are the only way to build one, so a row with
     * both ids or neither cannot be constructed in Java either.
     */
    public record Uploader(ObAttachmentUploaderType type, Long userId, Long contactId) {

        public static Uploader staff(long userId) {
            return new Uploader(ObAttachmentUploaderType.STAFF, userId, null);
        }

        /** B-126 · a SPOC uploading through their own portal login (CP-04). */
        public static Uploader client(long contactId) {
            return new Uploader(ObAttachmentUploaderType.CLIENT, null, contactId);
        }
    }

    /**
     * Sniff, strip, store, insert, queue for scanning.
     *
     * @return the row as inserted — {@code scanStatus} is PENDING and there is no
     *         download URL, which is what the contract's 201 describes
     * @throws ObAttachmentTooLargeException the file is over the per-file cap
     * @throws com.edunext.edutrack.api.upload.UnsupportedUploadTypeException
     *         the extension or the sniffed type is not on the allow-list
     */
    @Transactional
    public ObAttachment store(ObAttachmentOwner owner, long ownerId, ObAttachmentKind kind,
                              Uploader uploader, String fileName, byte[] content) {

        if (content.length > uploads.maxFileBytes()) {
            throw new ObAttachmentTooLargeException(content.length, uploads.maxFileBytes());
        }

        UploadPipeline.Vetted vetted = uploads.vet(fileName, content);
        byte[] cleaned = vetted.content();

        ObAttachmentStorageKey key = ObAttachmentStorageKey.mint(owner, ownerId);
        uploads.put(key, cleaned, vetted.mediaType());

        ObAttachment row = new ObAttachment();
        switch (owner) {
            case CLIENT -> row.setObClientId(ownerId);
            case STEP -> row.setStepId(ownerId);
            case SIGNOFF -> row.setSignoffId(ownerId);
            case PREREQ_TEMPLATE_TASK -> row.setPrereqTemplateTaskId(ownerId);
            case PREREQ_TASK -> row.setPrereqTaskId(ownerId);
        }
        row.setKind(kind);
        row.setUploadedByType(uploader.type());
        row.setUploadedByUser(uploader.userId());
        row.setUploadedByContact(uploader.contactId());
        row.setFileName(fileName);
        // The **sniffed** type, never the declared one. It is served back on a
        // presigned GET, and serving back a caller-supplied text/html is stored
        // XSS against whoever opens the link.
        row.setContentType(vetted.mediaType());
        row.setSizeBytes(cleaned.length);
        row.setStorageKey(key.toString());
        row.setContentSha256(sha256(cleaned));
        row.setScanStatus(ObAttachmentScanStatus.PENDING);

        ObAttachment saved = attachments.saveAndFlush(row);
        scans.submit(saved.getId());
        return saved;
    }

    /**
     * A short-lived signed URL, or nothing.
     *
     * <p>The refusals live in {@link #isReadable} rather than at the call sites:
     * a tombstoned row has no object to point at, an INFECTED one had its object
     * removed by the scanner, and a PENDING one has not been vouched for. The
     * PENDING case is the <em>common</em> one in the seconds after an upload — it
     * is not an error state, and the client is expected to reload.
     *
     * <p><b>The key is validated against the row's own owner.</b> It came out of
     * the database, and a row whose {@code storage_key} had been edited to name
     * another client's object would otherwise have that object signed and served
     * — a cross-client read through a column nobody watches.
     * {@link ObAttachmentStorageKey#belongsTo} answers false rather than
     * throwing, so a bad row costs its own URL and not the whole listing.
     */
    public Optional<URI> signedUrlFor(ObAttachment row) {
        if (!isReadable(row)) {
            return Optional.empty();
        }
        ObAttachmentOwner owner = ownerOf(row);
        if (owner == null || !ObAttachmentStorageKey.belongsTo(row.getStorageKey(), owner, ownerIdOf(row))) {
            return Optional.empty();
        }
        return Optional.of(uploads.signedDownloadUrl(
                ObAttachmentStorageKey.parse(row.getStorageKey()),
                row.getFileName(),
                row.getContentType(),
                uploads.signedUrlTtl()));
    }

    /**
     * Remove the stored object and tombstone the row.
     *
     * <p><b>The row always survives.</b> A-102's own comment settles it —
     * "removed by deactivation, never by DELETE — a file referenced by a sign-off
     * or a completed step is evidence, and the row has to keep resolving" — and
     * {@code ck_ob_attachments_deleted} makes the stamp and its actor move
     * together at the column.
     *
     * <p>The object goes before the row is stamped, and the order matters less
     * than the transaction does: the storage delete is idempotent, so a rollback
     * after it leaves a live row pointing at bytes that are gone — recoverable,
     * visible, and strictly better than the reverse, which is a tombstoned row
     * whose bytes are still in the bucket and still reachable by anyone holding
     * an unexpired signed URL.
     *
     * <p>Idempotent: tombstoning an already-tombstoned row does nothing and does
     * not re-stamp it, so a retried DELETE cannot rewrite who removed a file.
     */
    @Transactional
    public void tombstone(ObAttachment row, long removedByUserId) {
        if (row.getDeletedAt() != null) {
            return;
        }
        try {
            uploads.delete(ObAttachmentStorageKey.parse(row.getStorageKey()));
        } catch (IllegalArgumentException unparseable) {
            // A key this application cannot parse addresses no object it can
            // reach, so there is nothing to remove and the tombstone is still
            // the right outcome. Refusing would leave a row nobody can retire.
        }
        row.setDeletedAt(Instant.now(clock));
        row.setDeletedBy(removedByUserId);
        attachments.save(row);
    }

    /**
     * A-102's "nothing may be served to anyone while this is PENDING or
     * INFECTED", in one place.
     *
     * <p>Public because the owner arms outside this package render the same rows
     * and must not restate it — a second restatement is where "PENDING is
     * probably fine" gets written.
     */
    public boolean isReadable(ObAttachment row) {
        return row.getDeletedAt() == null && row.getScanStatus() == ObAttachmentScanStatus.CLEAN;
    }

    /**
     * Which arm this row hangs off, read back from the columns.
     *
     * <p>Null for a row with no owner set, which {@code ck_ob_attachments_one_owner}
     * makes unreachable through the database — but this method is asked before a
     * URL is signed, and answering "some owner" for a row that has none would
     * defeat the check it feeds.
     */
    static ObAttachmentOwner ownerOf(ObAttachment row) {
        if (row.getObClientId() != null) {
            return ObAttachmentOwner.CLIENT;
        }
        if (row.getStepId() != null) {
            return ObAttachmentOwner.STEP;
        }
        if (row.getSignoffId() != null) {
            return ObAttachmentOwner.SIGNOFF;
        }
        if (row.getPrereqTemplateTaskId() != null) {
            return ObAttachmentOwner.PREREQ_TEMPLATE_TASK;
        }
        if (row.getPrereqTaskId() != null) {
            return ObAttachmentOwner.PREREQ_TASK;
        }
        return null;
    }

    private static long ownerIdOf(ObAttachment row) {
        ObAttachmentOwner owner = ownerOf(row);
        if (owner == null) {
            return 0;
        }
        return switch (owner) {
            case CLIENT -> row.getObClientId();
            case STEP -> row.getStepId();
            case SIGNOFF -> row.getSignoffId();
            case PREREQ_TEMPLATE_TASK -> row.getPrereqTemplateTaskId();
            case PREREQ_TASK -> row.getPrereqTaskId();
        };
    }

    /**
     * Hex SHA-256 of the stored bytes — {@code ob_attachments.content_sha256}.
     *
     * <p>Written because the column exists to be written: A-102 put it there so a
     * re-upload of the same file can be recognised rather than stored twice, and
     * so an integrity check has something to compare against. <b>Nothing acts on
     * it yet</b>, and that is deliberate: what should happen when the same bytes
     * arrive twice under one owner — refuse, return the existing row, or store
     * both — is a product decision no screen has asked for, and a pipeline that
     * silently returned somebody else's earlier row would be answering a question
     * nobody put to it. The hash is over the <em>stripped</em> bytes, so two
     * uploads of one photo taken from different phones match after their EXIF is
     * gone, which is the case the column is actually for.
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; MessageDigest declares the checked
            // exception anyway. Unreachable, and a null hash is preferable to a
            // failed upload if it ever is not.
            return null;
        }
    }
}
