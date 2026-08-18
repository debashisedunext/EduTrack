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
 * row by row (B-037): every client the run <em>created</em> carries this batch
 * id, and {@link #reversedAt} records that the set was taken back. <b>The batch
 * row itself is never deleted</b> — it is the audit trail, and a reversal that
 * removed its own record would leave the master short of rows with nothing
 * anywhere explaining why.
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

    /**
     * B-037 · when this run was reversed as a set, or {@code null} for the
     * ordinary case of a run nobody has undone.
     *
     * <p><b>Null is the whole state machine.</b> A boolean beside a timestamp
     * would be two columns that can disagree, and the one question the service
     * asks — "has this already been reversed?" — is answered by the presence of
     * the fact rather than by a flag about it.
     *
     * <p>Deliberately not a {@link ImportBatchStatus} value: {@code status}
     * records how the <em>run</em> ended and reversal is a later fact about a run
     * that has already ended. See {@code V20260818_1210__import_batch_reversal}
     * for why collapsing them loses the outcome.
     */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** Who reversed it — best-effort and scalar, exactly like {@link #importedBy}. */
    @Column(name = "reversed_by")
    private Long reversedBy;

    /**
     * B-037 · rows this run <em>created</em> that the reversal deleted.
     *
     * <p>Never counts rows it merely updated. {@code import_batch_id} is stamped
     * on insert only, so a client an import edited is not attributed to it — and
     * there is no before image, so an update is not something a reversal can
     * undo. The API says so rather than letting "reversed" imply otherwise.
     */
    @Column(name = "reversed_rows", nullable = false)
    private int reversedRows;

    /**
     * B-037 · rows this run created that the reversal could <b>not</b> delete.
     *
     * <p>A client the import created and which has since been named on a ticket:
     * {@code tickets.client_id} is RESTRICT, and the alternatives to keeping it
     * are failing the whole reversal because one client got used, or destroying a
     * ticket's client. Counted here because it is not derivable afterwards —
     * once the other rows are gone, an unreversed batch and a fully reversed one
     * both count zero.
     */
    @Column(name = "retained_rows", nullable = false)
    private int retainedRows;

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

    public Instant getReversedAt() {
        return reversedAt;
    }

    public void setReversedAt(Instant reversedAt) {
        this.reversedAt = reversedAt;
    }

    public Long getReversedBy() {
        return reversedBy;
    }

    public void setReversedBy(Long reversedBy) {
        this.reversedBy = reversedBy;
    }

    public int getReversedRows() {
        return reversedRows;
    }

    public void setReversedRows(int reversedRows) {
        this.reversedRows = reversedRows;
    }

    public int getRetainedRows() {
        return retainedRows;
    }

    public void setRetainedRows(int retainedRows) {
        this.retainedRows = retainedRows;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
