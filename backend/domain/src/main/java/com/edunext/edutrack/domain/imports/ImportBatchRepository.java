package com.edunext.edutrack.domain.imports;

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
     * Finds runs stuck mid-flight after a restart — {@code RUNNING} batches
     * whose job died with the JVM. There is no dry-run state to sweep up: step 4
     * writes nothing, so a crash during validation leaves no row behind.
     */
    List<ImportBatch> findByStatus(ImportBatchStatus status);
}
