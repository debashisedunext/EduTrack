package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-033 · {@code import_mapping_presets} against a real MySQL.
 *
 * <p>Everything asserted here is a property of the schema rather than of the Java
 * — which is exactly why the unit tests cannot reach it:
 *
 * <ul>
 *   <li>the upsert on {@code (schema_key, name)} depends on a unique index
 *       existing, and a mock repository will happily "replace" a row whether or
 *       not the index is there;
 *   <li>whether {@code CRM export} and {@code CRM Export} are one preset is the
 *       table's collation deciding, not ours;
 *   <li>whether the JSON survives a round trip depends on the {@code CHECK} and
 *       the column type;
 *   <li>and whether the delete is really scoped by schema is one clause in one
 *       statement, which is the kind of thing that is trivially correct in the
 *       source and untested until a row exists to prove it.
 * </ul>
 *
 * <p>Preset names are prefixed {@code ITMAP} so nothing here collides with
 * another suite's rows.
 */
@SpringBootTest
@Testcontainers
class ImportMappingPresetIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_preset_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    ImportMappingPresetRepository presets;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM import_mapping_presets WHERE name LIKE 'ITMAP%'");
    }

    @Test
    @DisplayName("a preset round-trips, mapping and all")
    void roundTripsAPreset() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("clientCode", "Account Ref");
        mapping.put("name", "Account Name");
        mapping.put("primaryEmail", "Billing Email");

        ImportDtos.MappingPreset saved =
                presets.save("clients", "ITMAP crm", mapping, null);

        assertThat(saved.presetId()).isPositive();
        assertThat(saved.name()).isEqualTo("ITMAP crm");
        assertThat(saved.mapping()).containsExactlyInAnyOrderEntriesOf(mapping);
        assertThat(saved.updatedAt())
                .as("the column defaults on insert; a null here means the read dropped it")
                .isNotNull();
    }

    /**
     * §4B.3's presets are meant to be corrected, and this is what makes that safe.
     *
     * <p>Without the unique key, an Admin who fixes one column and presses Save
     * ends with two presets called <i>ITMAP crm</i> in a dropdown that cannot tell
     * them apart — and no way to know which one the next import will use.
     */
    @Test
    @DisplayName("saving under an existing name replaces it — one row, not two")
    void savingTwiceReplacesRatherThanDuplicates() {
        ImportDtos.MappingPreset first =
                presets.save("clients", "ITMAP crm", Map.of("clientCode", "Ref"), null);
        ImportDtos.MappingPreset second = presets.save("clients", "ITMAP crm",
                Map.of("clientCode", "Ref", "name", "Title"), null);

        assertThat(second.presetId())
                .as("the same row, so the picker's selected id stays valid across a re-save")
                .isEqualTo(first.presetId());
        assertThat(second.mapping()).containsKeys("clientCode", "name");
        assertThat(presets.findAll("clients").stream()
                .filter(preset -> preset.name().startsWith("ITMAP"))
                .toList())
                .hasSize(1);
    }

    /**
     * The table's collation deciding, not ours — {@code utf8mb4_0900_ai_ci}.
     *
     * <p>Recorded as a test rather than left implicit because the alternative is
     * defensible and this is the choice: two presets a user cannot distinguish in a
     * dropdown are worse than a replaced one.
     */
    @Test
    @DisplayName("names are case-insensitive, so two presets are never indistinguishable")
    void namesAreCaseInsensitive() {
        presets.save("clients", "ITMAP crm", Map.of("clientCode", "Ref"), null);
        presets.save("clients", "ITMAP CRM", Map.of("clientCode", "Code"), null);

        assertThat(presets.findAll("clients").stream()
                .filter(preset -> preset.name().toUpperCase(java.util.Locale.ROOT)
                        .startsWith("ITMAP"))
                .toList())
                .hasSize(1);
    }

    /**
     * Two schemas may use one name, because they are different mappings onto
     * different columns.
     *
     * <p>Written before B-038 existed, when no route could reach a {@code users}
     * preset at all — the key is composite, and a test that only ever used one
     * schema would pass just as well if it were not. The resource registration
     * has since made both halves reachable, and this assertion did not change.
     */
    @Test
    void theSameNameUnderTwoSchemasIsTwoPresets() {
        presets.save("clients", "ITMAP shared", Map.of("clientCode", "Ref"), null);
        presets.save("users", "ITMAP shared", Map.of("email", "Email"), null);

        assertThat(presets.findByName("clients", "ITMAP shared")).isPresent();
        assertThat(presets.findByName("users", "ITMAP shared")).isPresent();
        assertThat(presets.findByName("clients", "ITMAP shared").orElseThrow().presetId())
                .isNotEqualTo(presets.findByName("users", "ITMAP shared").orElseThrow().presetId());
    }

    /**
     * The nesting in the URL is real: a preset id from another schema is absent
     * from this schema's path rather than deletable through it.
     */
    @Test
    @DisplayName("the delete is scoped by schema, not only by id")
    void deleteIsScopedBySchema() {
        long id = presets.save("users", "ITMAP elsewhere", Map.of("email", "Email"), null)
                .presetId();

        assertThat(presets.delete("clients", id))
                .as("the id exists, but not under this schema")
                .isFalse();
        assertThat(presets.findByName("users", "ITMAP elsewhere")).isPresent();

        assertThat(presets.delete("users", id)).isTrue();
        assertThat(presets.findByName("users", "ITMAP elsewhere")).isEmpty();
    }

    /** Ordered by name, so the picker reads the same on every visit. */
    @Test
    void listsPresetsByName() {
        presets.save("clients", "ITMAP zeta", Map.of("clientCode", "Ref"), null);
        presets.save("clients", "ITMAP alpha", Map.of("clientCode", "Ref"), null);

        assertThat(presets.findAll("clients").stream()
                .map(ImportDtos.MappingPreset::name)
                .filter(name -> name.startsWith("ITMAP"))
                .toList())
                .containsExactly("ITMAP alpha", "ITMAP zeta");
    }
}
