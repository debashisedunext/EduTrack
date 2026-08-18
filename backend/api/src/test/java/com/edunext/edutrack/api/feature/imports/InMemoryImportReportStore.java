package com.edunext.edutrack.api.feature.imports;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * B-036 · {@link ImportReportStore} without MinIO.
 *
 * <p>The reason {@link ImportReportStore} is an interface at all — see its
 * javadoc, where the first reason is that nothing on it can produce a public
 * address and the test double is the consequence. This project has no MinIO
 * Testcontainer (C-025's attachment tests mock the storage for the same reason),
 * so without this every test of the commit path would exercise a run whose report
 * silently failed to store, and would prove the opposite of what it looks like.
 *
 * <p>{@link #failNext} is what makes the interesting assertion possible: an
 * object store that is down must cost the report and never the import.
 */
class InMemoryImportReportStore implements ImportReportStore {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private boolean failing;

    /** Every subsequent {@link #put} throws, as an unreachable bucket would. */
    void failNext() {
        this.failing = true;
    }

    @Override
    public String put(long batchId, String entityCode, byte[] workbook) {
        if (failing) {
            throw new IllegalStateException("object store unavailable");
        }
        String key = "imports/%s/%d/errors.xlsx".formatted(entityCode, batchId);
        objects.put(key, workbook);
        return key;
    }

    @Override
    public Optional<byte[]> read(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    /** What was stored, for a test that wants to read the workbook back. */
    Map<String, byte[]> objects() {
        return objects;
    }
}
