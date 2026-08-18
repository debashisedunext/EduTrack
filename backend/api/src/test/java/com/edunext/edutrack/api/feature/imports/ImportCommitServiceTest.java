package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-035 · what step 5 does before it starts writing, and what it refuses.
 *
 * <p>The commit is the one route in this feature where being wrong changes the
 * client master, so the assertions here are mostly about the order of the checks
 * and about what is <em>not</em> trusted. Three of them are the load-bearing
 * ones:
 *
 * <ul>
 *   <li>the rows written are the server's own verdicts, not anything the caller
 *       could nominate;
 *   <li>the staging entry is released before the response, so the same
 *       {@code uploadId} cannot be committed twice;
 *   <li>a refusal leaves both the staging entry and the database untouched.
 * </ul>
 *
 * <p>The executor is same-thread, so the job has finished by the time
 * {@code commit()} returns and the counters can be asserted without waiting on
 * anything. What that gives up — that the response really is sent before the
 * work — is asserted separately in {@link #answersBeforeTheJobFinishes()} with a
 * real pool.
 */
class ImportCommitServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private static final String SHEET = "Clients";

    private TestImportSchema schema;
    private InMemoryImportStagingStore staging;
    private RecordingBatches batches;
    private InMemoryImportReportStore reportStore;
    private ImportCommitService service;

    @BeforeEach
    void setUp() {
        schema = new TestImportSchema().writable();
        staging = new InMemoryImportStagingStore(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30), 20);
        batches = new RecordingBatches();
        reportStore = new InMemoryImportReportStore();
        service = serviceWith(sameThread());
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the writable rows are upserted and the batch completes")
    void commitsTheWritableRows() {
        UUID uploadId = stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Code", "NORTH", "Name", "Northwind"),
                row(4, "Name", "No code, rejected"));

        ImportDtos.Batch batch = service.commit("widgets", request(uploadId), 7L);

        assertThat(schema.written).extracting(row -> row.get("code"))
                .containsExactly("ACME", "NORTH");
        // Stamped on the write, which is what makes B-037's reversal possible.
        assertThat(schema.lastBatchId()).isEqualTo(batch.batchId());

        assertThat(batches.stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(batches.stored.getCreatedRows()).isEqualTo(2);
        assertThat(batches.stored.getUpdatedRows()).isZero();
        // The rejected row was counted at open, before the job ran — so a run
        // that dies on its first row still reports the size of the problem.
        assertThat(batches.stored.getRejectedRows()).isEqualTo(1);
        assertThat(batches.stored.getTotalRows()).isEqualTo(3);
        assertThat(batches.stored.getImportedBy()).isEqualTo(7L);
        assertThat(batches.stored.getEntity()).isEqualTo("WIDGET");
    }

    @Test
    @DisplayName("an existing key updates rather than creating a second row")
    void updatesRatherThanDuplicating() {
        // The rule the whole feature is judged on, at the level this test can
        // reach it: the verdict decides which counter moves, and both verdicts
        // go through the same upsert. ClientImportUpsertIT proves the other half
        // — that the upsert really is one against MySQL.
        schema = new TestImportSchema("ACME").writable();
        service = serviceWith(sameThread());

        UUID uploadId = stage(
                row(2, "Code", "ACME", "Name", "Acme, renamed"),
                row(3, "Code", "NEW", "Name", "Brand new"));

        service.commit("widgets", request(uploadId), null);

        assertThat(batches.stored.getUpdatedRows()).isEqualTo(1);
        assertThat(batches.stored.getCreatedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("the response carries the batch as queued, not the finished counts")
    void answersWithTheHandleNotTheOutcome() {
        // 202 means "accepted", and the counters in that body are the starting
        // ones. A client reading `created` off this response would show a
        // finished-looking zero; what it is for is the batchId to poll.
        ImportCommitService queued = serviceWith(neverRuns());
        UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));

        ImportDtos.Batch batch = queued.commit("widgets", request(uploadId), null);

        assertThat(batch.status()).isEqualTo("QUEUED");
        assertThat(batch.created()).isZero();
        assertThat(batch.processed()).isZero();
        assertThat(batch.total()).isEqualTo(1);
        assertThat(batch.errorReportUrl()).isNull();
    }

    @Test
    @DisplayName("the response is sent before the job has finished")
    void answersBeforeTheJobFinishes() throws Exception {
        // The property the same-thread executor above gives up, asserted once
        // with a real pool: if the commit were synchronous, a 5,000-row file
        // would hold the connection for the length of the import and the client
        // told to poll for progress would time out before it learned what to
        // poll.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);

        BlockingImportSchema blocking = new BlockingImportSchema(started, release);

        try (ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            ImportCommitService async = new ImportCommitService(
                    new ImportRequestResolver(new ImportSchemaRegistry(List.of(blocking)), staging),
                    new ImportValidationEngine(), batches,
                    new ImportCommitRunner(batches, reports()), pool,
                    new ImportCommitConfig.ImportCommitCeiling(8));

            UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));
            ImportDtos.Batch batch = async.commit("widgets", request(uploadId), null);

            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(batch.batchId()).isNotNull();
            assertThat(batches.stored.getStatus()).isNotEqualTo(ImportBatchStatus.COMPLETED);
            release.countDown();
        }
    }

    // ── what is not trusted ─────────────────────────────────────────────────

    @Test
    @DisplayName("the verdicts are re-derived — the request carries none and cannot")
    void reDerivesTheVerdicts() {
        // The security property of this step. ImportDtos.CommitRequest has no
        // field for a preview, so the only way a caller could nominate rows
        // would be for this test to be able to construct one. It cannot, and
        // that is the assertion: the rows written are exactly what a fresh dry
        // run over the same upload and mapping judges writable.
        UUID uploadId = stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Code", "acme", "Name", "Same code, different case"),
                row(4, "Code", "TOO LONG A CODE", "Name", "Rejected"));

        ImportPreview preview = new ImportValidationEngine().validate(schema, mapped(uploadId));
        service.commit("widgets", request(uploadId), null);

        assertThat(schema.written).extracting(row -> row.get("code"))
                .containsExactlyElementsOf(preview.writable().stream()
                        .map(verdict -> verdict.values().get("code")).toList());
    }

    @Test
    @DisplayName("the staging entry is released, so the same upload cannot be committed twice")
    void consumesTheStagedUpload() {
        UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));

        service.commit("widgets", request(uploadId), null);

        assertThat(staging.find(uploadId)).isEmpty();
        // The second press of a button that did not visibly respond, or a
        // retried request after a timeout. Refused as an unavailable upload,
        // which is the honest answer and also the one that stops the file being
        // written twice.
        assertThatThrownBy(() -> service.commit("widgets", request(uploadId), null))
                .isInstanceOf(ImportUploadNotAvailableException.class);
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Nested
    class Refusals {

        @Test
        @DisplayName("the same four refusals as step 4, from the same resolver")
        void sharesStepFoursRefusals() {
            UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));

            assertThatThrownBy(() -> service.commit("nonesuch", request(uploadId), null))
                    .isInstanceOf(UnknownImportSchemaException.class);
            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(UUID.randomUUID(), null,
                            Map.of("code", "Code", "name", "Name"), null), null))
                    .isInstanceOf(ImportUploadNotAvailableException.class);
            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(uploadId, null, Map.of("code", "Code"), null), null))
                    .isInstanceOf(IncompleteMappingException.class);
            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(uploadId, null,
                            Map.of("code", "Code", "name", "Name", "email", "Nope"), null), null))
                    .isInstanceOf(UnknownSourceColumnException.class);
            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(uploadId, null,
                            Map.of("code", "Code", "name", "Name", "invented", "Name"), null), null))
                    .isInstanceOf(UnknownImportFieldException.class);
        }

        @Test
        @DisplayName("a refused commit writes nothing and leaves the upload staged")
        void refusalsAreFree() {
            UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));

            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(uploadId, null, Map.of("code", "Code"), null), null))
                    .isInstanceOf(IncompleteMappingException.class);

            // No batch row, nothing written, and the file still staged — so the
            // user fixes one dropdown and presses the button again rather than
            // re-uploading.
            assertThat(batches.stored).isNull();
            assertThat(schema.written).isEmpty();
            assertThat(staging.find(uploadId)).isPresent();
        }

        @Test
        @DisplayName("a file with nothing writable is refused, not completed with four zeroes")
        void nothingToCommit() {
            UUID uploadId = stage(
                    row(2, "Name", "No code"),
                    row(3, "Code", "TOO LONG A CODE", "Name", "Rejected"));

            assertThatThrownBy(() -> service.commit("widgets", request(uploadId), null))
                    .isInstanceOf(NothingToCommitException.class)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            NothingToCommitException.class))
                    .satisfies(e -> {
                        assertThat(e.rejected()).isEqualTo(2);
                        assertThat(e.totalRows()).isEqualTo(2);
                    });

            // The point of refusing rather than accepting: import_batches is
            // B-037's audit trail, and a row claiming a file was imported when
            // nothing was is a false entry in it.
            assertThat(batches.stored).isNull();
        }

        @Test
        @DisplayName("skipRejected:false refuses the whole file when any row is bad")
        void allOrNothing() {
            UUID uploadId = stage(
                    row(2, "Code", "ACME", "Name", "Perfectly fine"),
                    row(3, "Name", "No code"));

            assertThatThrownBy(() -> service.commit("widgets",
                    new ImportDtos.CommitRequest(uploadId, null,
                            Map.of("code", "Code", "name", "Name"), false), null))
                    .isInstanceOf(RejectedRowsPresentException.class);

            assertThat(schema.written).isEmpty();
            assertThat(batches.stored).isNull();
            // Still staged, so flipping the flag and pressing again is one click
            // rather than a re-upload.
            assertThat(staging.find(uploadId)).isPresent();
        }

        @Test
        @DisplayName("an omitted skipRejected takes the contract's default of true")
        void skipRejectedDefaultsToTrue() {
            UUID uploadId = stage(
                    row(2, "Code", "ACME", "Name", "Perfectly fine"),
                    row(3, "Name", "No code"));

            service.commit("widgets", request(uploadId), null);

            assertThat(schema.written).hasSize(1);
        }

        @Test
        @DisplayName("a full queue is a 503, and the batch it opened is marked FAILED")
        void queueFull() {
            ImportCommitService saturated = serviceWith(alwaysRejects());
            UUID uploadId = stage(row(2, "Code", "ACME", "Name", "Acme"));

            assertThatThrownBy(() -> saturated.commit("widgets", request(uploadId), null))
                    .isInstanceOf(ImportCommitQueueFullException.class)
                    .hasMessageContaining("Nothing of this file has been written");

            // FAILED rather than deleted: a refused attempt that left no trace is
            // indistinguishable from an attempt nobody made, and every attempt
            // being identified is what import_batches is for.
            assertThat(batches.stored.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        }
    }

    // ── the job itself ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a row that fails at write time costs one row, not the run")
    void oneBadRowDoesNotLoseTheRest() {
        // The realistic cause is a constraint no validator declares — a column
        // widened in the master since the registration was written. Discarding
        // the other rows over it would throw away work the user approved and
        // watched land.
        schema = new TestImportSchema().failingOn("NORTH");
        service = serviceWith(sameThread());

        UUID uploadId = stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Code", "NORTH", "Name", "Northwind"),
                row(4, "Code", "ZED", "Name", "Zed"));

        service.commit("widgets", request(uploadId), null);

        assertThat(schema.written).extracting(row -> row.get("code"))
                .containsExactly("ACME", "ZED");
        assertThat(batches.stored.getCreatedRows()).isEqualTo(2);
        assertThat(batches.stored.getRejectedRows()).isEqualTo(1);
        // COMPLETED, not FAILED. The run finished; one row of it did not, and
        // B-036's error report is how the user recovers that row.
        assertThat(batches.stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
    }

    // ── the error report · B-036 ────────────────────────────────────────────

    @Test
    @DisplayName("the report holds every row the run did not write, in file order")
    void theReportHoldsEveryUnwrittenRow() throws Exception {
        // Three ways a row fails to land, and the report is the only place the
        // user ever sees all three together: the dry run rejected row 4, found
        // row 5 duplicated within the file, and the database refused row 3 after
        // the preview had approved it.
        schema = new TestImportSchema().failingOn("NORTH");
        service = serviceWith(sameThread());

        UUID uploadId = stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Code", "NORTH", "Name", "Northwind"),
                row(4, "Name", "No code at all"),
                row(5, "Code", "ACME", "Name", "Acme again"));

        service.commit("widgets", request(uploadId), null);

        assertThat(reportRowNumbers()).containsExactly("3", "4", "5");
    }

    @Test
    @DisplayName("the write failure's reason names the import, not the JDBC exception")
    void aWriteFailureReadsLikeASentence() throws Exception {
        // The exception message carries a constraint name, a table name and
        // sometimes the SQL. None of that helps the person reading the
        // spreadsheet, and all of it is internal detail on its way into a file
        // that gets emailed around.
        schema = new TestImportSchema().failingOn("NORTH");
        service = serviceWith(sameThread());

        service.commit("widgets", request(stage(
                row(2, "Code", "NORTH", "Name", "Northwind"))), null);

        String reason = reportReasons().getFirst();
        assertThat(reason).contains("the database refused this row");
        assertThat(reason).doesNotContain("simulated constraint violation");
    }

    /**
     * <b>The ordering the whole feature depends on.</b>
     *
     * <p>A client stops polling the instant it reads a terminal status, so a
     * report stamped in a later write is one the screen that wanted it has
     * already given up on. Asserted at the seam rather than through the clock:
     * the key must be present <em>on the same call</em> that sets COMPLETED.
     */
    @Test
    @DisplayName("the report key arrives on the same write that makes the run terminal")
    void theReportIsWrittenBeforeTheStatusIs() {
        service.commit("widgets", request(stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Name", "No code"))), null);

        assertThat(batches.reportKeyAtFinish).isEqualTo("imports/WIDGET/4242/errors.xlsx");
        assertThat(batches.statusWhenReportKeyWritten).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(batches.stored.getErrorReportKey()).isNotNull();
    }

    @Test
    @DisplayName("a run with nothing rejected leaves no report and no key")
    void aCleanRunHasNoReport() {
        service.commit("widgets", request(stage(
                row(2, "Code", "ACME", "Name", "Acme"))), null);

        assertThat(batches.stored.getErrorReportKey()).isNull();
        assertThat(reportStore.objects()).isEmpty();
    }

    @Test
    @DisplayName("an object store that is down costs the report and completes the import")
    void aStorageFailureDoesNotFailTheRun() {
        reportStore.failNext();

        service.commit("widgets", request(stage(
                row(2, "Code", "ACME", "Name", "Acme"),
                row(3, "Name", "No code"))), null);

        // The rows are written. Failing the batch over a bucket would report the
        // wrong thing to the user and to whoever reads the import history.
        assertThat(schema.written).hasSize(1);
        assertThat(batches.stored.getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
        assertThat(batches.stored.getErrorReportKey()).isNull();
    }

    @Test
    @DisplayName("counters are flushed while the run is in progress, not only at the end")
    void flushesProgress() {
        // Otherwise the progress bar reads zero for the length of the import and
        // then jumps, which is indistinguishable from a job that has died.
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < ImportCommitRunner.FLUSH_EVERY * 2 + 5; i++) {
            rows.add(row(i + 2, "Code", "C" + i, "Name", "Row " + i));
        }

        service.commit("widgets", request(stage(rows.toArray(StagedRow[]::new))), null);

        // Two mid-run flushes plus the terminal write, and RUNNING was announced
        // before any of them.
        assertThat(batches.progressWrites).isEqualTo(2);
        assertThat(batches.sawRunning).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ImportCommitService serviceWith(ExecutorService executor) {
        return new ImportCommitService(
                new ImportRequestResolver(new ImportSchemaRegistry(List.of(schema)), staging),
                new ImportValidationEngine(),
                batches,
                new ImportCommitRunner(batches, reports()),
                executor,
                new ImportCommitConfig.ImportCommitCeiling(8));
    }

    /**
     * B-036 · a real report service over an in-memory store.
     *
     * <p>The writer and the service are the real ones, so a commit test walks
     * the same generation path production does — only the bucket is fake. A
     * mocked service would leave the one ordering this feature depends on
     * (the report is written before the status turns terminal) asserted nowhere.
     */
    private ImportErrorReportService reports() {
        return new ImportErrorReportService(
                new ImportSchemaRegistry(List.of(schema)),
                new ImportErrorReportWriter(),
                reportStore,
                null);
    }

    private List<ImportRow> mapped(UUID uploadId) {
        return new ImportRequestResolver(new ImportSchemaRegistry(List.of(schema)), staging)
                .resolve("widgets", uploadId, null, Map.of("code", "Code", "name", "Name"))
                .rows();
    }

    private UUID stage(StagedRow... rows) {
        StagedUpload upload = new StagedUpload(UUID.randomUUID(), "widgets.xlsx",
                List.of(SHEET), SHEET, List.of("Code", "Name"), List.of(rows), NOW);
        staging.stage(upload);
        return upload.uploadId();
    }

    /** The Row column of the stored report, read back through POI. */
    private List<String> reportColumn(int column) throws Exception {
        byte[] workbook = reportStore.objects().values().iterator().next();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook read =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                             new java.io.ByteArrayInputStream(workbook))) {
            org.apache.poi.ss.usermodel.Sheet sheet = read.getSheetAt(0);
            List<String> values = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                int index = column < 0 ? row.getLastCellNum() + column : column;
                values.add(row.getCell(index).getStringCellValue());
            }
            return values;
        }
    }

    private List<String> reportRowNumbers() throws Exception {
        return reportColumn(0);
    }

    private List<String> reportReasons() throws Exception {
        return reportColumn(-1);
    }

    private static StagedRow row(int number, String... headingsAndCells) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < headingsAndCells.length; i += 2) {
            cells.put(headingsAndCells[i], headingsAndCells[i + 1]);
        }
        return new StagedRow(number, cells);
    }

    private static ImportDtos.CommitRequest request(UUID uploadId) {
        return new ImportDtos.CommitRequest(uploadId, SHEET,
                Map.of("code", "Code", "name", "Name"), null);
    }

    /** Runs the job inline, so the assertions need no waiting. */
    private static ExecutorService sameThread() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                command.run();
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() {
                return List.of();
            }
            @Override public boolean isShutdown() {
                return false;
            }
            @Override public boolean isTerminated() {
                return false;
            }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    /** Accepts the submission and never runs it — a batch parked at QUEUED. */
    private static ExecutorService neverRuns() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) { }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() {
                return List.of();
            }
            @Override public boolean isShutdown() {
                return false;
            }
            @Override public boolean isTerminated() {
                return false;
            }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    private static ExecutorService alwaysRejects() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("queue full");
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() {
                return List.of();
            }
            @Override public boolean isShutdown() {
                return false;
            }
            @Override public boolean isTerminated() {
                return false;
            }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    /**
     * A registration whose write parks until it is released.
     *
     * <p>Its own class rather than a subclass of {@link TestImportSchema},
     * which is {@code final} on purpose: the AssertionError in its
     * {@code upsert} is the guarantee that the dry run never writes, and a
     * subclass that overrode it away would be a way around the one rule this
     * package enforces structurally.
     */
    private static final class BlockingImportSchema implements ImportSchemaDefinition {

        private final java.util.concurrent.CountDownLatch started;
        private final java.util.concurrent.CountDownLatch release;

        BlockingImportSchema(java.util.concurrent.CountDownLatch started,
                             java.util.concurrent.CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override public String key() {
            return "widgets";
        }

        @Override public String entityCode() {
            return "WIDGET";
        }

        @Override public List<ImportField> fields() {
            return TestImportSchema.FIELDS;
        }

        @Override public ImportField naturalKey() {
            return TestImportSchema.CODE;
        }

        @Override public Map<String, Map<String, String>> findExisting(java.util.Set<String> keys) {
            return Map.of();
        }

        @Override public void upsert(ImportRow row, Long importBatchId) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * {@link ImportBatchService} without a database.
     *
     * <p>A stub rather than a mock so the counters can be read back the way the
     * poll route reads them — the interesting assertions here are about the
     * numbers that end up on the row, and a verify-call-count would prove the
     * method was called without proving what it stored.
     */
    private static final class RecordingBatches extends ImportBatchService {

        private ImportBatch stored;
        private int progressWrites;
        private boolean sawRunning;

        RecordingBatches() {
            super(null);
        }

        @Override
        ImportBatch open(String entityCode, String fileName, int totalRows, int rejected, Long userId) {
            stored = new ImportBatch();
            stored.setId(4242L);
            stored.setEntity(entityCode);
            stored.setFileName(fileName);
            stored.setTotalRows(totalRows);
            stored.setRejectedRows(rejected);
            stored.setStatus(ImportBatchStatus.QUEUED);
            stored.setImportedBy(userId);
            return stored;
        }

        @Override
        void markRunning(long batchId) {
            sawRunning = true;
            stored.setStatus(ImportBatchStatus.RUNNING);
        }

        @Override
        void progress(long batchId, int created, int updated, int rejected) {
            progressWrites++;
            apply(created, updated, rejected);
        }

        @Override
        void finish(long batchId, ImportBatchStatus status, int created, int updated,
                    int rejected, String errorReportKey) {
            // B-036 · recorded in the order the real one writes them, so a test
            // can assert that the report existed by the time the status did.
            reportKeyAtFinish = errorReportKey;
            statusWhenReportKeyWritten = status;
            apply(created, updated, rejected);
            stored.setStatus(status);
            stored.setErrorReportKey(errorReportKey);
        }

        @Override
        void fail(long batchId) {
            stored.setStatus(ImportBatchStatus.FAILED);
        }

        String reportKeyAtFinish;
        ImportBatchStatus statusWhenReportKeyWritten;

        private void apply(int created, int updated, int rejected) {
            stored.setCreatedRows(created);
            stored.setUpdatedRows(updated);
            stored.setRejectedRows(rejected);
        }
    }
}
