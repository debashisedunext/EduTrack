package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * C-025 · the knobs, with defaults that are safe when nothing is configured.
 *
 * <p>Every value below has a default here rather than only in
 * {@code application.yml}, so an environment with no {@code edutrack.attachments}
 * block at all still gets the secure behaviour instead of a startup failure or a
 * zero. A misconfigured deployment should be strict, not open.
 *
 * @param signedUrlTtl how long a download URL is valid. Blueprint §4B.4 says
 *                     "short-lived" and does not put a number on it; five
 *                     minutes is long enough to click a link and open the file,
 *                     and short enough that the copy left in a browser history
 *                     or a proxy log is worthless by the time anyone reads it.
 *                     Long enough to <em>download</em> is the real floor — S3
 *                     checks the signature when the request starts, not
 *                     throughout, so a 40-second expiry would not cut a large
 *                     download short but would defeat a user who queued three.
 * @param maxFileBytes 10 MB, §4B.4. <b>C-027 moved the authority for this and
 *                     the two below into {@code attachment_settings}</b>, so
 *                     they are the fallback used when that row is absent rather
 *                     than the value normally enforced — see
 *                     {@link AttachmentSettingsService}, which is the only
 *                     caller. Kept rather than deleted because a deployment
 *                     whose settings row has been removed by hand should lose
 *                     its customisation and not its upload path.
 *                     {@code spring.servlet.multipart.max-file-size} still
 *                     bounds every one of them: the container refuses an
 *                     oversized body before it is buffered, so a configured cap
 *                     above it cannot take effect, which is why
 *                     {@code AttachmentSettingsService} refuses such a write and
 *                     clamps such a read.
 * @param maxTicketBytes 50 MB per ticket, §4B.4. Fallback, as above.
 * @param maxFiles 20 files per ticket, §4B.4. Fallback, as above.
 * @param deleteWindow C-028 · how long after upload the uploader's own delete
 *                     leaves no trace — §4B.4's fifteen minutes. Deliberately
 *                     <b>not</b> in {@code attachment_settings} beside the three
 *                     caps: C-027 moved those because §4B.4 says "all
 *                     configurable in system settings", and that sentence is
 *                     about the caps. This one is a retention rule rather than a
 *                     quota, and an administrator who could set it to a year
 *                     would be able to make the tombstone unreachable — which is
 *                     the record §4B.4 exists to keep. Configurable at deploy for
 *                     a test that cannot wait fifteen minutes, not at runtime for
 *                     an operator.
 * @param scan the AV scanner
 * @param thumbnail C-026's reductions
 */
@ConfigurationProperties("edutrack.attachments")
public record AttachmentProperties(

        @DefaultValue("PT5M") Duration signedUrlTtl,
        @DefaultValue("10485760") long maxFileBytes,
        @DefaultValue("52428800") long maxTicketBytes,
        @DefaultValue("20") int maxFiles,
        @DefaultValue("PT15M") Duration deleteWindow,
        @DefaultValue Scan scan,
        @DefaultValue Thumbnail thumbnail) {

    /**
     * @param enabled whether a scanner is reachable at all. <b>Off does not mean
     *                "treat everything as clean"</b> — see
     *                {@link AttachmentScanner} and {@code failOpen} below.
     * @param host    ClamAV's {@code clamd}, speaking the INSTREAM protocol
     * @param port    3310 is clamd's default
     * @param timeout per-scan socket timeout. A scan that hangs must fail, not
     *                hold a pool thread until the process is restarted
     * @param failOpen what to do when the scanner cannot be reached — and it
     *                 defaults to {@code false}, which is the single most
     *                 important default in this file. A file whose scan did not
     *                 complete stays PENDING and is never served. The opposite
     *                 default would serve every upload unscanned for the whole
     *                 duration of a scanner outage, and nothing about the product
     *                 would look different while it happened, which is what makes
     *                 it the dangerous direction. §4B.4's wording is "anti-virus
     *                 scan <em>before the file becomes visible</em>", and a file
     *                 that was never scanned has not satisfied that.
     */
    public record Scan(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("PT30S") Duration timeout,
            @DefaultValue("false") boolean failOpen) {
    }

    /**
     * C-026 · the reduced copy shown in the gallery strip — blueprint §4B.4.
     *
     * @param enabled off stores no reductions and leaves every {@code thumbnailUrl}
     *                null, which the client already renders (an image with no
     *                thumbnail falls back to the full file). It is a switch for an
     *                operator whose {@code ImageIO} has gone wrong, not a feature
     *                flag — nothing downstream branches on it
     * @param maxEdge the longest side of the reduction, in pixels. 320 covers a
     *                160px gallery tile on a 2× display, which is the largest
     *                place a thumbnail is drawn. Deliberately not larger: the
     *                whole reason to store a second object is that it is small
     *                enough for twenty of them to load at once
     * @param maxSourcePixels the decompression-bomb ceiling, and the one value
     *                here with a security job. A 10 MB PNG is allowed to be
     *                40,000 × 40,000 — 1.6 <em>billion</em> pixels, several
     *                gigabytes of heap once decoded — and §4B.4's per-file cap
     *                cannot see it, because the bomb is small until it is opened.
     *                The dimensions are read from the header and checked before
     *                any pixel is decoded. 50 MP is roughly a 50-megapixel
     *                camera's full frame, far above any screenshot and far below
     *                anything that could hurt
     */
    public record Thumbnail(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("320") int maxEdge,
            @DefaultValue("50000000") long maxSourcePixels) {
    }
}
