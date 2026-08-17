package com.edunext.edutrack.api.feature.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-033 · {@code import_mapping_presets}, the one table this feature owns.
 *
 * <p>Blueprint §4B.3 step 3: "Mapping presets can be saved and reused for the
 * next import." A preset records how one export lines up with our columns, so
 * next month's import is a picker rather than twenty dropdowns.
 *
 * <h2>This is not a hole in the engine's isolation rule</h2>
 *
 * <p>{@code ImportEngineIsolationTest} bans persistence from
 * {@link ImportValidationEngine} — the dry run — and business entities from the
 * whole package. Neither is touched here: a preset is <em>the engine's own</em>
 * record, the same way {@code import_batches} is, and this class names no
 * client, user, project or ticket. Nothing on the validate path can reach it,
 * because nothing on the validate path holds one.
 *
 * <h2>Save is an upsert, and the unique key is what makes it one</h2>
 *
 * <p>{@code (schema_key, name)} is unique, so {@code ON DUPLICATE KEY UPDATE}
 * replaces rather than accumulates. That is what "Save" means to somebody who
 * has just corrected one column: without it, pressing it twice leaves two
 * presets called <i>CRM export</i> in a picker that cannot tell them apart.
 *
 * <p><b>The mapping is stored as JSON</b> — read whole, written whole, never
 * queried across. Encoded through the injected {@link ObjectMapper}, like
 * {@code ProjectSettingsRepository} and {@code ResourceWriteRepository} before
 * it, rather than by hand.
 */
@Repository
class ImportMappingPresetRepository {

    private static final TypeReference<Map<String, String>> MAPPING = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ImportMappingPresetRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Every preset for a schema, by name.
     *
     * <p>Ordered so the picker reads the same on every visit. Insertion order
     * would put the newest at the bottom of a list somebody scans by name, and
     * {@code updated_at} order would reshuffle it every time one is saved.
     */
    List<ImportDtos.MappingPreset> findAll(String schemaKey) {
        return jdbc.sql("""
                        SELECT id, name, mapping, updated_at
                        FROM import_mapping_presets
                        WHERE schema_key = ?
                        ORDER BY name
                        """)
                .param(schemaKey)
                .query(this::toPreset)
                .list();
    }

    /**
     * Insert, or replace the mapping of the preset already saved under this name.
     *
     * <p>Two statements rather than one {@code INSERT … ON DUPLICATE KEY UPDATE
     * … RETURNING}, which MySQL does not have: the write, then a read of the row
     * it settled on. The read is by {@code (schema_key, name)} and not by
     * {@code LAST_INSERT_ID()} — that function reports 0 for an update that
     * changed nothing, so saving a preset whose mapping was already correct
     * would answer with a preset id of zero.
     *
     * <p>{@code created_by} is written on insert only. An update must not
     * re-attribute a mapping the team has shared for months to whoever last
     * pressed Save — the same reasoning {@code ClientImportSchema} applies to
     * {@code importBatchId}.
     */
    ImportDtos.MappingPreset save(String schemaKey, String name,
                                  Map<String, String> mapping, Long userId) {
        jdbc.sql("""
                        INSERT INTO import_mapping_presets (schema_key, name, mapping, created_by)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE mapping = VALUES(mapping)
                        """)
                .param(schemaKey)
                .param(name)
                .param(encode(mapping))
                .param(userId)
                .update();

        return findByName(schemaKey, name).orElseThrow(() -> new IllegalStateException(
                // Unreachable: the INSERT above either created this row or
                // matched it. Loud rather than an Optional the caller has to
                // invent a meaning for — "saved, but not there" is a bug in
                // this method, not a state the API should describe.
                "preset '" + name + "' was saved under '" + schemaKey + "' and could not be read back"));
    }

    Optional<ImportDtos.MappingPreset> findByName(String schemaKey, String name) {
        return jdbc.sql("""
                        SELECT id, name, mapping, updated_at
                        FROM import_mapping_presets
                        WHERE schema_key = ? AND name = ?
                        """)
                .param(schemaKey)
                .param(name)
                .query(this::toPreset)
                .optional();
    }

    /**
     * @return whether a row went. {@code false} is the caller's 404.
     *
     * <p><b>Scoped by {@code schema_key} as well as by id</b>, so a preset id
     * belonging to the resource schema is absent from the client schema's path
     * rather than deletable through it. The nesting in the URL is real.
     */
    boolean delete(String schemaKey, long presetId) {
        return jdbc.sql("""
                        DELETE FROM import_mapping_presets
                        WHERE id = ? AND schema_key = ?
                        """)
                .param(presetId)
                .param(schemaKey)
                .update() > 0;
    }

    private ImportDtos.MappingPreset toPreset(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ImportDtos.MappingPreset(
                rs.getLong("id"),
                rs.getString("name"),
                decode(rs.getString("mapping")),
                updated == null ? null : updated.toInstant());
    }

    private String encode(Map<String, String> mapping) {
        try {
            return json.writeValueAsString(mapping);
        } catch (Exception e) {
            // Unreachable: the service has already established this is a map of
            // string to string whose keys are declared field names. Loud rather
            // than swallowed — a preset stored as NULL would fail the CHECK
            // anyway, and a preset stored as `{}` would silently map nothing.
            throw new IllegalStateException("mapping could not be encoded", e);
        }
    }

    /**
     * An empty map for a document that will not parse.
     *
     * <p>Same position as {@code ProjectSettingsRepository#decode}: a malformed
     * row must not make the screen that could delete it unopenable.
     * {@code ck_import_mapping_presets_mapping} makes it unreachable through the
     * database; a dump restored from before that constraint is the case it
     * covers. An empty mapping presents as a preset that maps nothing, which is
     * visibly wrong on screen and one click from being replaced.
     */
    private Map<String, String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> mapping = json.readValue(raw, MAPPING);
            return mapping == null ? Map.of() : new LinkedHashMap<>(mapping);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
