package com.edunext.edutrack.api.feature.tickets.attachments;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * C-025 · blueprint §4B.4's allow-list, keyed by <em>what the bytes are</em>
 * rather than by what the name claims.
 *
 * <p>Each constant is a <b>format family</b>: the thing a sniffer can actually
 * decide from a file's leading bytes. The extensions that may legitimately carry
 * that family hang off it, and {@link #forExtension(String)} inverts the map —
 * so the two questions the security check asks ("is this extension allowed" and
 * "do the bytes agree with it") are answered from one table rather than from two
 * lists that can drift apart.
 *
 * <h2>Why the family and not the MIME type is the unit</h2>
 *
 * <p>A sniffer cannot return {@code application/vnd.openxmlformats-officedocument.wordprocessingml.document}
 * from four magic bytes — {@code PK\03\04} says ZIP and nothing more, and every
 * {@code .docx}, {@code .xlsx} and {@code .zip} on the list starts with it.
 * Discriminating those needs a second step that reads the container's entries
 * ({@link AttachmentSniffer}), and the same is true of OLE2, where {@code .doc}
 * and {@code .xls} are the same eight bytes followed by different streams. So
 * the family is the honest granularity of the first pass, and the MIME type is
 * derived from the family plus the extension once the two have been reconciled.
 *
 * <h2>The text tier has no signature, and that is the point</h2>
 *
 * <p>{@code csv}, {@code txt} and {@code log} have no magic bytes at all — a log
 * file is whatever the process wrote. {@link #TEXT} therefore cannot be
 * <em>recognised</em>, only <em>left over</em>: the sniffer reaches it when the
 * bytes match no binary signature it knows and decode as text. That makes the
 * check for those three "is not a disguised binary" rather than "is a known
 * format", which is the strongest claim the format admits. §4B.4 put {@code log}
 * on the list because support agents attach them constantly, so refusing the
 * tier outright was never an option.
 *
 * <p>The legacy binary Office formats are on the list for the reason §4B.4
 * states — clients still send them — and they are also the reason the extension
 * alone is never the test: a {@code .doc} is an OLE2 container, and so is a
 * 1998-era Excel macro workbook renamed by hand.
 */
enum AttachmentType {

    /** {@code \x89PNG\r\n\x1a\n} — the signature that also detects a truncated transfer. */
    PNG("image/png", "png"),

    /** {@code FF D8 FF} — SOI plus the first marker's leading byte. */
    JPEG("image/jpeg", "jpg", "jpeg"),

    /** {@code GIF87a} or {@code GIF89a}. */
    GIF("image/gif", "gif"),

    /** {@code RIFF} … {@code WEBP} — a RIFF container with the WebP form type. */
    WEBP("image/webp", "webp"),

    /** {@code %PDF-}. */
    PDF("application/pdf", "pdf"),

    /** ISO base media: a {@code ftyp} box at offset 4. */
    MP4("video/mp4", "mp4"),

    /**
     * A ZIP container that is <em>not</em> an OOXML document.
     *
     * <p>Reached only after {@link AttachmentSniffer} has looked inside and found
     * neither {@code word/} nor {@code xl/}, so a {@code .docx} renamed to
     * {@code .zip} is refused rather than quietly accepted as an archive.
     */
    ZIP("application/zip", "zip"),

    /** OOXML word processing — a ZIP carrying {@code word/document.xml}. */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),

    /** OOXML spreadsheet — a ZIP carrying {@code xl/workbook.xml}. */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),

    /** OLE2 compound file carrying a {@code WordDocument} stream. */
    DOC("application/msword", "doc"),

    /** OLE2 compound file carrying a {@code Workbook} (or {@code Book}) stream. */
    XLS("application/vnd.ms-excel", "xls"),

    /**
     * The residual tier: bytes that match no binary signature and decode as
     * text. Its MIME type is always {@code text/plain}, including for
     * {@code .csv} — see {@link #mediaTypeFor}.
     */
    TEXT("text/plain", "csv", "txt", "log");

    private final String mediaType;
    private final Set<String> extensions;

    AttachmentType(String mediaType, String... extensions) {
        this.mediaType = mediaType;
        this.extensions = Set.of(extensions);
    }

    /** Extension → family, built once. Lower-case keys; callers normalise. */
    private static final Map<String, AttachmentType> BY_EXTENSION = buildIndex();

    private static Map<String, AttachmentType> buildIndex() {
        Map<String, AttachmentType> index = new LinkedHashMap<>();
        for (AttachmentType type : values()) {
            for (String extension : type.extensions) {
                index.put(extension, type);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    String mediaType() {
        return mediaType;
    }

    Set<String> extensions() {
        return extensions;
    }

    /** Whether this family may legitimately be named by {@code extension}. */
    boolean allows(String extension) {
        return extensions.contains(extension);
    }

    /**
     * §4B.4's list as extensions, sorted, for a message a user can act on.
     *
     * <p>Sorted rather than declaration-ordered because the only reader is a
     * human being told why their file was refused, and "the list, alphabetically"
     * is scannable where "the list, grouped by internal format family" is not.
     */
    static List<String> allowedExtensions() {
        return BY_EXTENSION.keySet().stream().sorted().toList();
    }

    static boolean isAllowedExtension(String extension) {
        return BY_EXTENSION.containsKey(normalise(extension));
    }

    static Optional<AttachmentType> forExtension(String extension) {
        return Optional.ofNullable(BY_EXTENSION.get(normalise(extension)));
    }

    /**
     * The extension of {@code fileName}, lower-cased and without the dot.
     *
     * <p>Deliberately the same rule as the browser side's
     * {@code attachmentExtension} in {@code components/ui/attachments.ts}: a
     * leading dot is a name and not an extension ({@code .gitignore}), and a
     * trailing dot yields nothing. The two implementations must agree, because a
     * file the client accepted and the server names differently is refused after
     * the upload has already run.
     *
     * @return {@code ""} when the name carries no extension at all
     */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return normalise(name.substring(dot + 1));
    }

    /**
     * The content type to store and to serve, once family and extension agree.
     *
     * <p>Almost always the family's own type. The exception is {@code .csv},
     * which is stored as {@code text/csv} so a browser hands it to a spreadsheet
     * rather than rendering it — the family cannot distinguish it from a log
     * file, but the reconciled extension can, and by this point the extension has
     * been <em>corroborated</em> rather than trusted.
     *
     * <p>Nothing here ever returns {@code text/html} or an XML type, so a file
     * that decodes as text cannot be served back as a document the browser will
     * execute. That, plus the {@code Content-Disposition: attachment} the signed
     * URL carries, is what stops an uploaded {@code .txt} full of {@code <script>}
     * from being a stored XSS against whoever opens it.
     */
    String mediaTypeFor(String extension) {
        if (this == TEXT && "csv".equals(normalise(extension))) {
            return "text/csv";
        }
        return mediaType;
    }

    /** Whether a family can carry EXIF, XMP or IPTC metadata worth stripping (C-025, §4B.4). */
    boolean carriesMetadata() {
        return this == JPEG || this == PNG || this == WEBP;
    }

    private static String normalise(String extension) {
        return extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
    }

    static {
        // The families and the extension index must cover the same ground. A
        // family added without extensions would be unreachable from any upload
        // and would read, in the source, exactly like one that works.
        Arrays.stream(values()).forEach(type -> {
            if (type.extensions.isEmpty()) {
                throw new IllegalStateException(type + " has no extensions and can never be selected");
            }
        });
    }
}
