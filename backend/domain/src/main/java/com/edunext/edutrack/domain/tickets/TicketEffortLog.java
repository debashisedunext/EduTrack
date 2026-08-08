package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Hours logged against a ticket. <b>APPEND ONLY · HASH CHAINED.</b>
 *
 * <p>{@code stageCode} and {@code iterationNo} are what attribute effort to a
 * ribbon segment rather than to the ticket as a whole — they drive the §4A.4
 * grid, and with {@code cycleNo} they are the four columns the roll-up joins
 * on. PLAN.md §3.4 records why all four are needed: the blueprint's roll-up
 * query omits {@code cycleNo}, so a reopened ticket re-entering the same stage
 * at the same iteration double-counts cycle 1's effort into cycle 2.
 *
 * <p>A logged hour is never edited or deleted. {@code isCorrection} rows carry
 * the adjustment, and a correction <b>may be negative</b> — that is the point
 * of a reversal, and the {@code ck_effort_hours} constraint relaxes the
 * positive check for exactly those rows. Zero is rejected either way: an entry
 * of no hours carries no information.
 *
 * <p>Like {@link TicketHistory}, this is {@link Immutable} and reachable only
 * through {@link com.edunext.edutrack.domain.appendonly.AppendOnly#insert}.
 */
@Entity
@Immutable
@Table(name = "ticket_effort_logs")
public class TicketEffortLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "cycle_no", nullable = false)
    private short cycleNo;

    @Column(name = "stage_code", length = 20)
    private String stageCode;

    @Column(name = "iteration_no", nullable = false)
    private short iterationNo = 1;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * A date, not an instant — "I worked 3 hours on Tuesday" has no time of day
     * and must not acquire one from a timezone.
     */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** Negative only on a correction row; never zero. */
    @Column(name = "hours", nullable = false, precision = 5, scale = 2)
    private BigDecimal hours;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "is_correction", nullable = false)
    private boolean isCorrection;

    @Column(name = "corrects_entry_id")
    private Long correctsEntryId;

    /**
     * {@code CHAR(64)}, not {@code VARCHAR} — a hex SHA-256 is always exactly
     * 64 characters, and PLAN.md §3.1 pins the collation to {@code ascii_bin}
     * so comparison is exact and carries no utf8mb4 overhead. The
     * {@link SqlTypes#CHAR} code is what makes {@code ddl-auto=validate} agree.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "logged_at", insertable = false, updatable = false)
    private Instant loggedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public short getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(short cycleNo) {
        this.cycleNo = cycleNo;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public short getIterationNo() {
        return iterationNo;
    }

    public void setIterationNo(short iterationNo) {
        this.iterationNo = iterationNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public BigDecimal getHours() {
        return hours;
    }

    public void setHours(BigDecimal hours) {
        this.hours = hours;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean isCorrection() {
        return isCorrection;
    }

    public void setCorrection(boolean correction) {
        this.isCorrection = correction;
    }

    public Long getCorrectsEntryId() {
        return correctsEntryId;
    }

    public void setCorrectsEntryId(Long correctsEntryId) {
        this.correctsEntryId = correctsEntryId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }

    public void setRowHash(String rowHash) {
        this.rowHash = rowHash;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }
}
