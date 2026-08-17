package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-033 · blueprint §4B.3 step 3 — the presets half.
 *
 * <p>The mapping itself needs no service and has no endpoint: step 3 is a screen
 * over data step 2 already returned, and the mapping the user settles on travels
 * with step 4's {@code /validate} request. That is what the contract has encoded
 * since B-030, and adding a "submit the mapping" route would give the wizard a
 * fifth piece of server state to keep in step with the other four.
 *
 * <p>What does need the server is presets, because §4B.3 wants them reusable for
 * the <em>next</em> import — see the migration for why that ruled out the
 * browser.
 *
 * <h2>The schema is resolved first, every time</h2>
 *
 * <p>Every method starts with {@link ImportSchemaRegistry#resolve}, so an
 * unregistered path segment is a 404 before a query runs — the same order the
 * upload service uses, and the reason {@code /imports/users/mapping-presets}
 * answers 404 today rather than an empty list. An empty list would be a lie of a
 * particular kind: it says "this schema has no presets yet", which invites the
 * caller to save one.
 *
 * <h2>No {@code @Transactional}, deliberately</h2>
 *
 * <p>These three methods were written with it and it was removed once what it
 * bought was examined. {@code list} and {@code delete} are one statement each and
 * atomic on their own. {@code save} is two — the upsert, then a read of the row it
 * settled on — and the race a transaction looks like it would close, another Admin
 * re-saving that name in between, is <em>not</em> closed by one: under
 * {@code READ COMMITTED} the other session's commit is visible to our second
 * statement either way. Both statements address the row for {@code (schema_key,
 * name)}, so the id is the same regardless and the worst outcome is a response
 * echoing a colleague's mapping — which the transaction would not have prevented.
 *
 * <p>What it did do was open a JPA {@code EntityManager} on every call, which put
 * a live database between the route and any test of it. Annotation removed, four
 * route tests gained, nothing lost.
 */
@Service
class ImportMappingPresetService {

    private final ImportSchemaRegistry registry;
    private final ImportMappingPresetRepository presets;

    ImportMappingPresetService(ImportSchemaRegistry registry,
                               ImportMappingPresetRepository presets) {
        this.registry = registry;
        this.presets = presets;
    }

    /** What step 3's picker is built from. */
    List<ImportDtos.MappingPreset> list(String schemaKey) {
        ImportSchemaDefinition definition = registry.resolve(schemaKey);
        return presets.findAll(definition.key());
    }

    /**
     * Save, or replace the preset already saved under this name.
     *
     * <p><b>Every key is checked against the registration.</b> A mapping naming a
     * field the schema does not declare is refused rather than trimmed — see
     * {@link UnknownImportFieldException} for why silence is the expensive
     * option here.
     *
     * <p><b>The source column is not checked, and cannot be.</b> A preset is
     * saved to be applied to a <em>different file</em>, so "does this column
     * exist" has no answer at save time — the file it was saved from is the one
     * file the answer is irrelevant for. That question is asked when the preset
     * is applied, on the client, against the headings of the file actually in
     * hand, and the screen reports what it could not place.
     *
     * <p>The name is trimmed. Two presets differing only in a trailing space are
     * two rows the unique key permits and a dropdown cannot distinguish.
     *
     * @param userId who saved it, or {@code null} when the caller is not
     *               identifiable. Attribution is best-effort by design: it is
     *               recorded because it is useful, it is on no key, nothing
     *               filters on it, and a preset with no name against it is still
     *               a working preset. Failing the save over it would refuse a
     *               legitimate action to protect a column nothing reads
     */
    ImportDtos.MappingPreset save(String schemaKey, ImportDtos.SaveMappingPresetRequest request,
                                  Long userId) {
        ImportSchemaDefinition definition = registry.resolve(schemaKey);

        Map<String, String> mapping = cleaned(request.mapping());
        if (mapping.isEmpty()) {
            // `@NotEmpty` on the request cannot see this: a map of two fields
            // both pointing at "" is non-empty and maps nothing. Stored, it
            // becomes a preset that appears in the picker, applies without
            // complaint and leaves every column unmapped.
            throw new EmptyMappingException();
        }

        Set<String> declared = new LinkedHashSet<>();
        definition.fields().forEach(field -> declared.add(field.name()));

        Set<String> unknown = new LinkedHashSet<>(mapping.keySet());
        unknown.removeAll(declared);
        if (!unknown.isEmpty()) {
            throw new UnknownImportFieldException(definition.key(), unknown, List.copyOf(declared));
        }

        return presets.save(definition.key(), request.name().trim(), mapping, userId);
    }

    void delete(String schemaKey, long presetId) {
        ImportSchemaDefinition definition = registry.resolve(schemaKey);
        if (!presets.delete(definition.key(), presetId)) {
            throw new MappingPresetNotFoundException(definition.key(), presetId);
        }
    }

    /**
     * Drops entries with a blank source column, and trims the rest.
     *
     * <p>A blank value is how "not mapped" arrives from a {@code <select>} whose
     * empty option was left selected, and storing it would make the preset
     * <em>claim</em> a mapping for that field. {@link ImportMapping#apply}
     * already ignores a blank cell, so an empty string in a preset is
     * indistinguishable from an absent key in effect — but only after it has been
     * counted as mapped by the screen that decides whether Next is blocked.
     *
     * <p>Ordered, so the stored document reads in the order the user built it
     * rather than in hash order. Nothing depends on it; a diff of two versions of
     * the same preset does.
     */
    private static Map<String, String> cleaned(Map<String, String> submitted) {
        Map<String, String> mapping = new LinkedHashMap<>();
        submitted.forEach((field, column) -> {
            if (field != null && !field.isBlank() && column != null && !column.isBlank()) {
                mapping.put(field.trim(), column.trim());
            }
        });
        return mapping;
    }

    /**
     * A mapping that names no column at all — a 400.
     *
     * <p>Nested on the service, the way {@code ClientService.UnknownClientException}
     * is: one condition on one route, and a file of its own would say less than
     * this paragraph does.
     *
     * <p>400 and not 422, unlike {@link UnknownImportFieldException}: nothing here
     * refers to something that does not exist, the body simply does not carry the
     * one thing it is for. That is the same failure {@code @NotEmpty} reports, so
     * it answers with the same status and {@code type} — a caller should not have
     * to handle "you sent no mapping" twice.
     */
    static class EmptyMappingException extends RuntimeException {

        EmptyMappingException() {
            super("A preset must map at least one column. "
                    + "Choose a source column for at least one field before saving.");
        }
    }
}
