package com.edunext.edutrack.api.feature.imports;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A registration that exists only for these tests.
 *
 * <p>The engine tests use this rather than {@code ClientImportSchema} on
 * purpose. Asserting the verdict matrix against the real client schema would
 * make every engine test depend on the client field list, so adding a column to
 * the client master would break tests about duplicate detection — and the
 * breakage would be read as noise and silenced. It would also quietly stop
 * proving the thing B-030 is for: that the engine works for a schema it has
 * never heard of.
 */
final class TestImportSchema implements ImportSchemaDefinition {

    static final ImportField CODE = ImportField.required("code", "Code")
            .maxLength(10)
            .validate(FieldValidators.alphanumeric());

    static final List<ImportField> FIELDS = List.of(
            CODE,
            ImportField.required("name", "Name").maxLength(50),
            ImportField.optional("email", "Email")
                    .type(ImportFieldType.EMAIL)
                    .validate(FieldValidators.email()),
            ImportField.optional("status", "Status").oneOf("ACTIVE", "INACTIVE"),
            ImportField.optional("notes", "Notes").maxLength(10));

    private final Set<String> existing;
    /** Every set this schema was asked about, so a test can prove the probe was batched. */
    final List<Set<String>> probes = new ArrayList<>();

    TestImportSchema(String... existingKeys) {
        this.existing = new HashSet<>(List.of(existingKeys));
    }

    @Override
    public String key() {
        return "widgets";
    }

    @Override
    public String entityCode() {
        return "WIDGET";
    }

    @Override
    public List<ImportField> fields() {
        return FIELDS;
    }

    @Override
    public ImportField naturalKey() {
        return CODE;
    }

    @Override
    public Set<String> findExisting(Set<String> naturalKeyValues) {
        probes.add(new LinkedHashSet<>(naturalKeyValues));
        Set<String> found = new LinkedHashSet<>(naturalKeyValues);
        found.retainAll(existing);
        return found;
    }

    @Override
    public void upsert(ImportRow row, Long importBatchId) {
        throw new AssertionError(
                "The dry run called upsert(). Step 4 writes nothing — see ImportValidationEngine.");
    }
}
