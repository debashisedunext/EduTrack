package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-025 · what the bytes are, independently of what they are called.
 *
 * <p>The class under test never sees a file name, so nothing here passes one.
 * That is the property being protected as much as the detection itself: a
 * sniffer that could read the extension would eventually be helped by it, and
 * §4B.4's second opinion would stop being independent.
 */
class AttachmentSnifferTest {

    private final AttachmentSniffer sniffer = new AttachmentSniffer();

    @Nested
    @DisplayName("every format on §4B.4's list is recognised from its bytes")
    class Recognition {

        @Test
        void png() {
            assertThat(sniffer.sniff(AttachmentFixtures.pngWithExif())).contains(AttachmentType.PNG);
        }

        @Test
        void jpeg() {
            assertThat(sniffer.sniff(AttachmentFixtures.jpegWithExif())).contains(AttachmentType.JPEG);
            assertThat(sniffer.sniff(AttachmentFixtures.jpegMinimal())).contains(AttachmentType.JPEG);
        }

        @Test
        void gif() {
            assertThat(sniffer.sniff(AttachmentFixtures.gif())).contains(AttachmentType.GIF);
        }

        @Test
        void webp() {
            assertThat(sniffer.sniff(AttachmentFixtures.webpWithExif())).contains(AttachmentType.WEBP);
        }

        @Test
        void pdf() {
            assertThat(sniffer.sniff(AttachmentFixtures.pdf())).contains(AttachmentType.PDF);
        }

        @Test
        void mp4() {
            assertThat(sniffer.sniff(AttachmentFixtures.mp4())).contains(AttachmentType.MP4);
        }

        @Test
        void plainTextAndLogs() {
            assertThat(sniffer.sniff(AttachmentFixtures.text("2026-08-14 09:31:02 INFO started")))
                    .contains(AttachmentType.TEXT);
            assertThat(sniffer.sniff(AttachmentFixtures.text("id,name\n1,Priya\n2,Anil\n")))
                    .contains(AttachmentType.TEXT);
        }
    }

    @Nested
    @DisplayName("containers are opened, because their headers do not discriminate")
    class Containers {

        @Test
        void aDocxAndAnXlsxAndAPlainZipAllStartWithTheSameFourBytes() {
            // The premise of the whole tier. If this ever stops holding, the
            // discrimination below is unnecessary — and if it holds, checking
            // only the header cannot possibly tell them apart.
            byte[] docx = AttachmentFixtures.docx();
            byte[] xlsx = AttachmentFixtures.xlsx();
            byte[] zip = AttachmentFixtures.plainZip();
            assertThat(new byte[]{docx[0], docx[1], docx[2], docx[3]})
                    .isEqualTo(new byte[]{xlsx[0], xlsx[1], xlsx[2], xlsx[3]})
                    .isEqualTo(new byte[]{zip[0], zip[1], zip[2], zip[3]});
        }

        @Test
        void aZipHoldingWordIsADocx() {
            assertThat(sniffer.sniff(AttachmentFixtures.docx())).contains(AttachmentType.DOCX);
        }

        @Test
        void aZipHoldingXlIsAnXlsx() {
            assertThat(sniffer.sniff(AttachmentFixtures.xlsx())).contains(AttachmentType.XLSX);
        }

        @Test
        void aZipHoldingNeitherIsAnArchive() {
            assertThat(sniffer.sniff(AttachmentFixtures.plainZip())).contains(AttachmentType.ZIP);
        }

        @Test
        void anArchiveThatCannotBeOpenedIsStillAnArchive() {
            // Encrypted, corrupt or truncated. Reported as ZIP rather than
            // refused, because the extension check still has to agree — so this
            // is accepted only when the user did call it a .zip.
            byte[] truncated = new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x00};
            assertThat(sniffer.sniff(truncated)).contains(AttachmentType.ZIP);
        }
    }

    @Nested
    @DisplayName("a disguised binary is refused — §4B.4's reason for sniffing at all")
    class Disguises {

        @Test
        void aWindowsExecutableIsNotAnyAllowedFormat() {
            assertThat(sniffer.sniff(AttachmentFixtures.windowsExecutable())).isEmpty();
        }

        @Test
        void anElfBinaryIsNotAnyAllowedFormat() {
            assertThat(sniffer.sniff(AttachmentFixtures.elfExecutable())).isEmpty();
        }

        @Test
        void anExecutableIsNotMistakenForTextBecauseOfItsEmbeddedStrings() {
            // The DOS stub contains "This program cannot be run in DOS mode." in
            // plain ASCII, which is exactly the kind of thing a naive "does it
            // look printable" check reads as a text file. The NUL bytes around
            // it are what settle it.
            byte[] executable = AttachmentFixtures.windowsExecutable();
            assertThat(new String(executable, StandardCharsets.ISO_8859_1)).contains("DOS mode");
            assertThat(sniffer.sniff(executable)).isEmpty();
        }

        @Test
        void anSvgIsRecognisedAsTextAndNotAsAnImage() {
            // SVG is a scriptable document and is deliberately off §4B.4's list.
            // It has no binary signature, so it lands in the text tier — and the
            // extension check then refuses it, because `.svg` is not allowed and
            // `.txt` is not what anyone would name it.
            assertThat(sniffer.sniff(AttachmentFixtures.svgWithScript())).contains(AttachmentType.TEXT);
            assertThat(AttachmentType.isAllowedExtension("svg")).isFalse();
        }

        @Test
        void anAviIsRefusedEvenThoughItIsAlsoRiff() {
            // RIFF is a container family, not a format. Checking only "RIFF" at
            // offset 0 would accept every AVI and WAV as a WebP.
            byte[] avi = new byte[32];
            System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, avi, 0, 4);
            System.arraycopy("AVI ".getBytes(StandardCharsets.US_ASCII), 0, avi, 8, 4);
            assertThat(sniffer.sniff(avi)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the text tier decides by absence of a signature, not by presence of one")
    class TextTier {

        @Test
        void aNulByteMeansBinary() {
            byte[] withNul = new byte[]{'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'};
            assertThat(sniffer.sniff(withNul)).isEmpty();
        }

        @Test
        void aControlCharacterMeansBinary() {
            // BEL, ESC and DEL are all valid UTF-8, which is precisely why the
            // rule is stated as control characters and not as "decodes as
            // UTF-8" — the latter would pass a terminal-escape payload as .txt.
            assertThat(sniffer.sniff(new byte[]{'l', 'o', 'g', 0x07, 'l', 'i', 'n', 'e'})).isEmpty();
            assertThat(sniffer.sniff(new byte[]{'l', 'o', 'g', 0x1B, '[', '2', 'J'})).isEmpty();
            assertThat(sniffer.sniff(new byte[]{'l', 'o', 'g', 0x7F, 'x'})).isEmpty();
        }

        @Test
        void tabsNewlinesAndCarriageReturnsAreText() {
            assertThat(sniffer.sniff(AttachmentFixtures.text("a\tb\r\nc\n")))
                    .contains(AttachmentType.TEXT);
        }

        @Test
        void utf8BeyondAsciiIsText() {
            assertThat(sniffer.sniff(AttachmentFixtures.text("भुगतान गेटवे का समय समाप्त")))
                    .contains(AttachmentType.TEXT);
        }

        @Test
        void aWindows1252LogIsTextEvenThoughItIsNotValidUtf8() {
            // Windows-hosted services still write these, and UTF-8 rejects the
            // bytes outright. Refusing them would refuse the single file type
            // §4B.4 put `log` on the list to accommodate.
            byte[] latin1 = "temperature 21°C — nominal".getBytes(StandardCharsets.ISO_8859_1);
            assertThat(sniffer.sniff(latin1)).contains(AttachmentType.TEXT);
        }

        @Test
        void aLongTextFileCutMidCharacterAtTheSampleBoundaryIsStillText() {
            // A regression guard for a rule this class deliberately does not
            // have. The sample is 8 KiB and a multi-byte character can straddle
            // it, so a strict-UTF-8 check would report the truncation as
            // malformed input and reject a perfectly good large log — and only
            // ever above 8 KiB, which is the size at which nobody tests.
            StringBuilder padded = new StringBuilder();
            while (padded.length() < 8191) {
                padded.append('x');
            }
            padded.append("भुगतान".repeat(200));
            assertThat(sniffer.sniff(AttachmentFixtures.text(padded.toString())))
                    .contains(AttachmentType.TEXT);
        }
    }

    @Nested
    @DisplayName("nothing is recognised by default")
    class Refusals {

        @Test
        void emptyAndNullAreRefused() {
            assertThat(sniffer.sniff(new byte[0])).isEqualTo(Optional.empty());
            assertThat(sniffer.sniff(null)).isEqualTo(Optional.empty());
        }

        @Test
        void aByteSequenceMatchingNothingIsRefusedRatherThanGuessedAt() {
            assertThat(sniffer.sniff(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05})).isEmpty();
        }
    }
}
