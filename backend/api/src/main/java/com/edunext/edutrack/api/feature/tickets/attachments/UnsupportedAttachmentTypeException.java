package com.edunext.edutrack.api.feature.tickets.attachments;

import java.io.Serial;

/**
 * C-025 · 415. The file is not one of blueprint §4B.4's types, or is not what it
 * says it is.
 *
 * <h2>What the caller is told, and what it is not</h2>
 *
 * <p>The message names the extension the user chose and, where the two
 * disagreed, <em>what the file actually is</em>. That is deliberate and it is
 * not an information leak: every fact in it came out of the request the caller
 * just sent. Being told "this .pdf is really a ZIP archive" is how somebody
 * discovers they attached the wrong file, and the alternative — a flat "not
 * allowed" — sends them to re-upload the same bytes twice before asking a
 * colleague.
 *
 * <p>What it never contains is the allow-list <em>rule</em> that failed in terms
 * of internals: no family enum names, no signature bytes, no storage detail.
 * {@link AttachmentType#allowedExtensions()} is included because §4B.4's list is
 * product behaviour a user is entitled to know, and is on screen in the picker
 * already.
 */
class UnsupportedAttachmentTypeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private UnsupportedAttachmentTypeException(String message) {
        super(message);
    }

    static UnsupportedAttachmentTypeException extensionNotAllowed(String extension) {
        String what = extension.isEmpty()
                ? "Files without an extension are not allowed"
                : "." + extension + " files are not allowed";
        return new UnsupportedAttachmentTypeException(what + ". Allowed: " + allowed());
    }

    /**
     * The bytes match no format on the list.
     *
     * <p>Distinct from a mismatch on purpose: a truncated upload, an empty
     * placeholder from a still-syncing cloud drive and a genuinely exotic format
     * all land here, and none of them is helped by being told the file "is
     * really" something.
     */
    static UnsupportedAttachmentTypeException unrecognisedContent(String extension) {
        return new UnsupportedAttachmentTypeException(
                "This file's contents are not a recognised ." + extension
                        + " file. It may be corrupt or incompletely uploaded.");
    }

    /**
     * The interesting one: both halves are on the list and they name different
     * formats. A renamed executable never reaches here — it has no allowed
     * extension, or its bytes match nothing — so this is overwhelmingly a
     * genuine mistake, and the message is written for that reader.
     */
    static UnsupportedAttachmentTypeException contentDoesNotMatchExtension(String extension, AttachmentType actual) {
        return new UnsupportedAttachmentTypeException(
                "This file is named ." + extension + " but its contents are "
                        + describe(actual) + ". Rename it or attach the right file.");
    }

    /**
     * The family in the words a user would use. The enum's own name is an
     * implementation detail and {@code DOCX} tells a support agent nothing that
     * "a Word document" does not tell them better.
     */
    private static String describe(AttachmentType type) {
        return switch (type) {
            case PNG -> "a PNG image";
            case JPEG -> "a JPEG image";
            case GIF -> "a GIF image";
            case WEBP -> "a WebP image";
            case PDF -> "a PDF";
            case MP4 -> "an MP4 video";
            case ZIP -> "a ZIP archive";
            case DOCX -> "a Word document (.docx)";
            case XLSX -> "an Excel workbook (.xlsx)";
            case DOC -> "a legacy Word document (.doc)";
            case XLS -> "a legacy Excel workbook (.xls)";
            case TEXT -> "plain text";
        };
    }

    private static String allowed() {
        return String.join(", ", AttachmentType.allowedExtensions());
    }
}
