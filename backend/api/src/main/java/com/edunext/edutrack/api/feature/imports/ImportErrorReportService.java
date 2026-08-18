package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * B-036 · blueprint §4B.3 step 5's error report, generated and served.
 *
 * <p>"A downloadable error report (.xlsx with a Reason column appended) is
 * produced for every rejected row so the user can fix and re-upload just those."
 * {@link ImportErrorReportWriter} decides what the file looks like; this decides
 * when it exists and who can read it.
 *
 * <h2>It is generated during the run, because that is the last moment the rows
 * exist</h2>
 *
 * <p>The rejected rows are never persisted. B-035 releases the staging entry
 * before the job starts and the preview is re-derived rather than stored, so
 * once {@link ImportCommitRunner} has walked its list there is nothing left
 * anywhere to build a report from. There is no "generate it on download" option
 * to weigh — the data is gone by then.
 *
 * <h2>A report that cannot be stored costs a report, never an import</h2>
 *
 * <p>{@link #generate} swallows a storage failure and answers null. The rows are
 * already written; failing the run at this point would mark a batch
 * {@code FAILED} that wrote four hundred clients correctly, and would tell the
 * user their import broke when what broke was a convenience attached to it. The
 * key stays null, {@code errorReportUrl} stays null, and the step-5 screen
 * leaves its button disabled with an honest sentence beside it — which is the
 * state that screen has been in since B-035 and is understood.
 *
 * <p>It is logged at {@code warn} with the batch id, because an object store
 * that is refusing writes is an operational fact somebody has to see, and this
 * is the one place in the feature that notices.
 */
@Service
class ImportErrorReportService {

    private static final Logger log = LoggerFactory.getLogger(ImportErrorReportService.class);

    private final ImportSchemaRegistry registry;
    private final ImportErrorReportWriter writer;
    private final ImportReportStore store;
    private final ImportBatchRepository batches;

    ImportErrorReportService(ImportSchemaRegistry registry,
                             ImportErrorReportWriter writer,
                             ImportReportStore store,
                             ImportBatchRepository batches) {
        this.registry = registry;
        this.writer = writer;
        this.store = store;
        this.batches = batches;
    }

    /**
     * Write the report for one run and answer its storage key.
     *
     * <p>Called by {@link ImportCommitRunner} <b>before the batch is marked
     * terminal</b>, which is the ordering the whole feature depends on: a client
     * stops polling the moment it reads {@code COMPLETED}, so a key stamped
     * after the status is a report nobody is still looking for.
     *
     * @param failures every row the run did not write, in file order — the dry
     *                 run's rejections and in-file duplicates, plus anything that
     *                 broke at write time. All three are rows the user's file
     *                 contained and the client master did not receive, which is
     *                 the only distinction that matters to somebody fixing a
     *                 spreadsheet
     * @return the key to store on the batch, or {@code null} for a run with
     *         nothing to report or a report that could not be stored. Null
     *         rather than an empty workbook: a report offering a download of
     *         nothing but a header row is a button that wastes a click
     */
    String generate(ImportSchemaDefinition schema, long batchId, List<ImportRowVerdict> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        try {
            return store.put(batchId, schema.entityCode(), writer.write(schema, failures));
        } catch (RuntimeException e) {
            log.warn("Import batch {} — the error report for {} rejected rows could not be stored: {}",
                    batchId, failures.size(), e.toString());
            return null;
        }
    }

    /**
     * The download — the bytes and the name the browser should save them under.
     *
     * <p>Streamed through this API rather than handed out as a presigned URL,
     * which is where it differs from §4B.4's attachments and deliberately so.
     * An error report is a verbatim extract of the client master — names,
     * addresses, contract dates — and a signed URL is a bearer credential that
     * outlives the screen that minted it, in a browser history, a chat paste and
     * a proxy log. The file is small and read once, so the cost of proxying it is
     * a few hundred kilobytes and the benefit is that {@code master.write} is
     * checked at the moment of reading rather than at the moment of linking.
     *
     * <h2>No {@code @Transactional}, on purpose</h2>
     *
     * <p>It was written with it, and {@link ImportMappingPresetService} records
     * the same decision for the same two reasons. This is one query, and
     * {@link ImportBatch} has no lazy association for a longer-lived
     * {@code EntityManager} to resolve — everything read here is a scalar on the
     * row. What the annotation <em>did</em> do was open an
     * {@code EntityManager} per call, which puts a live database between this
     * route and any test of it: {@code ImportErrorReportControllerTest} could
     * then not assert the media type or the {@code Content-Disposition} name
     * without MySQL running, and those are exactly the properties a client
     * depends on.
     *
     * @throws ImportBatchNotFoundException     the same 404 the progress poll
     *                                          answers, so an invented batch id
     *                                          reads identically on both routes
     * @throws ImportErrorReportUnavailableException the batch is real and its
     *                                          report is not there
     */
    Report download(long batchId) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new ImportBatchNotFoundException(batchId));

        String key = batch.getErrorReportKey();
        if (key == null || key.isBlank()) {
            // Three ways to get here and one answer, because the remedy is the
            // same for all of them: there is nothing to download. A run still
            // going, a run with no rejected row, and a run whose report could not
            // be stored. The status and the counters on the batch already
            // distinguish them, and a client reaching this route at all has
            // ignored a null errorReportUrl.
            throw new ImportErrorReportUnavailableException(batchId, batch.getStatus().name());
        }

        byte[] workbook = store.read(key)
                .orElseThrow(() -> new ImportErrorReportUnavailableException(
                        batchId, batch.getStatus().name()));

        return new Report(workbook, fileName(batch));
    }

    /**
     * {@code clients-import-errors-412.xlsx}, from the registration the run
     * belongs to.
     *
     * <p>Falls back to the stored entity code when the schema is no longer
     * registered — a report written by a registration a later release removed is
     * still downloadable, just under a plainer name. Refusing it instead would
     * make removing a registration destroy the record of every run it ever made.
     */
    private String fileName(ImportBatch batch) {
        return registry.byEntityCode(batch.getEntity())
                .map(schema -> ImportErrorReportWriter.fileName(schema, batch.getId()))
                .orElseGet(() -> batch.getEntity().toLowerCase(java.util.Locale.ROOT)
                        + "-import-errors-" + batch.getId() + ".xlsx");
    }

    /** The two things the route needs and nothing about where they came from. */
    record Report(byte[] workbook, String fileName) {
    }
}
