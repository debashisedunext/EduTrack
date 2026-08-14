package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * C-025 · where the name and the bytes are made to agree — blueprint §4B.4's
 * "extension allow-list <b>and</b> MIME sniffing (not extension alone)".
 *
 * <p>Two independent opinions arrive here: {@link AttachmentType#extensionOf}
 * reads what the file claims to be, {@link AttachmentSniffer} reads what it is.
 * Both must be on §4B.4's list and both must name the <em>same</em> family. Any
 * disagreement is a refusal.
 *
 * <h2>Why agreement, and not "either one is enough"</h2>
 *
 * <p>Each check alone has a hole the other closes, and they are different holes:
 *
 * <ul>
 *   <li><b>Extension alone</b> accepts {@code payroll.pdf} that is a Windows
 *       executable. This is the failure §4B.4 names, and it is the one that ends
 *       with a support agent double-clicking the thing.</li>
 *   <li><b>Sniffing alone</b> accepts an OLE2 Installer package or a
 *       PowerPoint deck as "a legacy Office document", because at the container
 *       level that is what they are — and it accepts a {@code .docx} renamed to
 *       {@code .zip}, which means the archive path can be used to smuggle a
 *       document past a policy written about documents.</li>
 * </ul>
 *
 * <p>So neither is the guard; the conjunction is. The cost is that a file with a
 * genuinely wrong extension — a PNG somebody saved as {@code .jpg}, which is
 * ordinary — is refused with a message that says so, and refusing it is correct:
 * the alternative is silently renaming a user's file, and a rule that sometimes
 * rewrites the name it was given is a rule nobody can reason about later.
 *
 * <h2>The client's declared content type is never consulted</h2>
 *
 * <p>{@code Content-Type} on a multipart part is whatever the uploader wrote.
 * Trusting it would make the sniffer decorative — the point of reading the bytes
 * is that they are the one part of the request the client cannot restate. It is
 * not read here, not stored, and not served back: {@link AttachmentType#mediaTypeFor}
 * derives the stored type from the reconciled family instead.
 */
@Component
class AttachmentTypePolicy {

    private final AttachmentSniffer sniffer;

    AttachmentTypePolicy(AttachmentSniffer sniffer) {
        this.sniffer = sniffer;
    }

    /**
     * The outcome of reconciliation.
     *
     * @param type      the agreed family
     * @param mediaType the content type to store and to serve, derived from the
     *                  family and the corroborated extension — never from the
     *                  client's declaration
     */
    record Accepted(AttachmentType type, String mediaType) {
    }

    /**
     * @param fileName the name as uploaded, used only for its extension
     * @param content  the whole file
     * @return the reconciled family and the type to store
     * @throws UnsupportedAttachmentTypeException when the extension is off the
     *         list, the bytes are unrecognisable, or the two disagree — 415 in
     *         every case, per the contract's {@code uploadAttachment}
     */
    Accepted reconcile(String fileName, byte[] content) {
        String extension = AttachmentType.extensionOf(fileName);

        // Order matters, exactly as it does in the browser's
        // `validateAttachmentFile`: the name is reported first, because
        // "we do not accept .exe" is actionable and "the contents do not match
        // the extension" sends the user looking for a corruption that is not
        // there.
        Optional<AttachmentType> claimed = AttachmentType.forExtension(extension);
        if (claimed.isEmpty()) {
            throw UnsupportedAttachmentTypeException.extensionNotAllowed(extension);
        }

        AttachmentType actual = sniffer.sniff(content)
                .orElseThrow(() -> UnsupportedAttachmentTypeException.unrecognisedContent(extension));

        if (!actual.allows(extension)) {
            throw UnsupportedAttachmentTypeException.contentDoesNotMatchExtension(extension, actual);
        }

        return new Accepted(actual, actual.mediaTypeFor(extension));
    }
}
