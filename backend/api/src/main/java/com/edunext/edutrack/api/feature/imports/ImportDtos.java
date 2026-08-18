package com.edunext.edutrack.api.feature.imports;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B-032 · the wire shapes for {@code feature/imports}.
 *
 * <p>One file per feature, like {@code ClientDtos}: the envelope wrappers are one
 * line each and giving each of them its own file makes the package harder to read
 * rather than easier.
 */
final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * {@code ImportUploadResponse.data} — what step 2 hands step 3.
     *
     * <p>Deliberately <b>not</b> the rows. A 5,000-row file would be several
     * megabytes of JSON that the mapping screen has no use for: B-033 maps
     * columns, and columns are the {@code headers} list. The rows stay staged
     * server-side and are next read by the dry run, which is the step that
     * actually looks at them.
     *
     * @param sheet    which sheet these headers describe. Stated rather than left
     *                 to be inferred as "the first" — re-posting with a chosen
     *                 sheet is how the selector works, and a client that assumed
     *                 would mislabel every read after the first
     * @param rowCount data rows, blank rows excluded, header excluded. This is the
     *                 number the user is told they are about to import, so it
     *                 counts the same rows the dry run will
     * @param suggestedMapping {@link HeaderMatcher}'s auto-match — target field →
     *                 source column, and every entry overridable at step 3
     */
    record Upload(
            UUID uploadId,
            String fileName,
            List<String> sheets,
            String sheet,
            List<String> headers,
            int rowCount,
            Map<String, String> suggestedMapping) {
    }

    record UploadResponse(Upload data) {
    }

    // ── B-033 · step 3 ──────────────────────────────────────────────────────

    /**
     * One column of a registration, as the mapping screen needs it.
     *
     * <p><b>This exists so the field list is declared once.</b> Step 3 has to
     * render a row per target field and decide which unmapped ones block Next,
     * and {@code required} is not derivable from anything step 2 returns. The
     * alternative was a copy of {@link ImportField}'s declarations in TypeScript
     * — which B-038 would then have to copy a second time, and which would drift
     * the first time somebody edits a registration.
     *
     * <p>A projection of {@link ImportField} rather than the record itself:
     * {@link ImportField#validators()} is a list of lambdas with no wire
     * representation, and serialising a schema definition directly is how an
     * internal type becomes a public contract by accident.
     *
     * @param naturalKey true for the one field that decides create-versus-update.
     *                   Always {@code required} too, but a separate flag because
     *                   the screen says different things about them: "rows are
     *                   matched on this column" is not the same warning as "this
     *                   column cannot be blank"
     */
    record SchemaField(
            String name,
            String header,
            boolean required,
            boolean naturalKey,
            ImportFieldType type,
            int maxLength,
            List<String> allowedValues,
            String example) {

        static SchemaField of(ImportField field, ImportField naturalKey) {
            return new SchemaField(
                    field.name(),
                    field.header(),
                    field.required(),
                    field.name().equals(naturalKey.name()),
                    field.type(),
                    field.maxLength(),
                    field.allowedValues(),
                    field.example());
        }
    }

    /** {@code ImportSchemaFieldsResponse.data}. */
    record SchemaFields(
            String schema,
            String entity,
            String naturalKey,
            List<SchemaField> fields) {

        static SchemaFields of(ImportSchemaDefinition definition) {
            ImportField naturalKey = definition.naturalKey();
            return new SchemaFields(
                    definition.key(),
                    definition.entityCode(),
                    naturalKey.name(),
                    definition.fields().stream()
                            .map(field -> SchemaField.of(field, naturalKey))
                            .toList());
        }
    }

    record SchemaFieldsResponse(SchemaFields data) {
    }

    /**
     * A saved column mapping — §4B.3's "presets can be saved and reused for the
     * next import".
     *
     * <p>Org-wide, not per user. {@code createdBy} is recorded in the table
     * because knowing who saved a mapping is useful, and is deliberately not on
     * the wire: it is not part of any key, nothing filters on it, and putting a
     * name beside a preset in a picker invites "is this one mine?" of a thing
     * that is nobody's.
     */
    record MappingPreset(
            long presetId,
            String name,
            Map<String, String> mapping,
            Instant updatedAt) {
    }

    record MappingPresetsResponse(List<MappingPreset> data) {
    }

    record MappingPresetResponse(MappingPreset data) {
    }

    /**
     * The save body.
     *
     * <p>Bean validation covers shape — a name somebody can pick out of a list,
     * and at least one mapped column. That the keys are fields the schema
     * actually declares is checked in {@link ImportMappingPresetService}, because
     * it depends on which schema the path named and an annotation cannot see it.
     */
    record SaveMappingPresetRequest(
            @NotBlank @Size(max = 80) String name,
            @NotEmpty Map<String, String> mapping) {
    }

    // ── B-034 · step 4 ──────────────────────────────────────────────────────

    /**
     * The dry-run request — which staged file, read how.
     *
     * <p>The mapping travels in the body rather than being read from a parked
     * server-side copy, which is the decision B-033 recorded and this route is
     * the first consumer of: the user reads the preview, goes back to step 3,
     * changes one column and runs it again. A stored mapping would be a fifth
     * piece of wizard state to keep in step with the other four.
     *
     * <p>Bean validation covers shape only. That every key is a field the schema
     * declares, that every required one is covered and that every column exists
     * in the sheet are all checked in {@link ImportValidationService}, because
     * each depends on something an annotation cannot see — the registration
     * named by the path, and the file staged under the id in the body.
     *
     * @param sheet a cross-check, not a selector. See the request schema in the
     *              contract: one upload stages one sheet
     */
    record ValidateRequest(
            @NotNull UUID uploadId,
            String sheet,
            @NotEmpty Map<String, String> mapping) {
    }

    /**
     * One row of blueprint §4B.3's step-4 table.
     *
     * <p>A projection of {@link ImportRowVerdict} rather than the record
     * serialised, for {@link SchemaField}'s reason one type over: the engine's
     * verdict is an internal shape several steps read — B-036's error report
     * takes the same object — and serialising it directly is how an internal
     * type becomes a public contract by accident. Today the two happen to agree
     * field for field, which is exactly when the seam is cheap to put in.
     */
    record RowVerdict(
            int rowNumber,
            ImportVerdict verdict,
            String reason,
            Map<String, String> values) {

        static RowVerdict of(ImportRowVerdict verdict) {
            return new RowVerdict(verdict.rowNumber(), verdict.verdict(),
                    verdict.reason(), verdict.values());
        }
    }

    /**
     * {@code ImportPreviewResponse.data} — the counts and every row.
     *
     * <p>Every row, uncapped, in the file's own order. The cap that matters is
     * B-032's 5,000 rows, applied at upload where it can refuse the file; a
     * second one here would mean a preview that quietly describes part of what
     * the commit is about to write, which is the one thing step 4 exists to
     * prevent. Paging is the screen's problem and it has the whole set to page.
     */
    record Preview(
            int willCreate,
            int willUpdate,
            int duplicates,
            int rejected,
            List<RowVerdict> rows) {

        static Preview of(ImportPreview preview) {
            return new Preview(
                    preview.willCreate(),
                    preview.willUpdate(),
                    preview.duplicates(),
                    preview.rejected(),
                    preview.rows().stream().map(RowVerdict::of).toList());
        }
    }

    record PreviewResponse(Preview data) {
    }

    // ── B-035 · step 5 ──────────────────────────────────────────────────────

    /**
     * The commit body — the same shape {@link ValidateRequest} has, plus one flag.
     *
     * <p><b>It carries no verdicts, and that is the security property of this
     * step.</b> The rows a commit writes are the rows the server's own dry run
     * judges writable, re-derived from this upload and this mapping. A body that
     * carried the preview would make them whatever the caller said they were.
     *
     * <p>The duplication with {@code ValidateRequest} is deliberate rather than
     * an oversight. They are two wire contracts that happen to agree today;
     * folding one into the other would mean {@code skipRejected} appearing on the
     * dry run — where it means nothing, because nothing is written — and the
     * generated TypeScript offering it there.
     *
     * @param skipRejected boxed, so "the caller did not say" and "the caller said
     *                     false" are different things. Absent takes the contract's
     *                     documented default of {@code true}; a primitive would
     *                     have silently made the all-or-nothing reading the
     *                     default for every client that omitted it
     */
    record CommitRequest(
            @NotNull UUID uploadId,
            String sheet,
            @NotEmpty Map<String, String> mapping,
            Boolean skipRejected) {

        /** §4B.3's "import valid rows only" is the default. See {@link RejectedRowsPresentException}. */
        boolean skipRejectedOrDefault() {
            return skipRejected == null || skipRejected;
        }
    }

    /**
     * {@code ImportBatchResponse.data} — one import run, as the progress bar and
     * the history panel read it.
     *
     * <p>A projection of {@link com.edunext.edutrack.domain.imports.ImportBatch}
     * rather than the entity serialised, for {@link SchemaField}'s reason two
     * types over: the entity carries {@code errorReportKey}, an object-storage
     * key that is an implementation detail of B-036 and not something a browser
     * should ever see.
     *
     * @param processed <b>derived, not stored.</b> There is no
     *                  {@code processed_rows} column and there should not be: it
     *                  is the sum of the three counters beside it, and a fourth
     *                  column holding the same number is a fourth column that can
     *                  disagree with them. It reaches {@code total} exactly when
     *                  the run is over
     * @param errorReportUrl <b>B-036 — a route, not the storage key and not a
     *                  signed URL.</b> Null exactly when there is nothing to
     *                  download: the run is not over, nothing was rejected, or
     *                  the report could not be stored. Null rather than a
     *                  constructed URL in those cases, because a link that 404s
     *                  is worse than a button that is honestly disabled.
     *                  <p>Relative to the API base, so the client's existing
     *                  credentials apply to it and {@code master.write} is
     *                  re-checked when the file is actually read. A presigned
     *                  object-store URL was the alternative and is what §4B.4
     *                  hands out for attachments; it is wrong here because this
     *                  file is a verbatim extract of the client master and a
     *                  signed URL is a bearer credential that outlives the screen
     *                  it was minted for
     */
    record Batch(
            Long batchId,
            String entity,
            String fileName,
            String status,
            int processed,
            int total,
            int created,
            int updated,
            int rejected,
            String errorReportUrl,
            Instant startedAt,
            Long importedBy,
            String importedByName,
            Instant reversedAt,
            int reversedRows,
            int retainedRows,
            boolean reversible) {

        /**
         * Where the error report is downloaded from, given a batch that has one.
         *
         * <p>Derived from the presence of {@code error_report_key} rather than
         * from its value: the key is an object-store path, it is an
         * implementation detail of {@link ImportReportStore}, and putting it on
         * the wire would make a bucket layout a public contract. A caller needs
         * to know that a report exists and how to ask for it, which is one bit
         * and one route.
         *
         * <p><b>Relative to the API base, so the {@code /api/v1} prefix is not in
         * it.</b> The file cannot be fetched as a plain link — it needs the
         * caller's {@code Authorization} header — so a client is composing this
         * onto its own base either way, exactly as {@code fetchImportTemplate}
         * composes the template path. A root-relative {@code /api/v1/…} would
         * work in the ordinary deployment and point at the wrong origin the
         * moment the API is served from another one.
         */
        private static final String ERROR_REPORT_PATH = "/import-batches/%d/error-report";

        static Batch of(com.edunext.edutrack.domain.imports.ImportBatch batch) {
            return of(batch, null);
        }

        /**
         * @param importedByName the display name of {@code imported_by}, or
         *                       {@code null} when it is not being resolved. The
         *                       polled progress read does not resolve it — the
         *                       screen showing that bar knows perfectly well who
         *                       started the run, because they just clicked the
         *                       button, and a user lookup every two seconds for
         *                       the length of an import is a query per poll for a
         *                       string that cannot change. B-037's history panel
         *                       does resolve it, in one query for the page
         */
        static Batch of(com.edunext.edutrack.domain.imports.ImportBatch batch, String importedByName) {
            int rejected = batch.getRejectedRows();
            String reportKey = batch.getErrorReportKey();
            return new Batch(
                    batch.getId(),
                    batch.getEntity(),
                    batch.getFileName(),
                    batch.getStatus().name(),
                    batch.getCreatedRows() + batch.getUpdatedRows() + rejected,
                    batch.getTotalRows(),
                    batch.getCreatedRows(),
                    batch.getUpdatedRows(),
                    rejected,
                    reportKey == null || reportKey.isBlank()
                            ? null
                            : ERROR_REPORT_PATH.formatted(batch.getId()),
                    batch.getCreatedAt(),
                    batch.getImportedBy(),
                    importedByName,
                    batch.getReversedAt(),
                    batch.getReversedRows(),
                    batch.getRetainedRows(),
                    reversible(batch));
        }

        /**
         * B-037 · whether {@code POST /import-batches/{batchId}/reverse} would be
         * accepted right now — <b>the button's enabled state, decided by the
         * server</b>.
         *
         * <p>The screen could compute this from {@code status} and
         * {@code reversedAt}, and that is exactly the reason it is here instead.
         * The two rules are {@link ImportReversalService}'s refusals, and a copy
         * in TypeScript is a second statement of them that agrees on the day it
         * is written — after which a rule added on one side turns into a button
         * that offers an operation the server refuses, on a screen whose whole
         * job is destroying rows.
         *
         * <p>Note it is deliberately <em>not</em> "and this run created
         * something". A COMPLETED run that created nothing reverses to "0
         * deleted", which is an honest and harmless answer; refusing it would
         * mean the flag also had to encode "created &gt; 0" and the screen would
         * have to explain a disabled button on a run that looks perfectly
         * ordinary.
         */
        private static boolean reversible(com.edunext.edutrack.domain.imports.ImportBatch batch) {
            return batch.getReversedAt() == null
                    && switch (batch.getStatus()) {
                        case COMPLETED, FAILED -> true;
                        case QUEUED, RUNNING -> false;
                    };
        }

        /**
         * The {@code ETag} the contract promises on the polled read.
         *
         * <p>Over the counters and the status — the only things that move — so
         * the validator changes exactly when the progress bar would. A client
         * polling every two seconds through a run that is between flushes gets a
         * 304 and transfers nothing, which is the entire reason the route
         * declares one.
         *
         * <p>Weak by the same argument {@code ClientController.etagOf} records:
         * a hash is not byte-equality of the representation, and nothing here
         * takes {@code If-Match}, so there is no lost update for a strong tag to
         * prevent.
         *
         * <p><b>B-037's four fields are inputs too</b>, and one of them matters
         * more than the rest: {@code reversible} is what the Reverse button reads,
         * so a validator that ignored it would leave a browser holding a cached
         * response offering to reverse a batch that has already been reversed. The
         * three fields it is derived from are hashed as well rather than relying on
         * the derivation, which is the same argument the paragraph below makes about
         * {@code errorReportUrl}. {@code startedAt} and the two {@code importedBy}
         * fields are not: they are stamped once at {@code open} and are immutable
         * for the life of the row, so hashing them would add cost and never change
         * an answer.
         *
         * <p><b>{@code errorReportUrl} is one of the inputs since B-036</b>, and
         * leaving it out would have been the defect this whole route exists to
         * avoid. The report appears on the same write as the terminal status, so
         * a tag over the counters alone happens to move with it today — and would
         * silently stop doing so the first time a run's last flush left the
         * counters where the final write finds them. A validator that does not
         * cover every field of the representation is a 304 that withholds a
         * change.
         */
        String etag() {
            return Integer.toHexString(java.util.Objects.hash(
                    batchId, status, created, updated, rejected, total, errorReportUrl,
                    reversedAt, reversedRows, retainedRows, reversible));
        }
    }

    record BatchResponse(Batch data) {
    }

    // ── B-037 · traceability and reversal ───────────────────────────────────

    /**
     * {@code ImportBatchListResponse.data} — the import history panel.
     *
     * <p><b>The same {@link Batch} the progress poll returns, in a list.</b> Not
     * a slimmer "summary" shape, which was the obvious alternative and is wrong
     * here: the panel shows the counters, the status and the error-report link,
     * which is every field the single read has, and the one operation it offers
     * — Reverse — is decided by {@code reversible}, which is on that record for
     * the reason its own javadoc gives. A second, nearly-identical wire type
     * would be a second thing to keep in step for no field saved.
     *
     * <p>Not paged. The route caps what it returns and says so; see
     * {@link ImportBatchService#history}. A cursor over a list nobody scrolls
     * would be {@code Cursor} plumbing for a panel whose useful contents are the
     * last few runs.
     */
    record BatchList(String entity, List<Batch> batches, int limit) {
    }

    record BatchListResponse(BatchList data) {
    }

    /**
     * {@code ImportReversalResponse.data} — what a reversal did.
     *
     * <p>The batch is included whole rather than as an id, so the screen that
     * ran the reversal renders the updated row from the response instead of
     * re-reading the list. It is the same object with {@code reversedAt} now set
     * and {@code reversible} now false, which is what disables the button
     * without the client having to reason about it.
     *
     * @param deleted  natural keys removed, in the order the registration found
     *                 them. Named rather than counted because the count is on
     *                 {@code batch.reversedRows} already, and what the operator
     *                 actually asks next is "which ones"
     * @param retained rows the reversal refused to remove, each with a reason a
     *                 person can read. <b>An empty list is the ordinary
     *                 outcome</b>, not a success condition — a non-empty one is
     *                 not a failure either. See {@link ImportReversal}
     * @param updatedRowsNotReverted <b>the honest half of the promise.</b> Rows
     *                 this run <em>updated</em> rather than created, which a
     *                 reversal does not touch and could not restore: the batch id
     *                 is stamped on insert only and there is no before image
     *                 anywhere. Carried explicitly, and never folded into
     *                 {@code retained}, because it is not a row the reversal
     *                 declined to delete — it is a row the reversal was never
     *                 about, and a user who imported 412 and sees "12 deleted"
     *                 is owed the sentence that explains the other 400
     */
    record Reversal(
            Batch batch,
            List<String> deleted,
            List<ImportReversal.Retained> retained,
            int updatedRowsNotReverted) {
    }

    record ReversalResponse(Reversal data) {
    }
}
