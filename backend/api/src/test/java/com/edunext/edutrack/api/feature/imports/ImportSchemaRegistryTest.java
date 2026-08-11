package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-030 · the registry, and the boot-time refusals that keep a registration
 * honest.
 *
 * <p>Most of this file is about failing to start. That is the point: every
 * malformed registration below would otherwise surface as a wrong verdict on a
 * real import — weeks later, to a different person, on somebody's 5,000-row
 * file. A failed context refresh is the cheapest possible place to find them.
 */
class ImportSchemaRegistryTest {

    @Test
    void resolvesARegisteredSchemaByItsUrlKey() {
        TestImportSchema widgets = new TestImportSchema();
        ImportSchemaRegistry registry = new ImportSchemaRegistry(List.of(widgets));

        assertThat(registry.resolve("widgets")).isSameAs(widgets);
        assertThat(registry.keys()).containsExactly("widgets");
    }

    @Test
    @DisplayName("an unregistered key throws the exception that becomes a 404")
    void rejectsAnUnknownKey() {
        ImportSchemaRegistry registry = new ImportSchemaRegistry(List.of(new TestImportSchema()));

        assertThatThrownBy(() -> registry.resolve("invoices"))
                .isInstanceOf(UnknownImportSchemaException.class)
                // The registered keys are in the message: the first thing
                // anyone asks on seeing this is "well, what IS registered?"
                .hasMessageContaining("widgets");
    }

    @Test
    void rejectsANullKeyRatherThanReturningNull() {
        ImportSchemaRegistry registry = new ImportSchemaRegistry(List.of(new TestImportSchema()));

        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(UnknownImportSchemaException.class);
    }

    @Test
    void startsCleanlyWithNoRegistrationsAtAll() {
        // Not a real deployment, but it must not be a startup failure — a
        // module that has not registered anything yet is a valid state.
        assertThatCode(() -> new ImportSchemaRegistry(List.of())).doesNotThrowAnyException();
    }

    // ── boot-time refusals ──────────────────────────────────────────────────

    @Test
    @DisplayName("two registrations under one key refuse to start, naming both classes")
    void refusesADuplicateKey() {
        assertThatThrownBy(() ->
                new ImportSchemaRegistry(List.of(new TestImportSchema(), new TestImportSchema())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("widgets")
                .hasMessageContaining(TestImportSchema.class.getName());
    }

    @Test
    @DisplayName("a natural key that may be blank refuses to start")
    void refusesAnOptionalNaturalKey() {
        // See ImportSchemaDefinition#naturalKey — with a blank key,
        // "does this already exist?" has no answer for exactly the rows that
        // most need one, and they would all silently create.
        ImportField optionalKey = ImportField.optional("code", "Code");

        assertThatThrownBy(() -> new ImportSchemaRegistry(List.of(
                schemaWith(List.of(optionalKey), optionalKey))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be required()");
    }

    @Test
    @DisplayName("a natural key that is not one of the fields refuses to start")
    void refusesAnUnlistedNaturalKey() {
        assertThatThrownBy(() -> new ImportSchemaRegistry(List.of(
                schemaWith(List.of(ImportField.required("name", "Name")),
                        ImportField.required("code", "Code")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("naturalKey");
    }

    @Test
    @DisplayName("two fields with one name refuse to start — the mapping could not address them")
    void refusesADuplicateFieldName() {
        ImportField code = ImportField.required("code", "Code");

        assertThatThrownBy(() -> new ImportSchemaRegistry(List.of(
                schemaWith(List.of(code, ImportField.optional("code", "Client Code")), code))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void refusesASchemaWithNoFields() {
        assertThatThrownBy(() -> new ImportSchemaRegistry(List.of(
                schemaWith(List.of(), ImportField.required("code", "Code")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no fields");
    }

    /** A minimal registration whose field list and natural key the test chooses. */
    private static ImportSchemaDefinition schemaWith(List<ImportField> fields, ImportField naturalKey) {
        return new ImportSchemaDefinition() {
            @Override
            public String key() {
                return "malformed";
            }

            @Override
            public String entityCode() {
                return "MALFORMED";
            }

            @Override
            public List<ImportField> fields() {
                return fields;
            }

            @Override
            public ImportField naturalKey() {
                return naturalKey;
            }

            @Override
            public Set<String> findExisting(Set<String> naturalKeyValues) {
                return Set.of();
            }

            @Override
            public void upsert(ImportRow row, Long importBatchId) {
            }
        };
    }
}
