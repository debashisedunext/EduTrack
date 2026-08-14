package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-025 · the conjunction — blueprint §4B.4's "extension allow-list <b>and</b>
 * MIME sniffing".
 *
 * <p>The tests are organised around the two holes the conjunction closes, not
 * around the happy path, because each check on its own passes the happy path
 * and the whole reason there are two is what happens off it.
 */
class AttachmentTypePolicyTest {

    private final AttachmentTypePolicy policy = new AttachmentTypePolicy(new AttachmentSniffer());

    @Nested
    @DisplayName("name and bytes agreeing is the only way through")
    class Agreement {

        @Test
        void everyFormatOnTheListRoundTrips() {
            assertThat(policy.reconcile("screenshot.png", AttachmentFixtures.pngWithExif()).mediaType())
                    .isEqualTo("image/png");
            assertThat(policy.reconcile("photo.jpg", AttachmentFixtures.jpegWithExif()).mediaType())
                    .isEqualTo("image/jpeg");
            assertThat(policy.reconcile("photo.jpeg", AttachmentFixtures.jpegWithExif()).type())
                    .isEqualTo(AttachmentType.JPEG);
            assertThat(policy.reconcile("animation.gif", AttachmentFixtures.gif()).mediaType())
                    .isEqualTo("image/gif");
            assertThat(policy.reconcile("capture.webp", AttachmentFixtures.webpWithExif()).mediaType())
                    .isEqualTo("image/webp");
            assertThat(policy.reconcile("signoff.pdf", AttachmentFixtures.pdf()).mediaType())
                    .isEqualTo("application/pdf");
            assertThat(policy.reconcile("repro.mp4", AttachmentFixtures.mp4()).mediaType())
                    .isEqualTo("video/mp4");
            assertThat(policy.reconcile("evidence.zip", AttachmentFixtures.plainZip()).mediaType())
                    .isEqualTo("application/zip");
            assertThat(policy.reconcile("notes.docx", AttachmentFixtures.docx()).type())
                    .isEqualTo(AttachmentType.DOCX);
            assertThat(policy.reconcile("figures.xlsx", AttachmentFixtures.xlsx()).type())
                    .isEqualTo(AttachmentType.XLSX);
            assertThat(policy.reconcile("app.log", AttachmentFixtures.text("INFO started")).mediaType())
                    .isEqualTo("text/plain");
        }

        @Test
        void theExtensionIsCaseInsensitive() {
            // Windows hands over `.PNG` routinely and it is the same file.
            assertThat(policy.reconcile("SCREENSHOT.PNG", AttachmentFixtures.pngWithExif()).type())
                    .isEqualTo(AttachmentType.PNG);
        }

        @Test
        void aCsvIsServedAsCsvEvenThoughTheSnifferCannotTellItFromALog() {
            // The family is TEXT for both. The extension is what distinguishes
            // them — and by this point it has been corroborated rather than
            // trusted, which is what makes it safe to act on.
            var csv = policy.reconcile("export.csv", AttachmentFixtures.text("id,name\n1,Priya\n"));
            var log = policy.reconcile("app.log", AttachmentFixtures.text("id,name\n1,Priya\n"));
            assertThat(csv.type()).isEqualTo(AttachmentType.TEXT);
            assertThat(csv.mediaType()).isEqualTo("text/csv");
            assertThat(log.mediaType()).isEqualTo("text/plain");
        }
    }

    @Nested
    @DisplayName("the hole the extension check alone leaves")
    class DisguisedBinaries {

        @Test
        void anExecutableNamedPdfIsRefused() {
            assertThatThrownBy(() -> policy.reconcile("payroll.pdf", AttachmentFixtures.windowsExecutable()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining("not a recognised .pdf file");
        }

        @Test
        void anExecutableNamedPngIsRefused() {
            assertThatThrownBy(() -> policy.reconcile("logo.png", AttachmentFixtures.elfExecutable()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class);
        }

        @Test
        void anSvgRenamedToPngIsRefused() {
            // A scriptable document with an image extension is the file that
            // would be handed to an <img> tag if this check were not here.
            assertThatThrownBy(() -> policy.reconcile("diagram.png", AttachmentFixtures.svgWithScript()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class);
        }
    }

    @Nested
    @DisplayName("the hole the sniffer alone leaves")
    class MislabelledContainers {

        @Test
        void aWordDocumentRenamedToZipIsRefused() {
            // At the container level a .docx *is* a ZIP, so a sniffer-only rule
            // accepts this — and the archive path becomes a way to smuggle a
            // document past a policy written about documents.
            assertThatThrownBy(() -> policy.reconcile("evidence.zip", AttachmentFixtures.docx()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining("a Word document");
        }

        @Test
        void aSpreadsheetNamedDocxIsRefused() {
            assertThatThrownBy(() -> policy.reconcile("figures.docx", AttachmentFixtures.xlsx()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining("an Excel workbook");
        }

        @Test
        void aPngNamedJpgIsRefusedAndSaysWhat() {
            // An ordinary mistake, and the message has to be enough to fix it —
            // the alternative is renaming the user's file for them, which is a
            // rule nobody can reason about six months later.
            assertThatThrownBy(() -> policy.reconcile("photo.jpg", AttachmentFixtures.pngWithExif()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining("named .jpg")
                    .hasMessageContaining("a PNG image");
        }
    }

    @Nested
    @DisplayName("the extension is reported before the contents are read")
    class Ordering {

        @Test
        void aDisallowedExtensionIsReportedAsSuchAndNotAsAMismatch() {
            // Same order the browser's validateAttachmentFile uses: "we do not
            // accept .exe" is actionable; "the contents do not match" sends the
            // user looking for a corruption that is not there.
            assertThatThrownBy(() -> policy.reconcile("setup.exe", AttachmentFixtures.windowsExecutable()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining(".exe files are not allowed")
                    .hasMessageContaining("png");
        }

        @Test
        void aFileWithNoExtensionIsRefused() {
            assertThatThrownBy(() -> policy.reconcile("Dockerfile", AttachmentFixtures.text("FROM eclipse-temurin")))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining("without an extension");
        }

        @Test
        void aDotfileHasNoExtension() {
            // `.gitignore` is a name, not an extension — the same rule the
            // browser's attachmentExtension applies, and the two must agree or a
            // file the client accepted is refused after it has been uploaded.
            assertThat(AttachmentType.extensionOf(".gitignore")).isEmpty();
            assertThatThrownBy(() -> policy.reconcile(".gitignore", AttachmentFixtures.text("target/")))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class);
        }

        @Test
        void aDoubleExtensionIsJudgedOnTheLastOne() {
            // `invoice.pdf.exe` is the oldest trick on the list, and it is
            // refused for its real extension rather than its decorative one.
            assertThatThrownBy(() -> policy.reconcile("invoice.pdf.exe", AttachmentFixtures.windowsExecutable()))
                    .isInstanceOf(UnsupportedAttachmentTypeException.class)
                    .hasMessageContaining(".exe files are not allowed");
        }
    }

    @Nested
    @DisplayName("§4B.4's list, and no more")
    class AllowList {

        @Test
        void holdsExactlyTheSixteenExtensionsTheBlueprintNames() {
            assertThat(AttachmentType.allowedExtensions()).containsExactly(
                    "csv", "doc", "docx", "gif", "jpeg", "jpg", "log", "mp4",
                    "pdf", "png", "txt", "webp", "xls", "xlsx", "zip");
        }

        @Test
        void theLegacyBinaryOfficeFormatsAreOnItDeliberately() {
            // §4B.4 puts them there because clients still send them, and calls
            // them out as the reason the extension alone is never the test.
            assertThat(AttachmentType.isAllowedExtension("doc")).isTrue();
            assertThat(AttachmentType.isAllowedExtension("xls")).isTrue();
        }

        @Test
        void svgAndExecutablesAndArchiveFormatsBeyondZipAreNotOnIt() {
            assertThat(AttachmentType.isAllowedExtension("svg")).isFalse();
            assertThat(AttachmentType.isAllowedExtension("exe")).isFalse();
            assertThat(AttachmentType.isAllowedExtension("bat")).isFalse();
            assertThat(AttachmentType.isAllowedExtension("html")).isFalse();
            assertThat(AttachmentType.isAllowedExtension("rar")).isFalse();
            assertThat(AttachmentType.isAllowedExtension("7z")).isFalse();
        }

        @Test
        void noFamilyEverServesAsATypeTheBrowserWouldRender() {
            // A stored file that came back as text/html would execute in the
            // browser under this application's origin. Nothing on the list can
            // produce one, and this asserts it rather than trusting the table to
            // be read carefully.
            //
            // An exact deny-set, not a substring match on "xml": the OOXML types
            // legitimately contain that string — `openxmlformats` — and a
            // substring rule fails on .docx and .xlsx while catching nothing
            // real. It was written that way first and this is what it found.
            var renderable = java.util.Set.of(
                    "text/html", "application/xhtml+xml", "image/svg+xml",
                    "application/xml", "text/xml", "text/javascript", "application/javascript");

            for (AttachmentType type : AttachmentType.values()) {
                for (String extension : type.extensions()) {
                    assertThat(type.mediaTypeFor(extension))
                            .as("%s.%s", type, extension)
                            .isNotIn(renderable);
                }
            }
        }
    }
}
