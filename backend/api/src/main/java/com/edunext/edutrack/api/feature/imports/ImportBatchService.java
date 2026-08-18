package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * B-035 · {@code import_batches}, read and written.
 *
 * <p>One class for both directions because it is one table and the two halves
 * are three lines each; splitting them would put the ETag's inputs in a
 * different file from the code that moves them.
 *
 * <h2>Every write is its own transaction, and that is the point</h2>
 *
 * <p>The commit job runs for as long as the file takes. A transaction spanning
 * it would hold a connection for minutes, would make the progress a client polls
 * invisible until the end (nothing is committed, so nothing is readable), and
 * would roll the whole run back over one bad row — which is precisely the
 * behaviour {@link ImportCommitRunner} exists to avoid.
 *
 * <p>So the job calls these one at a time, from a thread with no ambient
 * transaction, and each call commits before the next row is touched. The
 * counters a poll reads are therefore always a real prefix of the run rather
 * than a projection of one.
 *
 * <h2>Nothing here is append-only</h2>
 *
 * <p>Worth stating because the word "batch" invites the comparison.
 * {@code import_batches} is not one of CLAUDE.md's three hash-chained tables and
 * carries no A-008 trigger: it is a job record whose counters climb, and the
 * rows it explains — clients — are ordinary mutable master data. The append-only
 * rule is about {@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions}, none of which this feature touches.
 */
@Service
class ImportBatchService {

    /**
     * B-037 · how many runs the history panel returns.
     *
     * <p>The cap is here rather than on the caller because it is a property of
     * what the panel is for. {@code import_batches} only grows — a year of a busy
     * client master is thousands of rows — and "the imports" means the recent
     * ones by any reading: nobody scrolls back to March to find a batch to
     * reverse, they reverse the one they just ran and got wrong.
     *
     * <p>Fifty rather than ten, because the panel is also the audit trail. An
     * Admin asking "when did this client appear in the master?" is reading down
     * the list, not looking at the top of it.
     *
     * <p>Reported on the wire as {@code limit} rather than applied silently.
     * CLAUDE.md's rule about caps is that a bounded answer which looks unbounded
     * reads as "this is all of them" when it is not.
     */
    static final int HISTORY_LIMIT = 50;

    private final ImportBatchRepository batches;
    private final ImportBatchUserNames userNames;

    ImportBatchService(ImportBatchRepository batches, ImportBatchUserNames userNames) {
        this.batches = batches;
        this.userNames = userNames;
    }

    /**
     * Opens the run — {@code QUEUED}, before anything is submitted.
     *
     * <p>{@code totalRows} is every data row of the sheet and {@code rejected}
     * is what the dry run already refused, both stamped now rather than
     * discovered. That is what lets a progress bar start partly filled on a file
     * with bad rows instead of jumping at the end, and it means a run that dies
     * on its first row still says how big it was.
     */
    @Transactional
    ImportBatch open(String entityCode, String fileName, int totalRows, int rejected, Long userId) {
        ImportBatch batch = new ImportBatch();
        batch.setEntity(entityCode);
        batch.setFileName(fileName);
        batch.setTotalRows(totalRows);
        batch.setRejectedRows(rejected);
        batch.setStatus(ImportBatchStatus.QUEUED);
        batch.setImportedBy(userId);
        return batches.save(batch);
    }

    @Transactional
    void markRunning(long batchId) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setStatus(ImportBatchStatus.RUNNING);
            batches.save(batch);
        });
    }

    /**
     * A progress flush — absolute counts, never increments.
     *
     * <p>Absolute because the runner holds the truth and this is a projection of
     * it. An increment would have to be exactly-once to be correct, and a flush
     * that is retried, or one that raced another, would leave the row
     * permanently wrong with nothing to reconcile it against.
     */
    @Transactional
    void progress(long batchId, int created, int updated, int rejected) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setCreatedRows(created);
            batch.setUpdatedRows(updated);
            batch.setRejectedRows(rejected);
            batches.save(batch);
        });
    }

    /**
     * The terminal write.
     *
     * <p>{@code COMPLETED} is not a claim that nothing was rejected — see
     * {@link ImportBatchStatus}. A run that refused half the file and wrote the
     * rest completed, and B-036's error report is how the user recovers the
     * other half.
     *
     * <p><b>B-036 folded the error report key into this call rather than adding a
     * second one after it, and the ordering is the reason.</b> A client stops
     * polling the instant it reads a terminal status, so a key written in a later
     * transaction is a report the screen that wanted it has already stopped
     * asking for. One write settles the status, the counters and the report
     * together, which is also what makes {@code ImportDtos.Batch.etag} — computed
     * over all of them — move exactly once at the end of a run.
     *
     * @param errorReportKey the stored report, or {@code null} for a run with
     *                       nothing rejected or one whose report could not be
     *                       stored. Both leave {@code errorReportUrl} null, which
     *                       is what keeps the download button honestly disabled
     */
    @Transactional
    void finish(long batchId, ImportBatchStatus status,
                int created, int updated, int rejected, String errorReportKey) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setCreatedRows(created);
            batch.setUpdatedRows(updated);
            batch.setRejectedRows(rejected);
            batch.setStatus(status);
            batch.setErrorReportKey(errorReportKey);
            batches.save(batch);
        });
    }

    /** Used by the 503 path — the batch was opened and never ran. */
    @Transactional
    void fail(long batchId) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setStatus(ImportBatchStatus.FAILED);
            batches.save(batch);
        });
    }

    /**
     * <h2>No {@code @Transactional}, since B-037</h2>
     *
     * <p>It had one, and {@link ImportMappingPresetService} and
     * {@link ImportErrorReportService} both record the same removal for the same
     * two reasons. This is one query, and {@link ImportBatch} has no lazy
     * association for a longer-lived {@code EntityManager} to resolve —
     * everything {@link ImportDtos.Batch#of} reads is a scalar on the row.
     *
     * <p>What the annotation <em>did</em> do was open an {@code EntityManager}
     * per call, which puts a live database between these routes and any test of
     * them. That is not an abstract cost: it is why B-036 removed it from the
     * error-report read, and why B-037's two route tests could not otherwise
     * assert the history's shape or a reversal's refusals without MySQL running.
     *
     * <p><b>Every write in this class keeps its annotation</b>, including
     * {@link #markReversed}. Each is a real state change; the argument here is
     * only about reads that are a single statement and cannot be torn.
     *
     * <p>{@link #history} is the one read that is two queries — the page, then
     * the names — and it drops the annotation too. The pair is not one that can
     * be inconsistent: a user renamed between them renders under whichever name
     * they had, and an id that resolves to nothing renders unattributed, which is
     * a case the DTO already permits and the panel already handles.
     */
    ImportDtos.Batch find(long batchId) {
        return batches.findById(batchId)
                .map(ImportDtos.Batch::of)
                .orElseThrow(() -> new ImportBatchNotFoundException(batchId));
    }

    /**
     * B-037 · the entity itself, for the reversal — {@link #find} returns a
     * projection and a reversal has to read {@code status} and
     * {@code reversed_at} and then write four columns.
     *
     * <p>Package-private and named for its one caller rather than exposed as a
     * general "get the entity": everything else in this feature reads batches
     * through the DTO, which is what keeps {@code error_report_key} off the wire.
     */
    com.edunext.edutrack.domain.imports.ImportBatch load(long batchId) {
        return batches.findById(batchId)
                .orElseThrow(() -> new ImportBatchNotFoundException(batchId));
    }

    /**
     * B-037 · the import history for one registered schema — blueprint §4B.3's
     * "every import writes an {@code import_batch} row so a bad import can be
     * identified".
     *
     * <p>Until this method existed, <b>a batch id was known only to the browser
     * tab that started the run</b>. Close it and the import was unfindable: there
     * was no route that listed runs, so "identified" was true of the database and
     * of nothing a person could reach. {@code findByEntityOrderByCreatedAtDesc}
     * has been sitting in the repository since B-030 with its javadoc naming this
     * panel and no caller.
     *
     * <p>Per entity rather than across all of them, because the schema registry
     * is the feature's organising idea and the panel lives on one wizard: a
     * client Admin looking at S-34 is not asking about resource imports. The
     * ordering is {@code ix_import_batches_entity (entity, created_at)}, so it is
     * an index walk rather than a filesort.
     *
     * <p>Names are resolved in one query for the page — see
     * {@link ImportBatchUserNames} — and never on {@link #find}, which is polled
     * every two seconds for the length of a run.
     */
    ImportDtos.BatchList history(String entityCode) {
        List<com.edunext.edutrack.domain.imports.ImportBatch> rows =
                batches.findByEntityOrderByCreatedAtDesc(entityCode, PageRequest.of(0, HISTORY_LIMIT));

        Map<Long, String> names = userNames.resolve(
                rows.stream().map(com.edunext.edutrack.domain.imports.ImportBatch::getImportedBy).toList());

        return new ImportDtos.BatchList(
                entityCode,
                rows.stream()
                        .map(row -> ImportDtos.Batch.of(row, nameOf(names, row.getImportedBy())))
                        .toList(),
                HISTORY_LIMIT);
    }

    /**
     * {@code imported_by} is nullable and the map is immutable, so the null has
     * to be handled here rather than by {@code Map#get}.
     *
     * <p>{@code Map.of()} throws {@link NullPointerException} on a null key
     * rather than answering null — which turned "one run started by an
     * unidentifiable caller" into a 500 on the whole history panel, found by
     * {@code ClientImportReversalIT} against a real database. A null actor is
     * ordinary: {@code ImportCommitService} records the caller best-effort, so a
     * {@code dev-noauth} import legitimately has none.
     */
    private static String nameOf(Map<Long, String> names, Long userId) {
        return userId == null ? null : names.get(userId);
    }

    /**
     * B-037 · the reversal's own write — four columns, one transaction, after
     * the registration has already deleted what it could.
     *
     * <p><b>Stamped after the deletes rather than before them.</b> The order
     * matters on the one path where it can differ: a run that dies partway
     * through its deletions leaves {@code reversed_at} null, so the batch is
     * still reversible and running it again finishes the job — the deletes are
     * idempotent, because a client already gone is simply not in
     * {@code findByImportBatchId} the second time. Stamping first would mark a
     * half-reversed batch as done and refuse the retry that would fix it.
     *
     * <p>The row is <b>updated, never replaced or removed</b>. It is the audit
     * trail, and the counters and the error-report key it already holds describe
     * a run that genuinely happened whatever was later taken back.
     */
    @Transactional
    void markReversed(long batchId, int reversedRows, int retainedRows, Long userId) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setReversedAt(Instant.now());
            batch.setReversedBy(userId);
            batch.setReversedRows(reversedRows);
            batch.setRetainedRows(retainedRows);
            batches.save(batch);
        });
    }
}
