package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-025 · the clamd client, against a socket that speaks the protocol back.
 *
 * <p>A real {@link ServerSocket} rather than a mocked stream, because the thing
 * most likely to be wrong is the wire format — the length-prefixed chunks and
 * the zero-length terminator — and a mock would be written from the same
 * misreading as the code. This server asserts what it received.
 *
 * <p>No Testcontainers and no clamd: the protocol is small enough to state
 * exactly, and a test that needed a virus scanner installed would be a test
 * nobody runs.
 */
class ClamAvScannerTest {

    private static final AttachmentProperties.Scan SETTINGS_TEMPLATE =
            new AttachmentProperties.Scan(true, "127.0.0.1", 0, Duration.ofSeconds(5), false);

    /** A one-shot clamd stand-in that answers {@code reply} and records the request. */
    private static final class FakeClamd implements AutoCloseable {

        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<byte[]> received = new AtomicReference<>(new byte[0]);
        private final CountDownLatch handled = new CountDownLatch(1);

        FakeClamd(String reply) throws IOException {
            this.server = new ServerSocket(0);
            this.thread = new Thread(() -> {
                try (Socket socket = server.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {

                    ByteArrayOutputStream request = new ByteArrayOutputStream();
                    // Read the command, then chunks, stopping at the
                    // zero-length terminator — which is the part under test.
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        request.write(buffer, 0, read);
                        if (endsWithTerminator(request.toByteArray())) {
                            break;
                        }
                    }
                    received.set(request.toByteArray());

                    out.write(reply.getBytes(StandardCharsets.US_ASCII));
                    out.write(0);
                    out.flush();
                } catch (IOException ignored) {
                    // The client closed early; the assertions below will say so.
                } finally {
                    handled.countDown();
                }
            });
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private static boolean endsWithTerminator(byte[] request) {
            if (request.length < 4) {
                return false;
            }
            for (int i = request.length - 4; i < request.length; i++) {
                if (request[i] != 0) {
                    return false;
                }
            }
            return true;
        }

        int port() {
            return server.getLocalPort();
        }

        byte[] request() throws InterruptedException {
            handled.await(5, TimeUnit.SECONDS);
            return received.get();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static AttachmentProperties.Scan settingsFor(int port) {
        return new AttachmentProperties.Scan(true, "127.0.0.1", port,
                SETTINGS_TEMPLATE.timeout(), SETTINGS_TEMPLATE.failOpen());
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        void okMeansClean() throws Exception {
            try (FakeClamd clamd = new FakeClamd("stream: OK")) {
                var verdict = new ClamAvScanner(settingsFor(clamd.port()))
                        .scan("signoff.pdf", AttachmentFixtures.pdf());
                assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.CLEAN);
            }
        }

        @Test
        void foundMeansInfected() throws Exception {
            try (FakeClamd clamd = new FakeClamd("stream: Eicar-Test-Signature FOUND")) {
                var verdict = new ClamAvScanner(settingsFor(clamd.port()))
                        .scan("invoice.pdf", AttachmentFixtures.pdf());
                assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.INFECTED);
            }
        }

        @Test
        void theSignatureNameIsNotParsedBecauseItChangesWithEveryDatabaseUpdate() {
            // Matching on the trailing keyword rather than the whole line is
            // what keeps this working across signature updates.
            assertVerdict("stream: Win.Trojan.Agent-1234567 FOUND", AttachmentScanner.Verdict.INFECTED);
            assertVerdict("stream: Unix.Malware.Generic FOUND", AttachmentScanner.Verdict.INFECTED);
        }

        private void assertVerdict(String reply, AttachmentScanner.Verdict expected) {
            try (FakeClamd clamd = new FakeClamd(reply)) {
                assertThat(new ClamAvScanner(settingsFor(clamd.port())).scan("f.pdf", AttachmentFixtures.pdf()))
                        .isEqualTo(expected);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested
    @DisplayName("no failure is ever reported as clean — the property that matters most")
    class FailureIsNeverClean {

        @Test
        void anUnreachableScannerIsUnknown() {
            // Port 1 on loopback: nothing listens, and connect is refused
            // immediately rather than hanging.
            var verdict = new ClamAvScanner(settingsFor(1)).scan("x.pdf", AttachmentFixtures.pdf());
            assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.UNKNOWN);
        }

        @Test
        void aScannerErrorReplyIsUnknown() throws Exception {
            try (FakeClamd clamd = new FakeClamd("INSTREAM size limit exceeded. ERROR")) {
                var verdict = new ClamAvScanner(settingsFor(clamd.port()))
                        .scan("big.mp4", AttachmentFixtures.mp4());
                assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.UNKNOWN);
            }
        }

        @Test
        void anUnrecognisedReplyIsUnknown() throws Exception {
            try (FakeClamd clamd = new FakeClamd("something nobody has seen before")) {
                var verdict = new ClamAvScanner(settingsFor(clamd.port()))
                        .scan("x.pdf", AttachmentFixtures.pdf());
                assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.UNKNOWN);
            }
        }

        @Test
        void anEmptyReplyIsUnknown() throws Exception {
            try (FakeClamd clamd = new FakeClamd("")) {
                var verdict = new ClamAvScanner(settingsFor(clamd.port()))
                        .scan("x.pdf", AttachmentFixtures.pdf());
                assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.UNKNOWN);
            }
        }

        @Test
        void anEmptyFileIsUnknownAndNotClean() {
            // Nothing to scan is not the same as scanned and found nothing.
            var verdict = new ClamAvScanner(settingsFor(1)).scan("empty.txt", new byte[0]);
            assertThat(verdict).isEqualTo(AttachmentScanner.Verdict.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("the INSTREAM wire format")
    class WireFormat {

        @Test
        void sendsTheCommandThenLengthPrefixedChunksThenAZeroLengthTerminator() throws Exception {
            byte[] content = AttachmentFixtures.pdf();

            try (FakeClamd clamd = new FakeClamd("stream: OK")) {
                new ClamAvScanner(settingsFor(clamd.port())).scan("signoff.pdf", content);

                byte[] request = clamd.request();
                String command = new String(request, 0, 10, StandardCharsets.US_ASCII);
                assertThat(command).isEqualTo("zINSTREAM\0");

                // Then a four-byte big-endian length, then exactly that many bytes.
                int declared = ((request[10] & 0xFF) << 24) | ((request[11] & 0xFF) << 16)
                        | ((request[12] & 0xFF) << 8) | (request[13] & 0xFF);
                assertThat(declared).isEqualTo(content.length);
                assertThat(java.util.Arrays.copyOfRange(request, 14, 14 + content.length)).isEqualTo(content);

                // And the terminator, without which clamd waits for more data
                // and the scan times out into UNKNOWN.
                assertThat(java.util.Arrays.copyOfRange(request, request.length - 4, request.length))
                        .containsOnly((byte) 0);
            }
        }

        @Test
        void aFileLargerThanOneChunkIsSentAsSeveral() throws Exception {
            byte[] large = new byte[100_000];
            java.util.Arrays.fill(large, (byte) 'A');

            try (FakeClamd clamd = new FakeClamd("stream: OK")) {
                new ClamAvScanner(settingsFor(clamd.port())).scan("big.log", large);

                byte[] request = clamd.request();
                // 10 bytes of command, four chunks (32 KiB × 3 + remainder) each
                // with a four-byte header, and a four-byte terminator.
                assertThat(request).hasSize(10 + large.length + 4 * 4 + 4);
            }
        }
    }
}
