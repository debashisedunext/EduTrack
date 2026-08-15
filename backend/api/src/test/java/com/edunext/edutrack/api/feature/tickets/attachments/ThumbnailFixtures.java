package com.edunext.edutrack.api.feature.tickets.attachments;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * C-026 · images that actually decode.
 *
 * <p>Separate from {@link AttachmentFixtures}, and the separation is the point.
 * Those fixtures are assembled byte by byte because their subjects — the sniffer
 * and the metadata stripper — walk headers and chunk tables and never look at a
 * pixel, so a structurally valid container proves everything and a real image
 * would prove nothing extra. {@link ThumbnailGenerator} is the opposite: it is
 * the one class in this feature that runs a decoder, so its fixtures have to be
 * decodable, which in practice means letting {@code ImageIO} write them.
 *
 * <p>Sizes are passed in rather than fixed, because almost every property worth
 * asserting here is about dimensions — the pixel ceiling, the never-upscale rule,
 * aspect preservation, and the subsampling step that keeps a large source from
 * being materialised at full size.
 */
final class ThumbnailFixtures {

    private ThumbnailFixtures() {
    }

    /** An opaque PNG of exactly this size, with enough detail to survive scaling. */
    static byte[] png(int width, int height) {
        return encode(paint(width, height, false), "png");
    }

    /** A PNG with a genuinely transparent region, for the alpha-preservation test. */
    static byte[] pngWithAlpha(int width, int height) {
        return encode(paint(width, height, true), "png");
    }

    static byte[] jpeg(int width, int height) {
        // JPEG cannot carry alpha; painting it opaque keeps the writer from
        // silently producing a four-channel file some decoders read as CMYK.
        return encode(paint(width, height, false), "jpg");
    }

    static byte[] gif(int width, int height) {
        return encode(paint(width, height, false), "gif");
    }

    /**
     * A PNG that <em>claims</em> a huge canvas, for the decompression-bomb test.
     *
     * <p>Genuinely allocating 40,000 × 40,000 to build a fixture would cost the
     * several gigabytes the check exists to prevent — the test would reproduce
     * the attack in order to prove it is stopped. Instead the IHDR's width and
     * height fields are overwritten in place: the header, which is all
     * {@link ThumbnailGenerator} reads before deciding, says the image is
     * enormous, and the reader never gets far enough to notice the pixel data
     * disagrees.
     *
     * <p>The IHDR CRC is recomputed, so this is a well-formed PNG right up to the
     * point of decode. A fixture with a broken checksum would be refused for the
     * wrong reason and the test would pass without exercising the ceiling at all.
     */
    static byte[] pngClaiming(int width, int height) {
        byte[] source = png(8, 8);
        // 8-byte signature, then IHDR: 4 length, 4 type, then width and height.
        int ihdrData = 8 + 4 + 4;
        writeInt(source, ihdrData, width);
        writeInt(source, ihdrData + 4, height);

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        // The CRC covers the chunk type and its data, but not the length field.
        crc.update(source, 8 + 4, 4 + 13);
        writeInt(source, ihdrData + 13, (int) crc.getValue());
        return source;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /**
     * Something with edges in it.
     *
     * <p>A flat fill would scale to a flat fill and every assertion about output
     * size or interpolation would hold for the wrong reason. Diagonal bands give
     * the encoder real work and give a bilinear downscale something to blend.
     */
    private static BufferedImage paint(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(
                width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            if (!alpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setColor(new Color(0x1F, 0x6F, 0xEB));
            for (int x = -height; x < width; x += 8) {
                graphics.drawLine(x, 0, x + height, height);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, format, stream)) {
                throw new IllegalStateException("this JVM has no " + format + " writer");
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }
}
