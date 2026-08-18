package com.edunext.edutrack.domain.imports;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /**
     * The import history panel, per registered schema — CLIENT or RESOURCE.
     * Matches {@code ix_import_batches_entity (entity, created_at)}, so the
     * sort is the index rather than a filesort.
     */
    List<ImportBatch> findByEntityOrderByCreatedAtDesc(String entity);

    /**
     * The import history panel, capped — B-037's {@code GET /import-batches}.
     *
     * <p>Same index and same ordering as the unbounded read above; the cap is
     * here rather than on the caller because this table only grows. A year of a
     * busy client master is thousands of runs, and a panel showing "the imports"
     * means the recent ones by any reading — nobody scrolls back to March to find
     * a batch to reverse, they reverse the one they just ran.
     *
     * <p>{@link Pageable} rather than a {@code findTop50By…} name, so the limit
     * is stated by the service that has a reason for it instead of being frozen
     * into a method name.
     */
    List<ImportBatch> findByEntityOrderByCreatedAtDesc(String entity, Pageable page);

    /**
     * Finds runs stuck mid-flight after a restart — {@code RUNNING} batches
     * whose job died with the JVM. There is no dry-run state to sweep up: step 4
     * writes nothing, so a crash during validation leaves no row behind.
     */
    List<ImportBatch> findByStatus(ImportBatchStatus status);
}
