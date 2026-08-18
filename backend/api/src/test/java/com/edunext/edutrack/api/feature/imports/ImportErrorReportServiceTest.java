package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-036 · when the error report exists, and what happens when it cannot.
 *
 * <p>Two halves, and the second is the one worth having. Generating a workbook
 * is covered by {@code ImportErrorReportWriterTest}; what is decided here is
 * <b>whether a failure to store one is allowed to cost anything</b>. It is not:
 * the rows are already written by the time this runs, and failing a batch that
 * imported four hundred clients correctly because a bucket was unreachable would
 * report the wrong thing to the user and to whoever reads the history.
 */
class ImportErrorReportServiceTest {

    private TestImportSchema schema;
    private InMemoryImportReportStore store;
    private ImportBatchRepository batches;
    private ImportErrorReportService service;

    @BeforeEach
    void setUp() {
        schema = new TestImportSchema();
        store = new InMemoryImportReportStore();
        batches = mock(ImportBatchRepository.class);
        service = new ImportErrorReportService(
                new ImportSchemaRegistry(List.of(schema)),
                new ImportErrorReportWriter(),
                store,
                batches);
    }

    // ── generation ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("generating")
    class Generating {

        @Test
        @DisplayName("stores a workbook and answers the key")
        void storesTheReport() {
            String key = service.generate(schema, 412, List.of(rejected(5, "Code: required")));

            assertThat(key).isEqualTo("imports/WIDGET/412/errors.xlsx");
            assertThat(store.objects()).containsOnlyKeys(key);
            // A real .xlsx is a zip, and its first two bytes say so. Enough to
            // prove the writer ran and its bytes reached the store unmangled;
            // what is inside them is ImportErrorReportWriterTest's subject.
            assertThat(store.objects().get(key)).startsWith((byte) 'P', (byte) 'K');
        }

        @Test
        @DisplayName("a run with nothing rejected produces no report at all")
        void noFailuresNoReport() {
            // Null rather than an empty workbook: `errorReportUrl` is how the
            // screen decides whether to enable the button, and a report of a
            // header row is a download that wastes the one click this step
            // offers.
            assertThat(service.generate(schema, 412, List.of())).isNull();
            assertThat(store.objects()).isEmpty();
        }

        /**
         * <b>The assertion this class exists for.</b>
         *
         * <p>By the time this runs the client master has already been written.
         * An object store that is down is an operational problem, not a reason
         * to tell the user their import failed — so it costs the report and
         * nothing else, and the caller carries on to mark the run
         * {@code COMPLETED}.
         */
        @Test
        @DisplayName("an unreachable object store costs the report, never the import")
        void aStorageFailureIsSwallowed() {
            store.failNext();

            assertThat(service.generate(schema, 412, List.of(rejected(5, "Code: required"))))
                    .isNull();
        }
    }

    // ── download ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("downloading")
    class Downloading {

        @Test
        @DisplayName("answers the stored bytes under the run's own file name")
        void answersTheStoredWorkbook() {
            String key = service.generate(schema, 412, List.of(rejected(5, "Code: required")));
            when(batches.findById(412L)).thenReturn(Optional.of(
                    batch(412, ImportBatchStatus.COMPLETED, key)));

            ImportErrorReportService.Report report = service.download(412);

            assertThat(report.workbook()).isEqualTo(store.objects().get(key));
            // Named per run, so two reports in a Downloads folder say which
            // import each came from — see ImportErrorReportWriter.fileName.
            assertThat(report.fileName()).isEqualTo("widgets-import-errors-412.xlsx");
        }

        @Test
        @DisplayName("a batch id that names no run is the poll's own 404")
        void unknownBatch() {
            when(batches.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.download(999))
                    .isInstanceOf(ImportBatchNotFoundException.class);
        }

        @Test
        @DisplayName("a run with no report is a different 404, carrying its status")
        void noReportOnTheBatch() {
            // The realistic caller is a bookmark or a client that ignored a null
            // errorReportUrl. The status is on the refusal because it is what
            // makes the sentence honest — RUNNING means "not yet" and COMPLETED
            // means "there is none".
            when(batches.findById(412L)).thenReturn(Optional.of(
                    batch(412, ImportBatchStatus.RUNNING, null)));

            assertThatThrownBy(() -> service.download(412))
                    .isInstanceOf(ImportErrorReportUnavailableException.class)
                    .hasMessageContaining("RUNNING");
        }

        @Test
        @DisplayName("a key whose object has gone answers the same 404, not a 500")
        void theObjectExpired() {
            // A lifecycle rule expired it, or the bucket was emptied. The batch
            // row still says a report was written; the honest answer is that it
            // is no longer there.
            when(batches.findById(412L)).thenReturn(Optional.of(
                    batch(412, ImportBatchStatus.COMPLETED, "imports/WIDGET/412/errors.xlsx")));

            assertThatThrownBy(() -> service.download(412))
                    .isInstanceOf(ImportErrorReportUnavailableException.class);
        }

        @Test
        @DisplayName("a run whose registration has since been removed is still downloadable")
        void anUnregisteredEntityStillDownloads() {
            // Removing a registration must not destroy the record of every run it
            // ever made. The name loses the schema's URL segment and nothing
            // else.
            String key = store.put(77, "GONE", new byte[] {'P', 'K'});
            when(batches.findById(77L)).thenReturn(Optional.of(
                    batch(77, ImportBatchStatus.COMPLETED, key)));

            assertThat(service.download(77).fileName()).isEqualTo("gone-import-errors-77.xlsx");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ImportRowVerdict rejected(int rowNumber, String reason) {
        return new ImportRowVerdict(rowNumber, ImportVerdict.REJECTED, reason,
                Map.of("code", "ACME"));
    }

    private static ImportBatch batch(long id, ImportBatchStatus status, String reportKey) {
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setEntity(reportKey != null && reportKey.contains("GONE") ? "GONE" : "WIDGET");
        batch.setStatus(status);
        batch.setErrorReportKey(reportKey);
        return batch;
    }
}
