package com.edunext.edutrack.api.feature.imports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B-035 · the pool step 5's "background job" actually runs on.
 *
 * <h2>A private pool, not {@code @Async}</h2>
 *
 * <p>{@code @EnableAsync} is on this application, so {@code @Async} would work.
 * It is still not what this uses, and {@code AttachmentScanConfig} records the
 * argument one feature over: the shared executor is shared, so its bound is
 * whatever somebody else tuned it to and a burst of imports competes with every
 * other background thing in three streams' code. A pool owned by this feature
 * has a bound that is a property of <em>this</em> design — how many bulk writes
 * to the master data this deployment is willing to have in flight — and nothing
 * else lands in it.
 *
 * <h2>Two threads, and a short queue</h2>
 *
 * <p>A commit is database-bound, not CPU-bound: it is a few thousand upserts
 * through one connection pool. Widening this does not make one import faster, it
 * makes several imports contend for the same connections and the same rows, and
 * two admins importing at once is already the unusual case for a screen §7.4
 * puts inside the Admin-only master data module.
 *
 * <p>The queue is short for the same reason and one more: a queued batch sits at
 * {@code QUEUED} with a progress bar that does not move, and a user watching it
 * cannot tell that from a job that has died. Six is about as long as that is
 * honest for; past it the request is refused with a status that says "come
 * back", which is a better answer than a screen that appears to be working.
 *
 * <h2>Saturation is a refusal, not caller-runs</h2>
 *
 * <p>The abort policy is deliberate and is the one place this diverges from
 * {@code AttachmentScanConfig}, which uses {@link
 * ThreadPoolExecutor.CallerRunsPolicy}. See {@link ImportCommitQueueFullException}
 * — running a five-thousand-row commit on the request thread holds the
 * connection open past every reasonable timeout, and the response it is holding
 * up is the one that tells the browser which batch to poll.
 */
@Configuration
class ImportCommitConfig {

    /** Bean name, so the injection point says which executor it means. */
    static final String EXECUTOR = "importCommitExecutor";

    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    ExecutorService importCommitExecutor(
            @Value("${edutrack.imports.commit-threads:2}") int threads,
            @Value("${edutrack.imports.commit-queue-depth:6}") int queueDepth) {

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "import-commit-" + sequence.getAndIncrement());
                // Daemon, like the attachment scanner's: a JVM that will not
                // exit is a worse failure than an import interrupted mid-run,
                // which leaves the batch RUNNING and is exactly what
                // ImportBatchRepository.findByStatus exists to find.
                thread.setDaemon(true);
                return thread;
            }
        };

        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueDepth),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * How many runs may be queued or running before a commit is refused.
     *
     * <p>Exposed as a bean rather than recomputed at the throw site so the
     * number in the 503's body is the number the executor was built with.
     */
    @Bean
    ImportCommitCeiling importCommitCeiling(
            @Value("${edutrack.imports.commit-threads:2}") int threads,
            @Value("${edutrack.imports.commit-queue-depth:6}") int queueDepth) {
        return new ImportCommitCeiling(threads + queueDepth);
    }

    record ImportCommitCeiling(int value) {
    }
}
