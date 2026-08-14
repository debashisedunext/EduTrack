package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-025 · "never a public bucket", and what the signed URL actually says.
 *
 * <p>The first assertion here is a source scan, which is unusual and is the
 * point. Every other guarantee in this feature is enforced by a code path;
 * "nothing ever makes an object public" is enforced by the <em>absence</em> of
 * one, and an absence cannot be asserted by calling a method. Reading the source
 * is the only way to state it, and it fails the build the moment somebody adds
 * the line that would break it.
 */
class AttachmentStorageSecurityTest {

    private static final Path FEATURE_SOURCE = Path.of(
            "src/main/java/com/edunext/edutrack/api/feature/tickets/attachments");

    @Nested
    @DisplayName("never a public bucket")
    class NeverPublic {

        @Test
        void noSourceFileInThisFeatureMentionsACannedAcl() {
            // ObjectCannedACL.PUBLIC_READ is one word away from
            // ObjectCannedACL.PRIVATE, in a line that would read as though it
            // had been reasoned about. So the vocabulary is kept out entirely:
            // the PutObjectRequest sets no ACL at all and takes the bucket's
            // default, which on any bucket created this decade is private with
            // public access blocked at the account level.
            //
            // The javadoc on S3AttachmentStorage names this test. If it is ever
            // deleted, delete that paragraph too — a claim with nothing behind
            // it is worse than no claim.
            // Comments are stripped first. S3AttachmentStorage's javadoc names
            // both constants in order to explain why neither appears in the
            // code, and a scan that could not tell prose from a call would make
            // the explanation illegal to write.
            List<String> offenders = sourceFiles()
                    .filter(file -> {
                        String code = withoutComments(read(file));
                        return code.contains("ObjectCannedACL")
                                || code.contains("PUBLIC_READ")
                                || code.contains(".acl(");
                    })
                    .map(path -> path.getFileName().toString())
                    .toList();

            assertThat(offenders)
                    .as("""
                            An attachment must never be made publicly readable (blueprint §4B.4). \
                            These files reference an object ACL, which is the only way that could \
                            happen. If a legitimate reason has appeared, it needs a decision \
                            recorded in the stream backlog, not a quiet line here.""")
                    .isEmpty();
        }

        @Test
        void thePutRequestCarriesNoAclAndTheSniffedContentType() {
            S3Client s3 = mock(S3Client.class);
            new S3AttachmentStorage(s3, mock(S3Presigner.class), "edutrack")
                    .put(AttachmentStorageKey.mint(347), AttachmentFixtures.pdf(), "application/pdf");

            ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3).putObject(request.capture(), any(RequestBody.class));

            assertThat(request.getValue().acl()).isNull();
            assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
            assertThat(request.getValue().key()).matches("^tickets/347/[0-9a-f-]{36}$");
            assertThat(request.getValue().bucket()).isEqualTo("edutrack");
        }

        @Test
        void theStorageInterfaceExposesNoWayToProduceAPublicAddress() {
            // The interface is the boundary: adding a method that returned a
            // bucket URL would be a diff somebody sees, rather than a call on an
            // S3Client that can already do anything.
            assertThat(Stream.of(AttachmentStorage.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName))
                    .containsExactlyInAnyOrder("put", "signedDownloadUrl", "delete", "read");
        }
    }

    @Nested
    @DisplayName("the signed URL")
    class SignedUrl {

        private final S3Presigner presigner = mock(S3Presigner.class);
        private final ArgumentCaptor<GetObjectPresignRequest> presign =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);

        private URI sign(String fileName, Duration ttl) {
            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            try {
                when(presigned.url()).thenReturn(URI.create("https://minio.example/edutrack/x?sig=abc").toURL());
            } catch (java.net.MalformedURLException impossible) {
                throw new IllegalStateException(impossible);
            }
            when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

            URI url = new S3AttachmentStorage(mock(S3Client.class), presigner, "edutrack")
                    .signedDownloadUrl(AttachmentStorageKey.mint(347), fileName, "image/png", ttl);
            verify(presigner).presignGetObject(presign.capture());
            return url;
        }

        @Test
        void carriesTheTtlItWasGiven() {
            sign("screenshot.png", Duration.ofMinutes(5));
            assertThat(presign.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        void forcesTheBrowserToSaveRatherThanRender() {
            // The last line of defence, and the reason an uploaded file can
            // never be a stored XSS against whoever opens the link — even if
            // every check above it had failed.
            sign("screenshot.png", Duration.ofMinutes(5));
            assertThat(presign.getValue().getObjectRequest().responseContentDisposition())
                    .startsWith("attachment;");
        }

        @Test
        void overridesTheContentTypeWithTheSniffedOne() {
            sign("screenshot.png", Duration.ofMinutes(5));
            assertThat(presign.getValue().getObjectRequest().responseContentType()).isEqualTo("image/png");
        }

        @Test
        void aFileNameCannotInjectASecondHeader() {
            // A filename is the one field in this feature a user writes freely,
            // and a CR/LF in a response header is header injection.
            //
            // The claim is about the line break, not about the words. The text
            // "X-Injected: yes" surviving *inside* the quoted filename is
            // harmless and is in fact the correct outcome — it is the user's
            // filename, on one line, in a quoted string. An earlier draft
            // asserted the words were absent and failed for that reason, which
            // would have pushed the fix towards mangling ordinary filenames.
            sign("evil\r\nX-Injected: yes.png", Duration.ofMinutes(5));
            String disposition = presign.getValue().getObjectRequest().responseContentDisposition();
            assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
            assertThat(disposition.lines()).hasSize(1);
        }

        @Test
        void aQuoteInTheFileNameCannotCloseTheQuotedString() {
            sign("report \"final\".png", Duration.ofMinutes(5));
            String disposition = presign.getValue().getObjectRequest().responseContentDisposition();
            assertThat(disposition.chars().filter(c -> c == '"').count())
                    .as("exactly the two quotes that delimit the ASCII filename")
                    .isEqualTo(2);
        }

        @Test
        void aNonAsciiFileNameSurvivesInTheRfc5987Form() {
            // Routine in this product, not exotic. The quoted ASCII form cannot
            // express it at all, so the real name rides in filename*.
            sign("भुगतान.png", Duration.ofMinutes(5));
            String disposition = presign.getValue().getObjectRequest().responseContentDisposition();
            assertThat(disposition).contains("filename*=UTF-8''%E0%A4%AD");
            // …and the quoted fallback holds nothing a header cannot carry.
            String ascii = disposition.substring(disposition.indexOf('"') + 1, disposition.lastIndexOf('"'));
            assertThat(ascii).matches("^[\\x20-\\x7E]+$").endsWith(".png");
        }

        @Test
        void aSpaceIsPercentEncodedAndNotTurnedIntoAPlus() {
            // URLEncoder is form encoding; RFC 5987 is not. Missing this puts
            // plus signs in the name the user's browser saves.
            sign("error log.png", Duration.ofMinutes(5));
            assertThat(presign.getValue().getObjectRequest().responseContentDisposition())
                    .contains("filename*=UTF-8''error%20log.png");
        }

        @Test
        void anEmptyFileNameStillProducesAValidHeader() {
            sign("", Duration.ofMinutes(5));
            assertThat(presign.getValue().getObjectRequest().responseContentDisposition())
                    .contains("filename=\"attachment\"");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Stream<Path> sourceFiles() {
        try {
            assertThat(FEATURE_SOURCE).as("the feature package must be where this test looks").exists();
            return Files.list(FEATURE_SOURCE).filter(path -> path.toString().endsWith(".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Block and line comments out, so the scan sees code.
     *
     * <p>Crude on purpose — it would also strip a comment marker inside a string
     * literal, and there are none in this package. A real parser here would be a
     * dependency and a maintenance cost for a check whose entire job is to look
     * for three tokens.
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
