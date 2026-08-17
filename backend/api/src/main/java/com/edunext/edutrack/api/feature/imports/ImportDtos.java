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
}
