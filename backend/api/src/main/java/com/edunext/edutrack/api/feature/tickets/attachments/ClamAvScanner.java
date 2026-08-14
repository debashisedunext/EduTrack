package com.edunext.edutrack.api.feature.tickets.attachments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * C-025 · {@code clamd} over TCP, INSTREAM — blueprint §4B.4's AV scan.
 *
 * <h2>INSTREAM and not SCAN</h2>
 *
 * <p>clamd's {@code SCAN} command takes a <em>path</em> and requires the daemon
 * to see the same filesystem the application does, which stops being true the
 * moment either is containerised and is never true when the bytes live in
 * MinIO. {@code INSTREAM} pushes the file down the socket instead, so the
 * scanner needs no shared mount, no shared user and no filesystem at all.
 *
 * <p>The wire format is small and exact: {@code zINSTREAM\0}, then a sequence of
 * chunks each prefixed with its length as a four-byte big-endian integer, then a
 * zero-length chunk to end the stream. clamd answers with one line. Getting the
 * terminator wrong is the classic failure — the daemon waits for more data, the
 * socket times out, and the file is reported unscannable rather than infected,
 * which is why {@link #TERMINATOR} is written explicitly rather than implied by
 * closing the stream.
 *
 * <h2>Every failure is UNKNOWN, and none is CLEAN</h2>
 *
 * <p>Connection refused, timeout, a truncated reply, a reply this class does not
 * recognise: all {@link Verdict#UNKNOWN}. There is no code path here that turns
 * an error into a pass. That is the property worth protecting in review — the
 * natural shape of a scanner client is {@code try { … } catch (IOException e) {
 * return CLEAN; }} written by somebody unblocking a broken dev environment, and
 * it silently disables the control for everybody.
 */
class ClamAvScanner implements AttachmentScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);

    private static final byte[] INSTREAM = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TERMINATOR = {0, 0, 0, 0};

    /**
     * clamd's own {@code StreamMaxLength} defaults to 25 MB and it aborts the
     * connection when a chunk overruns. 32 KiB keeps each write comfortably
     * inside every clamd buffer and bounds how much is in flight.
     */
    private static final int CHUNK_BYTES = 32 * 1024;

    /** Longer than any legitimate reply; a runaway daemon must not be read for ever. */
    private static final int MAX_REPLY_BYTES = 512;

    private final AttachmentProperties.Scan settings;

    ClamAvScanner(AttachmentProperties.Scan settings) {
        this.settings = settings;
    }

    @Override
    public Verdict scan(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            // Nothing to scan is not the same as scanned-and-clean, and an empty
            // upload should not have reached storage in the first place.
            return Verdict.UNKNOWN;
        }

        try (Socket socket = new Socket()) {
            int timeoutMillis = (int) Math.min(settings.timeout().toMillis(), Integer.MAX_VALUE);
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), timeoutMillis);
            // Both halves need the bound: a daemon that accepts the connection
            // and then stops reading would otherwise hang the write, not the read.
            socket.setSoTimeout(timeoutMillis);

            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {
                send(out, content);
                return interpret(readReply(in), fileName);
            }
        } catch (IOException unreachable) {
            log.warn("clamd at {}:{} did not answer for {}; leaving the attachment PENDING",
                    settings.host(), settings.port(), fileName, unreachable);
            return Verdict.UNKNOWN;
        }
    }

    private void send(OutputStream out, byte[] content) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.write(INSTREAM);
        for (int offset = 0; offset < content.length; offset += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, content.length - offset);
            data.writeInt(length);
            data.write(content, offset, length);
        }
        data.write(TERMINATOR);
        data.flush();
    }

    /**
     * clamd terminates its reply with a NUL in {@code z} mode. Read to that, or
     * to end-of-stream, or to the cap — never unbounded.
     */
    private String readReply(InputStream in) throws IOException {
        byte[] buffer = new byte[MAX_REPLY_BYTES];
        int filled = 0;
        while (filled < buffer.length) {
            int b = in.read();
            if (b < 0 || b == 0) {
                break;
            }
            buffer[filled++] = (byte) b;
        }
        return new String(buffer, 0, filled, StandardCharsets.US_ASCII).trim();
    }

    /**
     * {@code stream: OK} or {@code stream: Eicar-Test-Signature FOUND}.
     *
     * <p>Matched on the trailing keyword rather than by parsing the whole line,
     * because the middle is a signature name that changes with every database
     * update. Anything else — including clamd's own {@code … ERROR} replies for
     * a size limit or a broken stream — is UNKNOWN, and the raw reply is logged
     * so an operator can see what the daemon actually said.
     */
    private Verdict interpret(String reply, String fileName) {
        String normalised = reply.toUpperCase(Locale.ROOT);
        if (normalised.endsWith("OK")) {
            return Verdict.CLEAN;
        }
        if (normalised.endsWith("FOUND")) {
            log.warn("clamd reported an infected attachment {}: {}", fileName, reply);
            return Verdict.INFECTED;
        }
        log.warn("clamd gave no verdict for {}: {}", fileName, reply);
        return Verdict.UNKNOWN;
    }
}
