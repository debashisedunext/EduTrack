package com.edunext.edutrack.domain.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * One Excel import run — S-34, blueprint §4B.3.
 *
 * <p>{@code entity} is what makes the importer a schema <em>registry</em>
 * rather than two implementations: CLIENT today, RESOURCE from B-038, and any
 * later registration without a migration. Blueprint §4B.3 — build it once,
 * register two schemas.
 *
 * <p>The row is also what makes a bad import reversible as a set rather than
 * row by row (B-037): every client written by a run carries this batch id.
 *
 * <p>The counters are stamped by the commit step, after the step-4 dry run has
 * already previewed the same outcomes without writing anything.
 */
@Entity
@Table(name = "import_batches")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CLIENT | RESOURCE — the registered schema this run was validated against. */
    @Column(name = "entity", nullable = false, length = 30)
    private String entity;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_rows", nullable = false)
    private int createdRows;

    @Column(name = "updated_rows", nullable = false)
    private int updatedRows;

    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows;

    /**
     * The contract's vocabulary, and since
     * {@code V20260810_2010__import_batch_status_vocabulary} the only one
     * {@code ck_import_batches_status} permits — see {@link ImportBatchStatus}.
     *
     * <p>{@code STRING}, not {@code ORDINAL}: the column is a {@code VARCHAR}
     * the CHECK constraint reads by name, and an ordinal would make reordering
     * this enum silently rewrite the meaning of every stored row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportBatchStatus status = ImportBatchStatus.QUEUED;

    /**
     * Object-storage key of the B-036 error report: the rejected rows as
     * uploaded, plus an appended Reason column, so the user fixes and
     * re-uploads only what failed.
     */
    @Column(name = "error_report_key", length = 400)
    private String errorReportKey;

    /** An actor id, kept scalar — see package-info. */
    @Column(name = "imported_by")
    private Long importedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getCreatedRows() {
        return createdRows;
    }

    public void setCreatedRows(int createdRows) {
        this.createdRows = createdRows;
    }

    public int getUpdatedRows() {
        return updatedRows;
    }

    public void setUpdatedRows(int updatedRows) {
        this.updatedRows = updatedRows;
    }

    public int getRejectedRows() {
        return rejectedRows;
    }

    public void setRejectedRows(int rejectedRows) {
        this.rejectedRows = rejectedRows;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public void setStatus(ImportBatchStatus status) {
        this.status = status;
    }

    public String getErrorReportKey() {
        return errorReportKey;
    }

    public void setErrorReportKey(String errorReportKey) {
        this.errorReportKey = errorReportKey;
    }

    public Long getImportedBy() {
        return importedBy;
    }

    public void setImportedBy(Long importedBy) {
        this.importedBy = importedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
