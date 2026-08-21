package com.edunext.edutrack.api.feature.tickets.attachments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * C-025 · EXIF stripped on upload — blueprint §4B.4, "client screenshots can
 * carry location data".
 *
 * <p>The threat is concrete and it is not hypothetical. A photograph of a broken
 * terminal, taken on a phone and attached to a ticket, carries GPS coordinates,
 * the device serial and the exact capture time in its EXIF block. That ticket is
 * then visible to everyone on the project and, if the attachment is marked client
 * visible, on the client portal. Nobody in that chain intended to publish where
 * the photographer was standing.
 *
 * <h2>Surgery on the container, not a re-encode</h2>
 *
 * <p>The obvious implementation is {@code ImageIO.read} then {@code ImageIO.write},
 * which drops metadata as a side effect of not carrying it across. It is rejected
 * for three reasons, in increasing order of importance:
 *
 * <ol>
 *   <li>It is lossy for JPEG. Re-encoding a screenshot of text at any quality
 *       setting visibly softens it, and the file the user attached is the
 *       evidence on the ticket.</li>
 *   <li>It rewrites every pixel of a 10 MB image to delete a few hundred bytes.</li>
 *   <li><b>It runs a full image decoder over hostile input.</b> Attachment
 *       upload is an unauthenticated-shaped surface — any of six roles can reach
 *       it — and image decoders are historically where the memory-safety bugs
 *       are. Parsing a chunk table and copying byte ranges reads no pixel data at
 *       all, so a malformed image cannot become anything worse than a rejected
 *       upload.</li>
 * </ol>
 *
 * <p>So each format is walked at the container level and the metadata segments
 * are dropped, byte-for-byte, leaving the compressed image data untouched. The
 * output of a strip is either a valid file of the same format or — if the walk
 * finds something it does not understand — <b>the input unchanged</b>, which is
 * the conservative direction: a file we could not parse has not been scanned into
 * an invalid one, and the AV scan and the private bucket are still in front of it.
 *
 * <h2>What is dropped, and what is deliberately kept</h2>
 *
 * <p>Only metadata that can carry personal or location data goes. Colour
 * management is kept — dropping an ICC profile makes a screenshot of a UI render
 * with visibly wrong colours, which is a change to the evidence on the ticket for
 * no privacy gain, since a colour profile describes a monitor and not a person.
 */
@Component
public class ImageMetadataStripper {

    private static final Logger log = LoggerFactory.getLogger(ImageMetadataStripper.class);

    /**
     * Strip whatever the family can carry.
     *
     * @return the cleaned bytes, or {@code content} itself when the format holds
     *         no metadata ({@link AttachmentType#carriesMetadata()}) or could not
     *         be parsed
     */
    public byte[] strip(AttachmentType type, byte[] content) {
        if (content == null || content.length == 0 || !type.carriesMetadata()) {
            return content;
        }
        try {
            return switch (type) {
                case JPEG -> stripJpeg(content);
                case PNG -> stripPng(content);
                case WEBP -> stripWebp(content);
                default -> content;
            };
        } catch (RuntimeException malformed) {
            // Reached only by a file that sniffed as one of the three above and
            // then failed to walk — a truncated upload, or a deliberately
            // malformed container. Logged rather than raised: the upload is not
            // wrong, and the file is about to be virus-scanned and stored
            // privately regardless. What must not happen is a half-rewritten
            // array reaching storage, which is why the original is returned.
            log.warn("attachment metadata strip failed for a {} of {} bytes; storing unchanged",
                    type, content.length, malformed);
            return content;
        }
    }

    // ── JPEG ────────────────────────────────────────────────────────────────

    /** {@code FF D8} start of image. */
    private static final int MARKER = 0xFF;
    private static final int SOI = 0xD8;
    /** Start of scan: everything from here to EOI is entropy-coded data, not segments. */
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP15 = 0xEF;
    private static final int COM = 0xFE;
    /** {@code FF D0}–{@code FF D7} and {@code FF 01} carry no length field. */
    private static final int RST0 = 0xD0;
    private static final int RST7 = 0xD7;
    private static final int TEM = 0x01;

    /**
     * Drop the APP segments that carry metadata, plus comments.
     *
     * <p>EXIF lives in APP1, XMP in a second APP1, IPTC and Photoshop resource
     * blocks in APP13 — and a phone will happily write location into any of
     * them. Rather than enumerate which APPn are dangerous, everything from APP1
     * to APP15 goes and the two harmless ones are named explicitly:
     *
     * <ul>
     *   <li><b>APP0 (JFIF) is kept</b> — pixel density and thumbnail geometry,
     *       no personal data, and some decoders are unhappy without it.</li>
     *   <li><b>APP2 (ICC profile) is kept</b> — see the class note on colour.</li>
     * </ul>
     *
     * <p>Allow-listing rather than deny-listing is the point: a metadata block in
     * an APPn nobody has thought about yet is dropped by default, which is the
     * direction a privacy control has to fail in.
     */
    private byte[] stripJpeg(byte[] content) {
        if (content.length < 4 || (content[0] & 0xFF) != MARKER || (content[1] & 0xFF) != SOI) {
            return content;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(content[0]);
        out.write(content[1]);

        int i = 2;
        while (i + 1 < content.length) {
            if ((content[i] & 0xFF) != MARKER) {
                // Not on a segment boundary. Fill bytes (FF FF) are legal padding
                // before a marker; anything else means the walk has lost its
                // place, and continuing would corrupt the file.
                return content;
            }
            int marker = content[i + 1] & 0xFF;

            if (marker == MARKER) {
                // Fill byte; the real marker is the next one along.
                out.write(content[i]);
                i++;
                continue;
            }
            if (marker == SOS) {
                // From SOS onward the stream is compressed scan data with no
                // segment structure to parse. Copy the rest verbatim and stop —
                // no metadata can hide past here in a well-formed file.
                out.write(content, i, content.length - i);
                return out.toByteArray();
            }
            if ((marker >= RST0 && marker <= RST7) || marker == TEM) {
                // Standalone markers: two bytes, no length field.
                out.write(content, i, 2);
                i += 2;
                continue;
            }
            if (i + 3 >= content.length) {
                return content;
            }

            int length = ((content[i + 2] & 0xFF) << 8) | (content[i + 3] & 0xFF);
            // The length field counts itself, so it can never be under two, and a
            // segment must fit inside the file.
            if (length < 2 || i + 2 + length > content.length) {
                return content;
            }

            if (!isDroppableJpegSegment(marker)) {
                out.write(content, i, 2 + length);
            }
            i += 2 + length;
        }

        return out.toByteArray();
    }

    /** APP1–APP15 and COM. APP0 (JFIF) and APP2 (ICC) survive — see {@link #stripJpeg}. */
    private boolean isDroppableJpegSegment(int marker) {
        if (marker == COM) {
            return true;
        }
        return marker > APP0 && marker <= APP15 && marker != (APP0 + 2);
    }

    // ── PNG ─────────────────────────────────────────────────────────────────

    private static final int PNG_SIGNATURE_LENGTH = 8;

    /**
     * Drop the ancillary chunks that carry metadata.
     *
     * <p>{@code eXIf} is EXIF proper. {@code tEXt}, {@code zTXt} and {@code iTXt}
     * are free-text key/value blocks and are where a screenshot tool writes its
     * own name, the window title and sometimes the user's — {@code iTXt} is also
     * where XMP, and therefore GPS, ends up. {@code tIME} is the last
     * modification time, which places a person at a keyboard at a moment.
     *
     * <p>Every other chunk is copied verbatim, CRC included, so nothing needs
     * recomputing: a PNG's chunk CRC covers the chunk alone and not the file, and
     * dropping whole chunks leaves the survivors' CRCs correct.
     */
    private byte[] stripPng(byte[] content) {
        if (content.length < PNG_SIGNATURE_LENGTH + 12) {
            return content;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        out.write(content, 0, PNG_SIGNATURE_LENGTH);

        int i = PNG_SIGNATURE_LENGTH;
        while (i + 8 <= content.length) {
            long dataLength = readUnsignedInt(content, i);
            // Length + type + data + CRC. Compared as a long, so a hostile
            // 0xFFFFFFFF length fails the bounds test instead of wrapping — the
            // whole reason `readUnsignedInt` does not return an int.
            long chunkLength = 12 + dataLength;
            if (i + chunkLength > content.length) {
                return content;
            }

            String type = new String(content, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!isDroppablePngChunk(type)) {
                out.write(content, i, (int) chunkLength);
            }
            i += (int) chunkLength;

            if ("IEND".equals(type)) {
                break;
            }
        }

        return out.toByteArray();
    }

    private boolean isDroppablePngChunk(String type) {
        return switch (type) {
            case "eXIf", "tEXt", "zTXt", "iTXt", "tIME" -> true;
            default -> false;
        };
    }

    // ── WebP ────────────────────────────────────────────────────────────────

    private static final int RIFF_HEADER_LENGTH = 12;
    /** In VP8X's flags byte: bit 3 says an EXIF chunk follows, bit 2 an XMP one. */
    private static final int VP8X_EXIF_FLAG = 0x08;
    private static final int VP8X_XMP_FLAG = 0x04;

    /**
     * Drop the {@code EXIF} and {@code XMP } chunks, and unset the flags that
     * announce them.
     *
     * <p>The flags are the part that is easy to get wrong. An extended WebP
     * begins with a {@code VP8X} chunk whose flags byte declares which optional
     * chunks the file contains; removing the chunks without clearing the bits
     * leaves a file that <em>says</em> it carries EXIF and does not, which strict
     * decoders reject outright. So the byte is rewritten, and the RIFF size field
     * — which counts every byte after itself — is recomputed to match the shorter
     * file. A stale RIFF size is the other half of the same bug: readers that
     * trust it would read past the end.
     */
    private byte[] stripWebp(byte[] content) {
        if (content.length < RIFF_HEADER_LENGTH + 8) {
            return content;
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream(content.length);

        int i = RIFF_HEADER_LENGTH;
        boolean droppedAny = false;
        while (i + 8 <= content.length) {
            String type = new String(content, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long dataLength = readUnsignedIntLittleEndian(content, i + 4);
            // RIFF pads every odd-sized chunk to an even boundary, and the pad
            // byte is not counted in the size field. Missing this shifts every
            // subsequent chunk by one and silently corrupts the file.
            long chunkLength = 8 + dataLength + (dataLength % 2);
            if (i + chunkLength > content.length) {
                return content;
            }

            if ("EXIF".equals(type) || "XMP ".equals(type)) {
                droppedAny = true;
            } else if ("VP8X".equals(type) && dataLength >= 1) {
                byte[] chunk = new byte[(int) chunkLength];
                System.arraycopy(content, i, chunk, 0, (int) chunkLength);
                chunk[8] = (byte) (chunk[8] & ~(VP8X_EXIF_FLAG | VP8X_XMP_FLAG));
                body.write(chunk, 0, chunk.length);
            } else {
                body.write(content, i, (int) chunkLength);
            }
            i += (int) chunkLength;
        }

        if (!droppedAny) {
            // Nothing was removed, so the original is already correct — and
            // returning it avoids rewriting a VP8X flags byte that was clear
            // anyway, which would produce a byte-different file for no reason.
            return content;
        }

        byte[] rebuilt = new byte[RIFF_HEADER_LENGTH + body.size()];
        System.arraycopy(content, 0, rebuilt, 0, RIFF_HEADER_LENGTH);
        System.arraycopy(body.toByteArray(), 0, rebuilt, RIFF_HEADER_LENGTH, body.size());
        // The RIFF size counts everything after the size field itself: the four
        // bytes of "WEBP" plus the chunks.
        writeUnsignedIntLittleEndian(rebuilt, 4, 4L + body.size());
        return rebuilt;
    }

    // ── byte helpers ────────────────────────────────────────────────────────

    /**
     * PNG is big-endian. Returned as a {@code long} and range-checked by the
     * caller: a chunk length is an unsigned 32-bit field, and reading it into an
     * {@code int} turns a hostile 0xFFFFFFFF into -1, which then passes a naive
     * "fits in the array" test by being negative.
     */
    private static long readUnsignedInt(byte[] content, int offset) {
        return ((long) (content[offset] & 0xFF) << 24)
                | ((long) (content[offset + 1] & 0xFF) << 16)
                | ((long) (content[offset + 2] & 0xFF) << 8)
                | (content[offset + 3] & 0xFF);
    }

    /** RIFF, and therefore WebP, is little-endian. Same unsigned reasoning. */
    private static long readUnsignedIntLittleEndian(byte[] content, int offset) {
        return (content[offset] & 0xFFL)
                | ((content[offset + 1] & 0xFFL) << 8)
                | ((content[offset + 2] & 0xFFL) << 16)
                | ((content[offset + 3] & 0xFFL) << 24);
    }

    private static void writeUnsignedIntLittleEndian(byte[] target, int offset, long value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
