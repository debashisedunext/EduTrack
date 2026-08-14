package com.edunext.edutrack.api.feature.tickets.attachments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C-025 · wires the scanner, and refuses to start when it has been configured to
 * wave files through outside local development.
 *
 * <p>Modelled on {@code TotpConfig} and {@code RefreshTokenConfig}, which do the
 * same for a placeholder key and a non-secure cookie, and for the same reason: a
 * setting that is convenient in development and unacceptable everywhere else
 * will eventually be shipped unless something stops it. {@code fail-open} is the
 * one here — it converts "the scanner is down" from "nothing is served" into
 * "everything is served unscanned", and nothing about the running product looks
 * different while it is happening.
 *
 * <h2>Why the scan runs on its own executor rather than on {@code @Async}</h2>
 *
 * <p>{@code @EnableAsync} is application-wide. Switching it on from a Stream C
 * feature changes the proxying behaviour of every {@code @Async} annotation in
 * three other streams' code at once, including any written speculatively while
 * it was inert. A private, bounded pool owned by this feature has none of that
 * blast radius, and its bound is a property of the design rather than of a
 * shared executor somebody else tunes: a burst of uploads queues rather than
 * spawning a scan thread each, and the queue is finite so a scanner outage
 * cannot grow it without limit.
 *
 * <p>Rejection is {@link ThreadPoolExecutor.CallerRunsPolicy} — under saturation
 * the request thread performs the scan itself, which is slower for that one
 * caller and is the correct trade. Dropping the task would leave a stored object
 * PENDING for ever with nothing scheduled to resolve it, which is the same
 * end-state as a lost file and considerably harder to notice.
 */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
class AttachmentScanConfig {

    private static final Logger log = LoggerFactory.getLogger(AttachmentScanConfig.class);

    /**
     * Two threads: a scan is IO-bound on a socket to clamd, and the useful
     * bound is clamd's own concurrency rather than ours.
     */
    private static final int SCAN_THREADS = 2;
    private static final int SCAN_QUEUE_DEPTH = 100;

    AttachmentScanConfig(AttachmentProperties properties, Environment environment) {
        if (properties.scan().failOpen() && !environment.matchesProfiles("local")) {
            throw new IllegalStateException(
                    "edutrack.attachments.scan.fail-open is true outside the local profile. That "
                            + "makes an attachment downloadable when the virus scan did NOT complete, "
                            + "which is precisely what blueprint §4B.4's \"scan before the file becomes "
                            + "visible\" forbids — and an outage would look like normal operation. "
                            + "Active profiles: " + String.join(",", environment.getActiveProfiles())
                            + ". Refusing to start.");
        }
        if (!properties.scan().enabled()) {
            // Loud on purpose. With no scanner every upload stays PENDING and no
            // attachment in the product can be opened — which is the correct
            // failure and is also completely mystifying if you have not been told.
            log.warn("No attachment virus scanner is configured (edutrack.attachments.scan.enabled=false). "
                    + "Uploads will be stored and will stay PENDING, so nothing will be downloadable. "
                    + "Set edutrack.attachments.scan.enabled=true with a reachable clamd, or — in local "
                    + "development only — edutrack.attachments.scan.fail-open=true.");
        }
    }

    /**
     * The scanner, or a stand-in that answers {@link AttachmentScanner.Verdict#UNKNOWN}
     * to everything.
     *
     * <p>The stand-in is not a no-op that returns CLEAN, and that distinction is
     * the whole point: with no scanner configured the system behaves exactly as
     * it does during an outage, so the unconfigured case is covered by the same
     * reasoning and the same code path rather than by a second, more permissive
     * one nobody tests.
     */
    @Bean
    AttachmentScanner attachmentScanner(AttachmentProperties properties) {
        if (!properties.scan().enabled()) {
            return (fileName, content) -> AttachmentScanner.Verdict.UNKNOWN;
        }
        return new ClamAvScanner(properties.scan());
    }

    @Bean(name = "attachmentScanExecutor", destroyMethod = "shutdown")
    ExecutorService attachmentScanExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "attachment-scan-" + sequence.getAndIncrement());
                // A daemon thread does not hold up shutdown, and a scan that is
                // interrupted mid-flight leaves the row PENDING — which is
                // recoverable, unlike a JVM that will not exit.
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(
                SCAN_THREADS, SCAN_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(SCAN_QUEUE_DEPTH),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
