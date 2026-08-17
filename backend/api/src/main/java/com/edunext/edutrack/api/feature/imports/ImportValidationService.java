package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * B-034 · blueprint §4B.3 step 4 — take a staged file and a mapping, hand back
 * a per-row verdict. <b>Nothing is written.</b>
 *
 * <p>This is the thin half of the step. {@link ImportValidationEngine} decides
 * the verdicts and has since B-030; what was missing was a way to reach it —
 * resolving the schema, finding the file the wizard uploaded three requests ago,
 * and refusing the mappings that would produce a preview nobody could act on.
 *
 * <h2>It holds no repository, and that is structural</h2>
 *
 * <p>Like {@link ImportUploadService} beside it, and for the same reason:
 * {@code ImportEngineIsolationTest} fails the build if a persistence import
 * appears in this package outside {@code .schemas}. The only path to the
 * database from here is {@link ImportSchemaDefinition#findExisting}, which is
 * read-only by contract. §4B.3's promise for this step is absolute — "nothing is
 * written yet" — and a promise that depends on nobody adding a convenient
 * {@code save()} later is not one.
 *
 * <h2>The order of the checks is the design</h2>
 *
 * <p>Cheapest and most structural first, so a caller is told the most useful
 * thing rather than the first thing that happened to fail:
 *
 * <ol>
 *   <li><b>Schema</b> — a 404 before the body matters at all, the same order the
 *       upload service uses.
 *   <li><b>The staged upload</b> — because every later check needs the sheet's
 *       headings, and because a caller whose upload expired should not be told
 *       about their mapping.
 *   <li><b>The mapping's target side</b> — fields we declare, and every required
 *       one covered. See {@link IncompleteMappingException} for why running
 *       anyway produces a preview that points at the wrong thing.
 *   <li><b>The mapping's source side</b> — columns their sheet actually has.
 *   <li><b>The rows.</b>
 * </ol>
 *
 * <p>Each of the four refusals is a 422 with a {@code type} of its own, because
 * each has a different remedy and the screen says a different sentence for each.
 */
@Service
class ImportValidationService {

    private final ImportSchemaRegistry registry;
    private final ImportStagingStore staging;
    private final ImportValidationEngine engine;

    ImportValidationService(ImportSchemaRegistry registry,
                            ImportStagingStore staging,
                            ImportValidationEngine engine) {
        this.registry = registry;
        this.staging = staging;
        this.engine = engine;
    }

    /**
     * @param schemaKey the URL segment — resolved first, so an unregistered
     *                  schema is a 404 before the body is looked at
     * @return the preview, and no side effects of any kind
     */
    ImportPreview validate(String schemaKey, ImportDtos.ValidateRequest request) {
        ImportSchemaDefinition definition = registry.resolve(schemaKey);
        StagedUpload upload = stagedUpload(request.uploadId(), request.sheet());
        ImportMapping mapping = mappingFor(definition, upload, request.mapping());

        // Mapped per request rather than at upload, because the mapping is the
        // thing the user is changing: they run the dry run, read it, go back to
        // step 3 and run it again. StagedUpload holds the rows keyed by their
        // own headings for exactly that reason.
        List<ImportRow> rows = new ArrayList<>(upload.rows().size());
        for (StagedRow staged : upload.rows()) {
            rows.add(mapping.apply(staged.number(), staged.cells()));
        }

        return engine.validate(definition, rows);
    }

    /**
     * The upload the request names, or a refusal.
     *
     * <p>{@code sheet} is a cross-check and not a selector — one {@code uploadId}
     * stages exactly one sheet, chosen at step 2, and changing it re-posts the
     * file. Sent and disagreeing, it means the client's state and the server's
     * have diverged; see {@link ImportUploadNotAvailableException} for why that
     * is the same condition as an expired id rather than a second one.
     */
    private StagedUpload stagedUpload(UUID uploadId, String requestedSheet) {
        StagedUpload upload = staging.find(uploadId)
                .orElseThrow(() -> ImportUploadNotAvailableException.expired(uploadId));

        if (requestedSheet != null && !requestedSheet.isBlank()
                && !requestedSheet.equals(upload.sheet())) {
            throw ImportUploadNotAvailableException.wrongSheet(
                    uploadId, requestedSheet, upload.sheet());
        }
        return upload;
    }

    /**
     * The submitted mapping, checked against both sides and turned into an
     * {@link ImportMapping}.
     *
     * <p>Both sides, because a mapping has two halves that fail differently: a
     * target field we do not declare is a caller sending something we have never
     * heard of, and a source column their sheet lacks is a mapping built against
     * a different file. Only the second is checkable here and only the second is
     * common — a preset survives a renamed export and a
     * {@link UnknownSourceColumnException} is what says so.
     */
    private ImportMapping mappingFor(ImportSchemaDefinition definition,
                                     StagedUpload upload,
                                     Map<String, String> submitted) {
        Map<String, String> cleaned = cleaned(submitted);

        Set<String> declared = new LinkedHashSet<>();
        definition.fields().forEach(field -> declared.add(field.name()));

        Set<String> unknownFields = new LinkedHashSet<>(cleaned.keySet());
        unknownFields.removeAll(declared);
        if (!unknownFields.isEmpty()) {
            // The same refusal preset saving makes, deliberately reused: to a
            // caller "this import declares no such field" is one condition
            // whichever route it arrives on, and a second type for it would be
            // a second thing for the screen to handle identically.
            throw new UnknownImportFieldException(
                    definition.key(), unknownFields, List.copyOf(declared));
        }

        ImportMapping mapping = new ImportMapping(cleaned);

        Set<String> missing = mapping.missingRequired(definition.fields());
        if (!missing.isEmpty()) {
            throw new IncompleteMappingException(definition.key(),
                    definition.fields().stream().filter(f -> missing.contains(f.name())).toList());
        }

        Set<String> headings = new LinkedHashSet<>(upload.headers());
        List<String> unknownColumns = cleaned.values().stream()
                .filter(column -> !headings.contains(column))
                .distinct()
                .toList();
        if (!unknownColumns.isEmpty()) {
            throw new UnknownSourceColumnException(
                    upload.sheet(), unknownColumns, upload.headers());
        }

        return mapping;
    }

    /**
     * Drops entries with a blank column, and trims the rest — the same cleaning
     * {@link ImportMappingPresetService} does on a save, for the same reason.
     *
     * <p>A blank value is how "not mapped" arrives from a {@code <select>} whose
     * empty option was left selected. Left in, it is a key that counts as mapped
     * for the required-fields check and reads nothing at all, which is precisely
     * the state that check exists to prevent.
     *
     * <p>Ordered, so a message listing the mapping reads the way the user built
     * it rather than in hash order.
     */
    private static Map<String, String> cleaned(Map<String, String> submitted) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (submitted == null) {
            return mapping;
        }
        submitted.forEach((field, column) -> {
            if (field != null && !field.isBlank() && column != null && !column.isBlank()) {
                mapping.put(field.trim(), column.trim());
            }
        });
        return mapping;
    }
}
