package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** B-030 · turning the user's columns into our fields. */
class ImportMappingTest {

    private final ImportMapping mapping = new ImportMapping(Map.of(
            "code", "Client Code",
            "name", "Company",
            "email", "Contact"));

    @Test
    void rekeysARowFromColumnHeadingsToFieldNames() {
        ImportRow row = mapping.apply(2, Map.of(
                "Client Code", "ACME",
                "Company", "Acme Corporation",
                "Contact", "someone@acme.example"));

        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.values()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "code", "ACME",
                "name", "Acme Corporation",
                "email", "someone@acme.example"));
    }

    @Test
    @DisplayName("columns the mapping ignores do not reach the engine")
    void dropsUnmappedColumns() {
        ImportRow row = mapping.apply(2, Map.of(
                "Client Code", "ACME",
                "Company", "Acme",
                "Contact", "a@b.example",
                "Internal Ref", "ignore me"));

        assertThat(row.values()).doesNotContainKey("Internal Ref");
        assertThat(row.values()).hasSize(3);
    }

    @Test
    @DisplayName("blank and whitespace-only cells are absent, not empty — one representation of missing")
    void dropsBlankCells() {
        Map<String, String> raw = new HashMap<>();
        raw.put("Client Code", "ACME");
        raw.put("Company", "   ");
        raw.put("Contact", "");

        ImportRow row = mapping.apply(2, raw);

        assertThat(row.get("code")).isEqualTo("ACME");
        assertThat(row.get("name")).isNull();
        assertThat(row.get("email")).isNull();
    }

    @Test
    void trimsSurroundingWhitespace() {
        ImportRow row = mapping.apply(2, Map.of("Client Code", "  ACME  ", "Company", "Acme"));

        assertThat(row.get("code")).isEqualTo("ACME");
    }

    @Test
    @DisplayName("a mapped column the sheet does not have is simply absent")
    void toleratesAMissingSourceColumn() {
        ImportRow row = mapping.apply(2, Map.of("Client Code", "ACME"));

        assertThat(row.values()).containsOnlyKeys("code");
    }
}
