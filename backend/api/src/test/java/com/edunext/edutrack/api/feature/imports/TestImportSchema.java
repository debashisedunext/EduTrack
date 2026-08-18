package com.edunext.edutrack.api.feature.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private final Map<String, Map<String, String>> existing = new LinkedHashMap<>();
    /** Every set this schema was asked about, so a test can prove the probe was batched. */
    final List<Set<String>> probes = new ArrayList<>();

    /**
     * Keys that exist with no current values — the registration that cannot
     * cheaply supply them, which {@link ImportSchemaDefinition#findExisting}
     * permits and whose rows come back with a null reason.
     */
    TestImportSchema(String... existingKeys) {
        for (String key : existingKeys) {
            existing.put(key, Map.of());
        }
    }

    /**
     * One key that exists <em>and</em> says what it holds, so B-034's
     * changed-field message has something to compare against.
     *
     * <p>Chained rather than taken in the constructor: most tests here are about
     * the verdict matrix and do not care what is stored, and making all of them
     * pass an empty map to say so would bury the two that do.
     */
    TestImportSchema holding(String key, Map<String, String> currentValues) {
        existing.put(key, Map.copyOf(currentValues));
        return this;
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
    public Map<String, Map<String, String>> findExisting(Set<String> naturalKeyValues) {
        probes.add(new LinkedHashSet<>(naturalKeyValues));
        Map<String, Map<String, String>> found = new LinkedHashMap<>();
        for (String key : naturalKeyValues) {
            Map<String, String> stored = existing.get(key);
            if (stored != null) {
                found.put(key, stored);
            }
        }
        return found;
    }

    @Override
    public void upsert(ImportRow row, Long importBatchId) {
        throw new AssertionError(
                "The dry run called upsert(). Step 4 writes nothing — see ImportValidationEngine.");
    }
}
