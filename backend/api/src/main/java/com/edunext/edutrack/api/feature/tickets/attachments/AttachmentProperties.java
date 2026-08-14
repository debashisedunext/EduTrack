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
 * @param maxFileBytes 10 MB, §4B.4. Mirrored by {@code spring.servlet.multipart.max-file-size}
 *                     so the container refuses an oversized body before it is
 *                     buffered; this is the check that produces the contract's
 *                     413 with a message, and the two must move together.
 * @param maxTicketBytes 50 MB per ticket, §4B.4.
 * @param maxFiles 20 files per ticket, §4B.4.
 * @param scan the AV scanner
 */
@ConfigurationProperties("edutrack.attachments")
record AttachmentProperties(

        @DefaultValue("PT5M") Duration signedUrlTtl,
        @DefaultValue("10485760") long maxFileBytes,
        @DefaultValue("52428800") long maxTicketBytes,
        @DefaultValue("20") int maxFiles,
        @DefaultValue Scan scan) {

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
    record Scan(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("PT30S") Duration timeout,
            @DefaultValue("false") boolean failOpen) {
    }
}
