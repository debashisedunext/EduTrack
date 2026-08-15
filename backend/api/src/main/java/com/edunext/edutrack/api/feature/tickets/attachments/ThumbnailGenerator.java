package com.edunext.edutrack.api.feature.tickets.attachments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * C-026 · the reduction itself — blueprint §4B.4's thumbnails.
 *
 * <p>Pure and Spring-free apart from the properties: bytes in, smaller bytes out.
 * It reads nothing from the database, writes nothing to storage and has no
 * opinion about when it should run — {@link ThumbnailTask} owns that, and the
 * answer is "after the virus scan has said CLEAN", which is the whole reason this
 * class is safe to exist at all.
 *
 * <h2>This is the decoder C-025 refused to run, and what changed</h2>
 *
 * <p>{@link ImageMetadataStripper}'s javadoc argues at length against
 * {@code ImageIO.read} and it is still right: EXIF stripping runs on the
 * <em>request</em> thread, over bytes that have been sniffed and nothing more, on
 * a surface all six roles can reach. Walking a chunk table decodes no pixels, so
 * a malformed image cannot become worse than a rejected upload.
 *
 * <p>A thumbnail cannot avoid decoding. So the three things that made a decoder
 * unacceptable there are removed here rather than argued away:
 *
 * <ol>
 *   <li><b>It is not on a request thread.</b> This runs on the scan pool, after
 *       the response has gone. A decoder that hangs or thrashes costs one of two
 *       background threads, not the caller's connection.</li>
 *   <li><b>The bytes have been scanned.</b> An attacker reaching this code has
 *       already passed the extension allow-list, the sniffer, the metadata strip
 *       <em>and</em> clamd. That is not a guarantee against a novel {@code ImageIO}
 *       bug, but it is a materially different exposure from "anything a browser
 *       will upload".</li>
 *   <li><b>The bomb is checked before it is opened.</b> Dimensions come from the
 *       header via {@link ImageReader#getWidth}, which decodes nothing, and a
 *       source above {@code maxSourcePixels} is refused with no allocation at
 *       all. What is then decoded is <em>subsampled</em>, so even a legitimate
 *       50 MP photograph never materialises in memory at full size.</li>
 * </ol>
 *
 * <p>Every failure is {@link Optional#empty()} and never an exception. A missing
 * thumbnail is a cosmetic degradation the client already handles; an exception
 * escaping here would abort {@link ThumbnailTask}'s transaction, and the one thing
 * that must never happen is a broken image costing an attachment its CLEAN
 * verdict.
 *
 * <h2>PNG out, whatever went in</h2>
 *
 * <p>One output type, so nothing has to store what a thumbnail is: the presigner
 * is told {@code image/png} from a constant, and there is no second MIME column to
 * drift out of step with the bytes.
 *
 * <p>PNG rather than JPEG because of what this product actually attaches. §4B.4's
 * driving case, and C-024's, is a support agent pasting a screenshot — a picture
 * of <em>text</em>, which is exactly the content JPEG's chroma subsampling
 * smears. A photograph would compress better as JPEG and does not appear on
 * tickets often enough to pay for a second format. Alpha is preserved only when
 * the source had it, since a 24-bit PNG is appreciably smaller than a 32-bit one
 * and a JPEG source can never need the fourth channel.
 */
@Component
class ThumbnailGenerator {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailGenerator.class);

    /** What every thumbnail is, regardless of what it was made from. */
    static final String MEDIA_TYPE = "image/png";

    /**
     * The media types this class can actually decode.
     *
     * <p>Deliberately <b>narrower</b> than {@link AttachmentType}'s image
     * families: {@code image/webp} is on §4B.4's allow-list and is missing here
     * because the JDK ships no WebP {@code ImageReader} and adding one means a
     * native library on the server for a format that arrives rarely. A WebP
     * attachment therefore gets no reduction and the client falls back to the
     * full file — which is correct behaviour, not a broken state, and is the same
     * path an image too small to be worth reducing takes.
     *
     * <p>{@code ThumbnailGeneratorTest} asserts every type named here is one an
     * {@link AttachmentType} can actually produce, so this set cannot drift into
     * claiming something the upload path would never store.
     */
    static final Set<String> DECODABLE = Set.of("image/png", "image/jpeg", "image/gif");

    private final AttachmentProperties.Thumbnail settings;

    ThumbnailGenerator(AttachmentProperties properties) {
        this.settings = properties.thumbnail();
    }

    /**
     * Whether a reduction would even be attempted for this media type.
     *
     * <p>Exposed so the caller can skip a pointless storage read for the PDFs,
     * spreadsheets and logs that are most of what gets attached.
     */
    boolean supports(String mediaType) {
        return settings.enabled() && mediaType != null && DECODABLE.contains(mediaType.toLowerCase(Locale.ROOT));
    }

    /**
     * A PNG reduction of {@code content}, or nothing.
     *
     * <p>Empty is an ordinary outcome with several ordinary causes — an
     * unsupported type, an image already smaller than the target, a source above
     * the pixel ceiling, a truncated or malformed file, or a decoder that threw.
     * None of them is worth distinguishing to a caller whose only two branches are
     * "store a key" and "leave the column null".
     */
    Optional<byte[]> generate(String mediaType, byte[] content) {
        if (!supports(mediaType) || content == null || content.length == 0) {
            return Optional.empty();
        }

        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            // MemoryCacheImageInputStream, not ImageIO.createImageInputStream:
            // the latter honours ImageIO's global disk-cache setting and may spill
            // the file to a temp directory. Naming the memory-backed stream keeps
            // an attachment's bytes off the server's filesystem without this
            // feature mutating a JVM-wide static that three other streams share.
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                log.debug("no ImageIO reader for {}; leaving the attachment without a thumbnail", mediaType);
                return Optional.empty();
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reduce(reader);
            } finally {
                // Readers hold native decoder state. Without this a burst of
                // uploads leaks it until the next GC decides to care.
                reader.dispose();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError failed) {
            // OutOfMemoryError is caught on purpose and it is not superstition:
            // it is the documented failure mode of a decompression bomb that got
            // past the pixel ceiling — a format whose header understates what it
            // expands to. Letting it escape would kill a pool thread and, worse,
            // roll back the transaction that was about to record a CLEAN verdict.
            log.warn("could not build a thumbnail for a {} attachment; it will render without one",
                    mediaType, failed);
            return Optional.empty();
        }
    }

    /**
     * Header first, pixels second — the order is the security property.
     */
    private Optional<byte[]> reduce(ImageReader reader) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }

        // Checked in long arithmetic. 40000 * 40000 overflows an int to a
        // negative number, which would sail past a naive `> maxSourcePixels`.
        long pixels = (long) width * (long) height;
        if (pixels > settings.maxSourcePixels()) {
            log.warn("refusing to decode a {}×{} image ({} pixels, ceiling {}); no thumbnail will be stored",
                    width, height, pixels, settings.maxSourcePixels());
            return Optional.empty();
        }

        int edge = Math.max(1, settings.maxEdge());
        if (width <= edge && height <= edge) {
            // Already thumbnail-sized. Storing a copy would double the object
            // count for no benefit, and the client renders the original in the
            // strip when there is no reduction — see AttachmentGallery.
            return Optional.empty();
        }

        ImageReadParam param = reader.getDefaultReadParam();
        int step = subsamplingStep(width, height, edge);
        if (step > 1) {
            // The decoder skips rows and columns as it reads, so the full-size
            // raster is never allocated. This is the difference between a 50 MP
            // source costing 200 MB of heap and costing 2 MB.
            param.setSourceSubsampling(step, step, 0, 0);
        }

        BufferedImage decoded = reader.read(0, param);
        if (decoded == null) {
            return Optional.empty();
        }
        return Optional.of(toPng(scale(decoded, edge)));
    }

    /**
     * How much of the image to skip while decoding.
     *
     * <p>Doubled until one more halving would take the long side below the target,
     * so what comes out of the decoder is between one and two times the size the
     * thumbnail needs — enough resolution for the final downscale to look right,
     * and no more heap than that.
     *
     * <p>Clamped to the <em>short</em> side. A 40,000 × 3 banner would otherwise
     * choose a step of 64 and ask the decoder for an image three-sixty-fourths of
     * a pixel tall, which is zero, which throws.
     */
    private static int subsamplingStep(int width, int height, int edge) {
        int step = 1;
        int longest = Math.max(width, height);
        while (longest / (step * 2) >= edge) {
            step *= 2;
        }
        return Math.max(1, Math.min(step, Math.min(width, height)));
    }

    /**
     * Down to the target box, aspect preserved, never up.
     *
     * <p>Bilinear rather than nearest-neighbour: a UI screenshot reduced by
     * point-sampling drops whole rows of text and produces the aliased mess that
     * makes people click through to the full image every time — which defeats the
     * point of a strip.
     */
    private static BufferedImage scale(BufferedImage source, int edge) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min((double) edge / width, (double) edge / height);
        if (factor >= 1) {
            // Subsampling already took it at or below the target. Copying it into
            // a known type is still worth doing — the decoder's own type may be
            // indexed or grayscale, and the PNG writer handles a plain
            // ARGB/RGB raster predictably.
            factor = 1;
        }

        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));

        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage target = new BufferedImage(
                targetWidth, targetHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Same reasoning as the input side: an explicitly memory-backed stream,
        // so encoding never touches the filesystem.
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", stream)) {
                throw new IOException("no PNG writer is registered in this JVM");
            }
        }
        return out.toByteArray();
    }
}
