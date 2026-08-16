package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * C-026 · the reduction — blueprint §4B.4's thumbnails.
 *
 * <p>Two groups of assertion here, and they are doing different jobs. The
 * dimension and format tests describe the feature. The tests under
 * <i>hostile input</i> describe why it is <em>safe</em> for this class to exist
 * at all: it is the only place in the attachment pipeline that runs an image
 * decoder, and {@link ImageMetadataStripper}'s javadoc spends four paragraphs
 * explaining why it refused to. Those tests are the difference between the two.
 */
class ThumbnailGeneratorTest {

    private static final int EDGE = 320;

    private static ThumbnailGenerator generator() {
        return generator(true, EDGE, 50_000_000L);
    }

    private static ThumbnailGenerator generator(boolean enabled, int maxEdge, long maxSourcePixels) {
        return new ThumbnailGenerator(new AttachmentProperties(
                Duration.ofMinutes(5), 10L * 1024 * 1024, 50L * 1024 * 1024, 20, Duration.ofMinutes(15),
                new AttachmentProperties.Scan(false, "localhost", 3310, Duration.ofSeconds(30), false),
                new AttachmentProperties.Thumbnail(enabled, maxEdge, maxSourcePixels)));
    }

    @Nested
    @DisplayName("what comes out")
    class Output {

        @Test
        void aLargeImageIsReducedToTheTargetBox() {
            byte[] thumbnail = generator().generate("image/png", ThumbnailFixtures.png(1600, 1200)).orElseThrow();

            BufferedImage decoded = decode(thumbnail);
            assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(EDGE);
            assertThat(thumbnail.length).isLessThan(200 * 1024);
        }

        @Test
        void theAspectRatioIsPreserved() {
            // A squashed thumbnail is worse than none: the gallery is how someone
            // recognises the screenshot they are looking for.
            BufferedImage decoded =
                    decode(generator().generate("image/png", ThumbnailFixtures.png(1000, 500)).orElseThrow());

            assertThat(decoded.getWidth()).isEqualTo(320);
            assertThat(decoded.getHeight()).isEqualTo(160);
        }

        @Test
        void aTallImageIsBoundedByItsHeightRatherThanItsWidth() {
            BufferedImage decoded =
                    decode(generator().generate("image/png", ThumbnailFixtures.png(500, 1000)).orElseThrow());

            assertThat(decoded.getWidth()).isEqualTo(160);
            assertThat(decoded.getHeight()).isEqualTo(320);
        }

        @Test
        void everythingComesOutAsPngWhateverWentIn() {
            // One output format, so nothing has to store what a thumbnail is —
            // the presigner is told image/png from a constant and there is no
            // second MIME column to drift out of step with the bytes.
            for (byte[] source : new byte[][]{
                    ThumbnailFixtures.png(800, 600),
                    ThumbnailFixtures.jpeg(800, 600),
                    ThumbnailFixtures.gif(800, 600)}) {
                byte[] thumbnail = generator()
                        .generate(source == null ? null : mediaTypeOf(source), source)
                        .orElseThrow();
                assertThat(Arrays.copyOf(thumbnail, 8)).isEqualTo(AttachmentFixtures.pngSignature());
            }
        }

        @Test
        void aJpegSourceLosesNoDetailToChromaSubsamplingOnTheWayBackOut() {
            // The reason the output is PNG at all: §4B.4's driving case is a
            // pasted screenshot, which is a picture of text, which is exactly
            // what JPEG smears. This asserts the format rather than the pixels —
            // the pixel claim is unmeasurable, the format claim is the mechanism.
            byte[] thumbnail = generator().generate("image/jpeg", ThumbnailFixtures.jpeg(900, 700)).orElseThrow();

            assertThat(Arrays.copyOf(thumbnail, 8)).isEqualTo(AttachmentFixtures.pngSignature());
            assertThat(decode(thumbnail).getWidth()).isEqualTo(EDGE);
        }

        @Test
        void transparencySurvives() {
            // A screenshot with a transparent corner, flattened onto black, is a
            // different picture. Alpha is kept when the source had it — and only
            // then, since a 24-bit PNG is appreciably smaller.
            BufferedImage decoded =
                    decode(generator().generate("image/png", ThumbnailFixtures.pngWithAlpha(800, 800)).orElseThrow());

            assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        }

        @Test
        void anOpaqueSourceDoesNotGainAnAlphaChannelItDoesNotNeed() {
            BufferedImage decoded =
                    decode(generator().generate("image/jpeg", ThumbnailFixtures.jpeg(800, 800)).orElseThrow());

            assertThat(decoded.getColorModel().hasAlpha()).isFalse();
        }
    }

    @Nested
    @DisplayName("nothing is produced, and that is an ordinary outcome")
    class NoThumbnail {

        @Test
        void anImageAlreadySmallerThanTheTargetIsNotCopied() {
            // Storing a reduction that is not a reduction doubles the object
            // count for no benefit. The client renders the original in the strip
            // when thumbnailUrl is null — see AttachmentGallery.
            assertThat(generator().generate("image/png", ThumbnailFixtures.png(200, 150))).isEmpty();
        }

        @Test
        void anImageExactlyTheTargetSizeIsNotCopiedEither() {
            assertThat(generator().generate("image/png", ThumbnailFixtures.png(EDGE, EDGE))).isEmpty();
        }

        @Test
        void webpGetsNoneBecauseTheJvmShipsNoReaderForIt() {
            // On §4B.4's allow-list, so this is a real gap and not a typo. Adding
            // a reader means a native library on the server for a format that
            // arrives rarely; the client falls back to the full image instead.
            assertThat(generator().supports("image/webp")).isFalse();
            assertThat(generator().generate("image/webp", AttachmentFixtures.webpWithExif())).isEmpty();
        }

        @Test
        void documentsVideoAndTextAreNotEvenAttempted() {
            ThumbnailGenerator generator = generator();
            assertThat(generator.generate("application/pdf", AttachmentFixtures.pdf())).isEmpty();
            assertThat(generator.generate("video/mp4", AttachmentFixtures.mp4())).isEmpty();
            assertThat(generator.generate("text/plain", AttachmentFixtures.text("a log line"))).isEmpty();
            assertThat(generator.generate("application/zip", AttachmentFixtures.plainZip())).isEmpty();
        }

        @Test
        void disablingItTurnsEveryThumbnailOffWithoutTurningAnythingElseOff() {
            // An operator's escape hatch for a broken ImageIO, not a feature
            // flag. Nothing downstream branches on it — the column stays null,
            // which is a state the client renders every day anyway.
            ThumbnailGenerator off = generator(false, EDGE, 50_000_000L);

            assertThat(off.supports("image/png")).isFalse();
            assertThat(off.generate("image/png", ThumbnailFixtures.png(1600, 1200))).isEmpty();
        }

        @Test
        void aNullOrEmptyBodyIsNotAnError() {
            assertThat(generator().generate("image/png", null)).isEmpty();
            assertThat(generator().generate("image/png", new byte[0])).isEmpty();
            assertThat(generator().generate(null, ThumbnailFixtures.png(800, 600))).isEmpty();
        }
    }

    @Nested
    @DisplayName("hostile input — the reason C-025 would not run a decoder and this can")
    class HostileInput {

        @Test
        @Timeout(10)
        void aDecompressionBombIsRefusedFromItsHeaderWithoutBeingDecoded() {
            // The attack §4B.4's 10 MB cap cannot see: a PNG of a few kilobytes
            // whose IHDR announces 40,000 × 40,000 — 1.6 billion pixels, several
            // gigabytes of heap the moment anything reads it. The dimensions are
            // checked before a pixel is decoded, so this returns in microseconds
            // and allocates nothing. The timeout is the assertion that matters:
            // a version that decoded first would hang or die here.
            assertThat(generator().generate("image/png", ThumbnailFixtures.pngClaiming(40_000, 40_000))).isEmpty();
        }

        @Test
        void theCeilingIsCheckedInLongArithmeticSoItCannotBeOverflowedPastIt() {
            // 65536 × 65536 is exactly 2^32, which is 0 as an int. A check
            // written in int arithmetic would compute a non-positive product and
            // wave the largest possible bomb straight through.
            assertThat(generator().generate("image/png", ThumbnailFixtures.pngClaiming(65_536, 65_536))).isEmpty();
        }

        @Test
        void anOrdinarilyLargePhotographIsStillProcessed() {
            // The ceiling must not be so eager that it refuses real cameras. 12 MP
            // is built here rather than something nearer the 50 MP limit because a
            // fixture that large costs 200 MB to *paint*; the boundary itself is
            // covered by the two claimed-dimension tests above, which cost nothing.
            assertThat(generator().generate("image/png", ThumbnailFixtures.png(4000, 3000))).isPresent();
        }

        @Test
        void aSourceOverTheConfiguredCeilingIsRefusedAtWhateverTheCeilingIsSetTo() {
            // The same image, either side of a ceiling moved under it — so the
            // check is demonstrably reading the property rather than a constant.
            byte[] image = ThumbnailFixtures.png(1600, 1200);

            assertThat(generator(true, EDGE, 5_000_000L).generate("image/png", image)).isPresent();
            assertThat(generator(true, EDGE, 1_000_000L).generate("image/png", image)).isEmpty();
        }

        @Test
        void anExtremeAspectRatioDoesNotAskTheDecoderForZeroRows() {
            // The subsampling step is chosen from the long side. Unclamped, a
            // 4000 × 3 banner picks a step of 8 and asks for an image three
            // eighths of a pixel tall, which is zero, which throws inside the
            // reader rather than returning empty.
            assertThatCode(() -> generator().generate("image/png", ThumbnailFixtures.png(4000, 3)))
                    .doesNotThrowAnyException();

            BufferedImage decoded =
                    decode(generator().generate("image/png", ThumbnailFixtures.png(4000, 3)).orElseThrow());
            assertThat(decoded.getWidth()).isEqualTo(EDGE);
            assertThat(decoded.getHeight()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void truncatedBytesProduceNothingRatherThanAnException() {
            // An exception escaping this class would abort ThumbnailTask's
            // transaction, and the one thing that must never happen is a broken
            // image costing an attachment the CLEAN verdict it had already been
            // given.
            byte[] whole = ThumbnailFixtures.png(1600, 1200);
            byte[] half = Arrays.copyOf(whole, whole.length / 2);

            assertThatCode(() -> generator().generate("image/png", half)).doesNotThrowAnyException();
            assertThat(generator().generate("image/png", half)).isEmpty();
        }

        @Test
        void bytesThatAreNotAnImageAtAllProduceNothing() {
            // Unreachable through the upload path — AttachmentTypePolicy would
            // have refused this long before — but the media type here comes off a
            // database row, and this class must not depend on that row being
            // right about its own bytes.
            assertThatCode(() -> generator().generate("image/png", AttachmentFixtures.windowsExecutable()))
                    .doesNotThrowAnyException();
            assertThat(generator().generate("image/png", AttachmentFixtures.windowsExecutable())).isEmpty();
        }

        @Test
        void aPngSignatureFollowedByGarbageProducesNothing() {
            byte[] signatureOnly = AttachmentFixtures.pngSignature();
            assertThat(generator().generate("image/png", signatureOnly)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the supported set cannot drift away from what can be uploaded")
    class NoDrift {

        @Test
        void everyTypeThisClassClaimsIsOneAnAttachmentTypeCanActuallyProduce() {
            // Two lists of image formats in one feature is how a generator ends
            // up claiming a type the upload path never stores — dead code that
            // reads as coverage.
            assertThat(ThumbnailGenerator.DECODABLE)
                    .allSatisfy(mediaType -> assertThat(Arrays.stream(AttachmentType.values())
                            .map(AttachmentType::mediaType)
                            .toList())
                            .as("%s is not a media type any AttachmentType produces", mediaType)
                            .contains(mediaType));
        }

        @Test
        void itIsExactlyTheImageFamiliesTheJvmCanRead() {
            assertThat(Arrays.stream(AttachmentType.values())
                    .filter(type -> generator().supports(type.mediaType()))
                    .toList())
                    .containsExactlyInAnyOrder(AttachmentType.PNG, AttachmentType.JPEG, AttachmentType.GIF);
        }

        @Test
        void svgIsNotSupportedAndNeverShouldBe() {
            // Not on §4B.4's list and must not arrive by another route: SVG is a
            // scriptable document, and "render it to make a preview" is the one
            // way a scriptable document gets executed server-side.
            assertThat(generator().supports("image/svg+xml")).isFalse();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(image).as("the generator's output must itself be readable").isNotNull();
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixture's own format, read back from its leading bytes. */
    private static String mediaTypeOf(byte[] source) {
        Optional<AttachmentType> sniffed = new AttachmentSniffer().sniff(source);
        return sniffed.map(AttachmentType::mediaType).orElseThrow();
    }
}
