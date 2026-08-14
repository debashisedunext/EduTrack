package com.edunext.edutrack.worker.journal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A-044 · the nightly run.
 *
 * <h2>Every ticket, every night</h2>
 *
 * <p>Verifying only what changed recently would be far cheaper and would not
 * work: a tampered row is tampered in the <em>past</em>, so a run that revisits
 * only recently-active tickets never looks at a chain on a ticket closed six
 * months ago. PLAN.md §3.5 names this as the backstop for defeated triggers,
 * and a backstop blind to dormant records is not one.
 *
 * <p>The cost is real and grows for ever, because these tables never shrink.
 * It is affordable because verification is SHA-256 over a few hundred bytes —
 * the expense is reading the rows, which is I/O-bound and parallelises per
 * ticket, exactly as §3.7 says the per-ticket chain allows. <b>When a run stops
 * fitting its window, the answer is a resume cursor</b> — verify until a
 * deadline, record where it got to, continue there tomorrow — which keeps
 * complete coverage and trades detection latency for time. Written down here
 * rather than built, because the machinery costs nothing today.
 *
 * <h2>Failures never escape</h2>
 *
 * <p>One ticket that cannot be verified must not end the run: the remaining
 * tickets are the ones the alarm has not yet checked. A ticket that throws is
 * logged and counted, and the run continues.
 */
@Component
class ChainVerificationScanner {

    private static final Logger log = LoggerFactory.getLogger(ChainVerificationScanner.class);

    /**
     * Wide enough to keep the database busy, narrow enough that a nightly job
     * does not starve the outbox worker sharing this process and its pool.
     */
    private static final int WORKERS = 4;

    private final ChainScanRepository tickets;
    private final ChainVerifier verifier;
    private final ChainBreakAlert alert;

    ChainVerificationScanner(ChainScanRepository tickets,
                             ChainVerifier verifier,
                             ChainBreakAlert alert) {
        this.tickets = tickets;
        this.verifier = verifier;
        this.alert = alert;
    }

    /**
     * 02:00 by default — after the day's writes and before anyone reads the
     * morning dashboard, so a break is found while there is a working day left
     * to act on it.
     */
    @Scheduled(cron = "${edutrack.chain.verify-cron:0 0 2 * * *}")
    @SchedulerLock(name = "chainVerification", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    public void verifyNightly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("chain verification: run failed, retrying at the next schedule", e);
        }
    }

    /** @return every break found, for the tests and for {@link #verifyNightly}'s log */
    List<ChainBreak> run() {
        long startedAt = System.nanoTime();
        List<Long> ids = tickets.allTicketIds();
        List<ChainBreak> breaks = Collections.synchronizedList(new ArrayList<>());
        int failed = 0;

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<List<ChainBreak>>> futures = new ArrayList<>(ids.size());
            for (Long ticketId : ids) {
                futures.add(pool.submit(() -> verifier.verify(ticketId)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    breaks.addAll(futures.get(i).get());
                } catch (ExecutionException e) {
                    // Counted, not rethrown. The tickets after this one are
                    // precisely the ones nothing has checked yet.
                    failed++;
                    log.error("chain verification: ticket {} could not be verified",
                            ids.get(i), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("chain verification interrupted", e);
                }
            }
        } finally {
            pool.shutdown();
        }

        long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
        if (failed > 0) {
            // Distinct from a break: these chains were not verified at all, so
            // the run's silence about them means nothing.
            log.error("chain verification: {} of {} tickets could not be verified", failed, ids.size());
        }
        log.info("chain verification: {} tickets in {}s, {} breaks", ids.size(), seconds, breaks.size());

        alert.raise(breaks, ids.size());
        return breaks;
    }
}
