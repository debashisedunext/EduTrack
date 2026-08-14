package com.edunext.edutrack.api.feature.tickets.attachments;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * C-025 · what the bytes actually are — blueprint §4B.4's "MIME sniffing (not
 * extension alone)".
 *
 * <p>This class never sees the file name and takes no argument that could carry
 * one. That is deliberate and it is the whole design: a sniffer that could read
 * the extension would eventually be <em>helped</em> by it, and the one thing
 * §4B.4 asks for is a second opinion formed independently of the first.
 * {@link AttachmentTypePolicy} is where the two opinions meet.
 *
 * <h2>Three tiers, in order</h2>
 *
 * <ol>
 *   <li><b>Leading-byte signatures</b> — PNG, JPEG, GIF, WebP, PDF, MP4. A fixed
 *       prefix decides the family outright.</li>
 *   <li><b>Containers that must be opened</b> — ZIP and OLE2. {@code PK\03\04}
 *       is the first four bytes of every {@code .docx}, {@code .xlsx} and
 *       {@code .zip} on §4B.4's list, and the eight-byte OLE2 header is shared by
 *       {@code .doc} and {@code .xls}. Neither can be resolved without reading
 *       the container's directory, so both are.</li>
 *   <li><b>Text</b> — the residual. Reached only when nothing above matched, and
 *       decided by "decodes as text and holds no NUL", because {@code .log} has
 *       no signature to look for. See {@link AttachmentType#TEXT}.</li>
 * </ol>
 *
 * <h2>Why not Apache POI's {@code FileMagic}, which is already on the classpath</h2>
 *
 * <p>It covers half the list and stops exactly where the interesting question
 * starts. It has no PNG, no WebP and no MP4, and for the formats it does detect
 * it answers {@code OLE2} and {@code OOXML} — the containers — without saying
 * which document is inside, which is the discrimination §4B.4 needs in order to
 * tell a {@code .doc} from an {@code .xls} renamed by hand. Using it would mean
 * one sniffer for six formats and a hand-written one for the other six, and two
 * sniffers disagree eventually. POI is still used, for the part it is genuinely
 * better at: {@link POIFSFileSystem} parses the OLE2 directory below, which is a
 * sector-allocation-table walk nobody should reimplement.
 *
 * <h2>Bytes, not a stream</h2>
 *
 * <p>Every caller has the whole upload in memory already — {@code max-file-size}
 * is 10 MB and the EXIF stripper rewrites the array anyway — so taking a
 * {@code byte[]} avoids a mark/reset dance and the class of bug where the second
 * reader finds the stream drained.
 */
@Component
class AttachmentSniffer {

    /**
     * How much of the file the text tier inspects.
     *
     * <p>A whole 10 MB log is a pointless scan when the question is "is this
     * really a binary wearing a {@code .txt}", and every format that lies about
     * itself does so in its header. 8 KiB is the same window {@code file(1)} and
     * Git use to decide the same question.
     */
    private static final int TEXT_SAMPLE_BYTES = 8192;

    /** {@code PK\03\04}, and the empty and spanned archive headers ZIP also allows. */
    private static final int[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final int[] ZIP_EMPTY_HEADER = {0x50, 0x4B, 0x05, 0x06};
    private static final int[] ZIP_SPANNED_HEADER = {0x50, 0x4B, 0x07, 0x08};

    /** OLE2 / Compound File Binary Format. Shared by every legacy Office document. */
    private static final int[] OLE2_HEADER = {0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1};

    /**
     * @return the family the bytes belong to, or empty when they belong to none
     *         this application accepts. Empty is a refusal, never a fallback —
     *         a caller must not treat "could not tell" as "probably fine".
     */
    Optional<AttachmentType> sniff(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }

        // ── tier 1 · fixed signatures ────────────────────────────────────────
        if (startsWith(content, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(AttachmentType.PNG);
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(AttachmentType.JPEG);
        }
        if (startsWith(content, 0, 'G', 'I', 'F', '8', '7', 'a')
                || startsWith(content, 0, 'G', 'I', 'F', '8', '9', 'a')) {
            return Optional.of(AttachmentType.GIF);
        }
        // RIFF is a family of formats; only the form type at offset 8 says WebP.
        // An AVI is also RIFF and is not on §4B.4's list, so the second check is
        // load-bearing rather than belt-and-braces.
        if (startsWith(content, 0, 'R', 'I', 'F', 'F') && startsWith(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(AttachmentType.WEBP);
        }
        if (startsWith(content, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(AttachmentType.PDF);
        }
        // ISO base media: a size field, then the `ftyp` box type at offset 4.
        // The brand that follows distinguishes mp4 from mov/3gp/heic, and is
        // deliberately not checked — §4B.4 asks for "video (mp4)" and the brands
        // in practice are a long tail (isom, mp42, avc1, dash, …) that a strict
        // list refuses real files over.
        if (startsWith(content, 4, 'f', 't', 'y', 'p')) {
            return Optional.of(AttachmentType.MP4);
        }

        // ── tier 2 · containers ──────────────────────────────────────────────
        if (startsWith(content, 0, ZIP_LOCAL_HEADER)
                || startsWith(content, 0, ZIP_EMPTY_HEADER)
                || startsWith(content, 0, ZIP_SPANNED_HEADER)) {
            return Optional.of(insideZip(content));
        }
        if (startsWith(content, 0, OLE2_HEADER)) {
            return insideOle2(content);
        }

        // ── tier 3 · text, or nothing ────────────────────────────────────────
        return looksLikeText(content) ? Optional.of(AttachmentType.TEXT) : Optional.empty();
    }

    /**
     * Which OOXML document a ZIP holds, if any.
     *
     * <p>Entry <em>names</em> only — nothing is inflated. That keeps the cost
     * proportional to the archive's directory rather than to its contents and
     * takes decompression bombs off the table entirely: a 10 MB zip that expands
     * to 40 GB is read here as a list of strings.
     *
     * <p>An archive we cannot open at all comes back {@link AttachmentType#ZIP}
     * rather than empty. That is not leniency — the extension check still has to
     * agree, so an unreadable archive is accepted only when the user called it a
     * {@code .zip}, which is exactly what a corrupt or encrypted archive is.
     */
    private AttachmentType insideZip(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                // The two marker paths OOXML mandates. `[Content_Types].xml` is
                // present in both and so cannot discriminate; these can.
                if (name.startsWith("word/")) {
                    return AttachmentType.DOCX;
                }
                if (name.startsWith("xl/")) {
                    return AttachmentType.XLSX;
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            return AttachmentType.ZIP;
        }
        return AttachmentType.ZIP;
    }

    /**
     * Which legacy Office document an OLE2 container holds.
     *
     * <p>The root directory names it: Word writes a {@code WordDocument} stream,
     * Excel a {@code Workbook} (or {@code Book}, pre-97). An OLE2 file holding
     * neither is refused rather than guessed at — PowerPoint, Outlook messages
     * and Installer packages are all this format and none of them is on §4B.4's
     * list, so "OLE2, therefore a document" is precisely the inference that makes
     * the extension check worthless.
     */
    private Optional<AttachmentType> insideOle2(byte[] content) {
        try (POIFSFileSystem ole2 = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            var root = ole2.getRoot();
            if (root.hasEntryCaseInsensitive("WordDocument")) {
                return Optional.of(AttachmentType.DOC);
            }
            if (root.hasEntryCaseInsensitive("Workbook") || root.hasEntryCaseInsensitive("Book")) {
                return Optional.of(AttachmentType.XLS);
            }
            return Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            // POI raises unchecked exceptions for a malformed header. A container
            // we cannot open is a container we cannot vouch for.
            return Optional.empty();
        }
    }

    /**
     * Whether the leading bytes are text rather than a binary in disguise.
     *
     * <p>One rule, stated as control characters: a text file holds no NUL, no
     * DEL, and none of the C0 codes except tab, newline, carriage return and
     * form feed. Every executable format violates it within the first few bytes,
     * and the NUL is usually the very first one.
     *
     * <p><b>Deliberately not "decodes as UTF-8".</b> That was the first draft and
     * it is wrong in both directions. It admits a BEL and an ESC, which are
     * perfectly valid UTF-8 and are not in any log file — the sniffer would then
     * pass a terminal-escape payload as {@code .txt}. And it rejects the
     * Windows-1252 logs that Windows-hosted services still write, which is the
     * single file type §4B.4 put {@code log} on the list to accommodate. Bytes
     * above 0x7F are therefore left alone: they are UTF-8 continuation bytes or
     * they are Latin-1 accents, and this class cannot tell which without
     * guessing at an encoding it was never told.
     */
    private boolean looksLikeText(byte[] content) {
        int length = Math.min(content.length, TEXT_SAMPLE_BYTES);
        for (int i = 0; i < length; i++) {
            int b = content[i] & 0xFF;
            if (b == 0x7F || (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != 0x0C)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param expected unsigned byte values or {@code char} literals — both widen
     *                 to {@code int}, which is what lets the signatures above be
     *                 written as {@code 'P', 'N', 'G'} and stay readable
     */
    private static boolean startsWith(byte[] content, int offset, int... expected) {
        if (offset < 0 || content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((content[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
