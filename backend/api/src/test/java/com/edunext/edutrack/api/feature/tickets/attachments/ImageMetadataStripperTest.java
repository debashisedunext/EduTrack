package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-025 · EXIF stripped on upload — blueprint §4B.4.
 *
 * <p>The assertions are on the bytes rather than on a metadata library's reading
 * of them, deliberately: the claim being made is "the coordinates are not in the
 * file", and searching the whole array for them is the direct form of that claim.
 * A library assertion would only prove that <em>that</em> library cannot find
 * them, which is a weaker statement and the one an attacker is not bound by.
 */
class ImageMetadataStripperTest {

    private final ImageMetadataStripper stripper = new ImageMetadataStripper();

    /** The coordinates every fixture in this file carries. */
    private static final String LATITUDE = "51.5074";
    private static final String LONGITUDE = "-0.1278";

    private static String asText(byte[] content) {
        return new String(content, StandardCharsets.ISO_8859_1);
    }

    @Nested
    @DisplayName("JPEG")
    class Jpeg {

        @Test
        void theFixtureReallyDoesCarryLocationDataBeforeStripping() {
            // Without this the tests below could pass against a fixture that
            // never had EXIF in it — which is the way a stripper test silently
            // stops testing anything.
            assertThat(asText(AttachmentFixtures.jpegWithExif()))
                    .contains(LATITUDE)
                    .contains(LONGITUDE);
        }

        @Test
        void exifIsGone() {
            byte[] stripped = stripper.strip(AttachmentType.JPEG, AttachmentFixtures.jpegWithExif());
            assertThat(asText(stripped))
                    .doesNotContain(LATITUDE)
                    .doesNotContain(LONGITUDE)
                    .doesNotContain("Exif");
        }

        @Test
        void iptcAndCommentsGoTooBecauseTheyCarryTheSameThing() {
            byte[] stripped = stripper.strip(AttachmentType.JPEG, AttachmentFixtures.jpegWithExif());
            assertThat(asText(stripped))
                    .doesNotContain("IPTC")
                    .doesNotContain("Photoshop")
                    .doesNotContain("Taken by Priya");
        }

        @Test
        void jfifAndTheColourProfileSurvive() {
            // Dropping the ICC profile makes a screenshot of a UI render with
            // visibly wrong colours — a change to the evidence on the ticket for
            // no privacy gain, since a profile describes a monitor.
            byte[] stripped = stripper.strip(AttachmentType.JPEG, AttachmentFixtures.jpegWithExif());
            assertThat(asText(stripped))
                    .contains("JFIF")
                    .contains("ICC_PROFILE");
        }

        @Test
        void theImageDataItselfIsUntouched() {
            byte[] stripped = stripper.strip(AttachmentType.JPEG, AttachmentFixtures.jpegWithExif());
            assertThat(asText(stripped))
                    .contains("quantisation table")
                    .contains("scanheader");
            // Still a JPEG: SOI at the front, EOI at the back.
            assertThat(stripped[0] & 0xFF).isEqualTo(0xFF);
            assertThat(stripped[1] & 0xFF).isEqualTo(0xD8);
            assertThat(stripped[stripped.length - 2] & 0xFF).isEqualTo(0xFF);
            assertThat(stripped[stripped.length - 1] & 0xFF).isEqualTo(0xD9);
        }

        @Test
        void theResultIsStillSniffedAsAJpeg() {
            // The round trip that matters: whatever this produces must still
            // pass the check the file just passed, or an upload would be
            // accepted and then stored as something unrecognisable.
            byte[] stripped = stripper.strip(AttachmentType.JPEG, AttachmentFixtures.jpegWithExif());
            assertThat(new AttachmentSniffer().sniff(stripped)).contains(AttachmentType.JPEG);
        }

        @Test
        void aTruncatedJpegIsReturnedUnchangedRatherThanHalfRewritten() {
            byte[] truncated = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x40};
            assertThat(stripper.strip(AttachmentType.JPEG, truncated)).isEqualTo(truncated);
        }

        @Test
        void aSegmentClaimingToBeLongerThanTheFileIsRefusedRatherThanRead() {
            // The hostile case: a length field that would walk the parser off
            // the end of the array.
            byte[] overrunning = new byte[]{
                    (byte) 0xFF, (byte) 0xD8,
                    (byte) 0xFF, (byte) 0xE1, (byte) 0xFF, (byte) 0xFF,
                    'E', 'x', 'i', 'f'};
            assertThat(stripper.strip(AttachmentType.JPEG, overrunning)).isEqualTo(overrunning);
        }
    }

    @Nested
    @DisplayName("PNG — where a screenshot tool writes what it knows about you")
    class Png {

        @Test
        void exifAndTextChunksAreGone() {
            byte[] stripped = stripper.strip(AttachmentType.PNG, AttachmentFixtures.pngWithExif());
            assertThat(asText(stripped))
                    .doesNotContain(LATITUDE)
                    .doesNotContain("eXIf")
                    .doesNotContain("tEXt")
                    .doesNotContain("Snipping Tool")
                    .doesNotContain("iTXt")
                    .doesNotContain("xmpmeta")
                    .doesNotContain("tIME");
        }

        @Test
        void theHeaderAndTheImageDataSurvive() {
            byte[] stripped = stripper.strip(AttachmentType.PNG, AttachmentFixtures.pngWithExif());
            assertThat(asText(stripped)).contains("IHDR").contains("IDAT").contains("IEND");
            assertThat(java.util.Arrays.copyOf(stripped, 8)).isEqualTo(AttachmentFixtures.pngSignature());
        }

        @Test
        void theResultIsStillSniffedAsAPng() {
            byte[] stripped = stripper.strip(AttachmentType.PNG, AttachmentFixtures.pngWithExif());
            assertThat(new AttachmentSniffer().sniff(stripped)).contains(AttachmentType.PNG);
        }

        @Test
        void aChunkLengthOfMinusOneAsAnUnsignedIntDoesNotWrapPastTheArray() {
            // 0xFFFFFFFF read into an int is -1, which passes a naive bounds
            // check by being negative. Read as a long it fails, and the file is
            // returned unchanged.
            byte[] hostile = new byte[8 + 12];
            System.arraycopy(AttachmentFixtures.pngSignature(), 0, hostile, 0, 8);
            for (int i = 8; i < 12; i++) {
                hostile[i] = (byte) 0xFF;
            }
            System.arraycopy("eXIf".getBytes(StandardCharsets.US_ASCII), 0, hostile, 12, 4);
            assertThat(stripper.strip(AttachmentType.PNG, hostile)).isEqualTo(hostile);
        }
    }

    @Nested
    @DisplayName("WebP — where the flags matter as much as the chunks")
    class Webp {

        @Test
        void exifAndXmpChunksAreGone() {
            byte[] stripped = stripper.strip(AttachmentType.WEBP, AttachmentFixtures.webpWithExif());
            assertThat(asText(stripped))
                    .doesNotContain(LATITUDE)
                    .doesNotContain("EXIF")
                    .doesNotContain("XMP")
                    .doesNotContain("xmpmeta");
        }

        @Test
        void theVp8xFlagsNoLongerAnnounceChunksThatAreNotThere() {
            // Removing the chunks without clearing the bits leaves a file that
            // *says* it carries EXIF and does not, which strict decoders reject.
            byte[] original = AttachmentFixtures.webpWithExif();
            assertThat(original[20] & 0x0C).isEqualTo(0x0C);

            byte[] stripped = stripper.strip(AttachmentType.WEBP, original);
            assertThat(stripped[20] & 0x08).as("EXIF flag").isZero();
            assertThat(stripped[20] & 0x04).as("XMP flag").isZero();
        }

        @Test
        void theRiffSizeIsRecomputedForTheShorterFile() {
            // A stale size is the other half of the same bug: a reader that
            // trusts it reads past the end of the file.
            byte[] stripped = stripper.strip(AttachmentType.WEBP, AttachmentFixtures.webpWithExif());
            long declared = (stripped[4] & 0xFFL)
                    | ((stripped[5] & 0xFFL) << 8)
                    | ((stripped[6] & 0xFFL) << 16)
                    | ((stripped[7] & 0xFFL) << 24);
            assertThat(declared).isEqualTo(stripped.length - 8L);
        }

        @Test
        void theImageDataSurvivesAndTheResultIsStillSniffedAsWebp() {
            byte[] stripped = stripper.strip(AttachmentType.WEBP, AttachmentFixtures.webpWithExif());
            assertThat(asText(stripped)).contains("VP8X").contains("VP8 ");
            assertThat(new AttachmentSniffer().sniff(stripped)).contains(AttachmentType.WEBP);
        }
    }

    @Nested
    @DisplayName("formats with no metadata to strip are not rewritten at all")
    class Passthrough {

        @Test
        void pdfZipDocxAndTextComeBackByteIdentical() {
            // Identity, not equality: rewriting a file that needed no change is
            // a risk taken for nothing, and returning the same array proves the
            // stripper did not take it.
            byte[] pdf = AttachmentFixtures.pdf();
            byte[] zip = AttachmentFixtures.plainZip();
            byte[] docx = AttachmentFixtures.docx();
            byte[] text = AttachmentFixtures.text("2026-08-14 09:31:02 INFO started");

            assertThat(stripper.strip(AttachmentType.PDF, pdf)).isSameAs(pdf);
            assertThat(stripper.strip(AttachmentType.ZIP, zip)).isSameAs(zip);
            assertThat(stripper.strip(AttachmentType.DOCX, docx)).isSameAs(docx);
            assertThat(stripper.strip(AttachmentType.TEXT, text)).isSameAs(text);
        }

        @Test
        void gifIsPassedThroughBecauseTheFormatCannotCarryExif() {
            byte[] gif = AttachmentFixtures.gif();
            assertThat(stripper.strip(AttachmentType.GIF, gif)).isSameAs(gif);
        }

        @Test
        void aWebpWithNoMetadataChunksIsNotRebuilt() {
            byte[] plain = new byte[]{
                    'R', 'I', 'F', 'F', 0x14, 0, 0, 0, 'W', 'E', 'B', 'P',
                    'V', 'P', '8', ' ', 0x08, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8};
            assertThat(stripper.strip(AttachmentType.WEBP, plain)).isSameAs(plain);
        }

        @Test
        void emptyContentIsHandledRatherThanThrown() {
            byte[] empty = new byte[0];
            assertThat(stripper.strip(AttachmentType.PNG, empty)).isSameAs(empty);
            assertThat(stripper.strip(AttachmentType.PNG, null)).isNull();
        }
    }
}
