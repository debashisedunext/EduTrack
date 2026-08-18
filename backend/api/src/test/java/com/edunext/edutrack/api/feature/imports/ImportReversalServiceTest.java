package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-037 · the rules of a reversal, with no database under them.
 *
 * <p>{@code ClientImportReversalIT} proves the deletes actually happen against a
 * real MySQL; this owns everything that is true of <em>any</em> registration —
 * which requests are refused, in what order, what the batch row records
 * afterwards, and the one number the response has to carry that nothing else
 * would tell the user.
 *
 * <p>The registration is {@link TestImportSchema}, not {@code ClientImportSchema},
 * for the reason that file gives: a service test that depended on the client
 * field list would break when somebody adds a column to the client master, and
 * would quietly stop proving that the service works for a schema it has never
 * heard of.
 */
class ImportReversalServiceTest {

    private ImportBatchRepository batches;
    private TestImportSchema schema;
    private ImportReversalService service;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchRepository.class);
        schema = new TestImportSchema();
        ImportBatchUserNames names = mock(ImportBatchUserNames.class);
        when(names.resolve(any())).thenReturn(Map.of());
        service = new ImportReversalService(
                new ImportSchemaRegistry(List.of(schema)),
                new ImportBatchService(batches, names));
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("a batch id that names no run is a 404, before anything else is considered")
        void unknownBatch() {
            when(batches.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reverse(999, 1L))
                    .isInstanceOf(ImportBatchNotFoundException.class);

            assertThat(schema.reversed).isEmpty();
        }

        /**
         * The refusal that protects the data rather than the record. A background
         * job is walking the file right now: reversing under it would have two
         * threads deleting and inserting the same rows, and the job would go on
         * creating rows the reversal had already counted.
         */
        @Test
        @DisplayName("a run still in flight is refused, and nothing is deleted")
        void runningBatch() {
            stored(7, ImportBatchStatus.RUNNING, null);

            assertThatThrownBy(() -> service.reverse(7, 1L))
                    .isInstanceOf(ImportBatchNotFinishedException.class)
                    .hasMessageContaining("running");

            assertThat(schema.reversed).isEmpty();
            verify(batches, never()).save(any());
        }

        @Test
        @DisplayName("a queued run is refused too — nothing has been written to take back")
        void queuedBatch() {
            stored(7, ImportBatchStatus.QUEUED, null);

            assertThatThrownBy(() -> service.reverse(7, 1L))
                    .isInstanceOf(ImportBatchNotFinishedException.class);
        }

        /**
         * <b>The refusal that protects the record.</b> A second reversal would
         * delete nothing — the rows are already gone — so succeeding is tempting.
         * It would also overwrite {@code reversed_at} and both counters with the
         * second attempt's zeroes, leaving the row claiming it was reversed just
         * now, by whoever pressed last, deleting nothing. That is a false entry in
         * the one table that exists to make bad imports traceable.
         */
        @Test
        @DisplayName("a second reversal is refused rather than quietly succeeding with zeroes")
        void alreadyReversed() {
            Instant when = Instant.parse("2026-08-18T09:02:00Z");
            ImportBatch batch = stored(7, ImportBatchStatus.COMPLETED, null);
            batch.setReversedAt(when);
            batch.setReversedRows(40);

            assertThatThrownBy(() -> service.reverse(7, 1L))
                    .isInstanceOf(ImportBatchAlreadyReversedException.class)
                    .extracting(e -> ((ImportBatchAlreadyReversedException) e).reversedAt())
                    .isEqualTo(when);

            assertThat(schema.reversed).isEmpty();
            verify(batches, never()).save(any());
        }

        /**
         * The order is asserted rather than left to chance: a reversed batch is
         * necessarily a finished one, so if the checks were the other way round a
         * caller holding a stale list would be told about the lifecycle when what
         * they need to hear is that their list is stale.
         */
        @Test
        @DisplayName("a reversed batch answers about the reversal, not about the lifecycle")
        void reversalCheckIsTheSpecificOne() {
            ImportBatch batch = stored(7, ImportBatchStatus.FAILED, null);
            batch.setReversedAt(Instant.parse("2026-08-18T09:02:00Z"));

            assertThatThrownBy(() -> service.reverse(7, 1L))
                    .isInstanceOf(ImportBatchAlreadyReversedException.class);
        }

        /**
         * Unreachable in a single-registration build, and kept because the
         * alternative to refusing is guessing which table to delete from.
         */
        @Test
        @DisplayName("a run written by a registration this build does not have is refused, not guessed at")
        void unregisteredEntity() {
            ImportBatch batch = stored(7, ImportBatchStatus.COMPLETED, null);
            batch.setEntity("GADGET");

            assertThatThrownBy(() -> service.reverse(7, 1L))
                    .isInstanceOf(ImportSchemaUnavailableException.class)
                    .hasMessageContaining("GADGET");

            assertThat(schema.reversed).isEmpty();
        }
    }

    @Nested
    @DisplayName("what it does")
    class Reversing {

        @Test
        @DisplayName("a completed run reverses, and the registration is asked by stored entity code")
        void completedRunReverses() {
            stored(7, ImportBatchStatus.COMPLETED, null);
            schema.reversing(new ImportReversal(List.of("A", "B", "C"), List.of()));

            ImportDtos.Reversal result = service.reverse(7, 1L);

            // Resolved by `entity`, never by a schema the caller named — otherwise
            // a caller could ask the client registration to reverse a batch of
            // resources.
            assertThat(schema.reversed).containsExactly(7L);
            assertThat(result.deleted()).containsExactly("A", "B", "C");
        }

        /**
         * The case this exists for. A run that died at row 314 left 313 rows in
         * the master that nobody approved the presence of — refusing to reverse it
         * would leave the worst outcome the feature has as the one it cannot clean
         * up.
         */
        @Test
        @DisplayName("a FAILED run is reversible — it is the one most likely to need it")
        void failedRunReverses() {
            stored(7, ImportBatchStatus.FAILED, null);
            schema.reversing(new ImportReversal(List.of("A"), List.of()));

            assertThat(service.reverse(7, 1L).deleted()).containsExactly("A");
        }

        @Test
        @DisplayName("the batch records both counts and who reversed it")
        void stampsTheBatch() {
            stored(7, ImportBatchStatus.COMPLETED, null);
            schema.reversing(new ImportReversal(
                    List.of("A", "B"),
                    List.of(new ImportReversal.Retained("C", "Kept — 3 tickets."))));

            service.reverse(7, 42L);

            ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
            verify(batches).save(saved.capture());
            assertThat(saved.getValue().getReversedRows()).isEqualTo(2);
            assertThat(saved.getValue().getRetainedRows()).isEqualTo(1);
            assertThat(saved.getValue().getReversedBy()).isEqualTo(42L);
            assertThat(saved.getValue().getReversedAt()).isNotNull();
        }

        /**
         * The status is left where the run left it. There is no {@code REVERSED}
         * status on purpose: {@code status} says how the run ended, and
         * overwriting it would collapse "completed with 6 rejections, later
         * reversed" into one word — and would leave the error report still sitting
         * in {@code error_report_key} belonging to a run whose status no longer
         * explains why it has one.
         */
        @Test
        @DisplayName("the run's own status survives the reversal")
        void statusIsUntouched() {
            stored(7, ImportBatchStatus.COMPLETED, "reports/7.xlsx");
            schema.reversing(new ImportReversal(List.of("A"), List.of()));

            service.reverse(7, 1L);

            ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
            verify(batches).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ImportBatchStatus.COMPLETED);
            assertThat(saved.getValue().getErrorReportKey()).isEqualTo("reports/7.xlsx");
        }

        /**
         * <b>The number that makes the promise honest.</b> Somebody who imported
         * 412 rows and reads "12 deleted" is owed the sentence explaining the
         * other 400 — they were updates, the batch id is stamped on insert only,
         * and there is no before image to restore them from.
         */
        @Test
        @DisplayName("rows the run updated are reported as not reverted, never as deleted")
        void updatesAreReportedNotReverted() {
            ImportBatch batch = stored(7, ImportBatchStatus.COMPLETED, null);
            batch.setCreatedRows(12);
            batch.setUpdatedRows(400);
            schema.reversing(new ImportReversal(
                    List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L"),
                    List.of()));

            ImportDtos.Reversal result = service.reverse(7, 1L);

            assertThat(result.deleted()).hasSize(12);
            assertThat(result.updatedRowsNotReverted()).isEqualTo(400);
        }

        /**
         * Retained is an outcome, not a failure. Failing the whole reversal
         * because one client acquired a ticket is unhelpful, and deleting the
         * ticket's client to get the count to zero is worse.
         */
        @Test
        @DisplayName("retained rows come back named, with a reason, and the reversal still succeeds")
        void retainedRowsAreNamed() {
            stored(7, ImportBatchStatus.COMPLETED, null);
            schema.reversing(new ImportReversal(
                    List.of("ACME"),
                    List.of(new ImportReversal.Retained("ZENITH",
                            "Kept — 3 tickets have been raised against this client since the import."))));

            ImportDtos.Reversal result = service.reverse(7, 1L);

            assertThat(result.deleted()).containsExactly("ACME");
            assertThat(result.retained())
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.naturalKey()).isEqualTo("ZENITH");
                        assertThat(row.reason()).contains("3 tickets");
                    });
        }

        /**
         * Accepted rather than refused. A run that created nothing reverses to "0
         * deleted", which is honest and harmless — and refusing it would mean the
         * screen had to explain a disabled button on a run that looks perfectly
         * ordinary.
         */
        @Test
        @DisplayName("a run that created nothing reverses to zero rather than being refused")
        void runThatCreatedNothing() {
            stored(7, ImportBatchStatus.COMPLETED, null);

            ImportDtos.Reversal result = service.reverse(7, 1L);

            assertThat(result.deleted()).isEmpty();
            assertThat(result.retained()).isEmpty();
            assertThat(schema.reversed).containsExactly(7L);
        }

        /**
         * Best-effort, like {@code imported_by} beside it: a caller the
         * {@code dev-noauth} profile cannot identify records a null actor rather
         * than being refused an operation their role permits.
         */
        @Test
        @DisplayName("an unidentifiable caller records no actor rather than being refused")
        void unidentifiableCaller() {
            stored(7, ImportBatchStatus.COMPLETED, null);

            service.reverse(7, null);

            ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
            verify(batches).save(saved.capture());
            assertThat(saved.getValue().getReversedBy()).isNull();
        }
    }

    private ImportBatch stored(long id, ImportBatchStatus status, String reportKey) {
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setEntity("WIDGET");
        batch.setStatus(status);
        batch.setErrorReportKey(reportKey);
        when(batches.findById(id)).thenReturn(Optional.of(batch));
        return batch;
    }
}
