package com.edunext.edutrack.domain.workflow;

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

import java.time.Instant;

/**
 * One hop of a ticket's journey. <b>APPEND ONLY · HASH CHAINED.</b>
 *
 * <p>Ordered by {@code seqNo}, this table <em>is</em> the ribbon: every segment
 * rendered in blueprint §4A.3, every row of the §4A.4 effort grid and every
 * stage-SLA the Stream D scanner checks reads from here.
 *
 * <h2>The two independent counters (§4A.2)</h2>
 *
 * The single most misread concept in the spec, so it is restated where the
 * columns live:
 *
 * <ul>
 *   <li>{@code iterationNo} increments when a ticket moves <b>backwards</b>
 *       inside a cycle. QA fails the build, back to DEV, iteration 2.</li>
 *   <li>{@code cycleNo} increments when a <b>closed</b> ticket is
 *       <b>reopened</b>. Cycle 2 starts a fresh journey with iteration back
 *       at 1.</li>
 * </ul>
 *
 * They are not nested versions of the same idea and neither derives from the
 * other. A ticket can legitimately be cycle 2 / iteration 3.
 *
 * <h2>Immutability</h2>
 *
 * {@link Immutable} stops Hibernate dirty-checking a loaded row into an
 * {@code UPDATE} that nobody wrote — the failure mode PLAN.md §3.6 calls out,
 * where an ORM rewrites every column because a service touched a field. The
 * repository has no {@code save} or {@code delete} to call, and the A-008
 * trigger rejects anything that reaches the database anyway.
 *
 * <p><b>The one permitted mutation is sealing</b> — {@code exitedAt} NULL to a
 * timestamp, with {@code durationMins} computed and {@code isCurrent} dropped.
 * It is performed by an explicit JPQL update on those three columns
 * ({@code TicketStageTransitionRepository#seal}), never by mutating a loaded
 * instance. The setters below exist for the insert path; calling one on a row
 * already in the database changes nothing, because Hibernate will not flush it.
 *
 * <p>{@code durationMins} is <b>working</b> minutes from the calendar service
 * (B-024), not wall-clock. It feeds "idle vs active" in §4A.4 — the number that
 * tells a manager a two-day stage holding two hours of effort is a queue
 * problem, not a capacity problem.
 */
@Entity
@Immutable
@Table(name = "ticket_stage_transitions")
public class TicketStageTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Scalar rather than a {@code @ManyToOne} to {@code Ticket}, for two
     * reasons: rendering a ribbon would otherwise attach a lazy proxy to every
     * one of its segments, and {@code domain.workflow} would take a compile
     * dependency on {@code domain.tickets} that the reverse direction already
     * needs.
     */
    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "cycle_no", nullable = false)
    private short cycleNo;

    @Column(name = "iteration_no", nullable = false)
    private short iterationNo = 1;

    /** Hop order within the cycle: 1, 2, 3… */
    @Column(name = "seq_no", nullable = false)
    private int seqNo;

    /** NULL on the very first hop — the ticket came from nowhere. */
    @Column(name = "from_stage", length = 20)
    private String fromStage;

    @Column(name = "to_stage", nullable = false, length = 20)
    private String toStage;

    @Column(name = "from_user_id")
    private Long fromUserId;

    @Column(name = "to_user_id")
    private Long toUserId;

    /**
     * FORWARD | REWORK | DEPLOY_FAILED | VERIFY_FAILED | SIGNOFF_REJECTED |
     * CLARIFICATION | SKIP | OVERRIDE.
     */
    @Column(name = "action_code", nullable = false, length = 20)
    private String actionCode;

    @Column(name = "handoff_note", columnDefinition = "text")
    private String handoffNote;

    /** Mandatory on any backward move — enforced in the service layer (§4A.6). */
    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt;

    /** NULL means the ticket is in this stage right now. */
    @Column(name = "exited_at")
    private Instant exitedAt;

    /** Working minutes, set on seal. */
    @Column(name = "duration_mins")
    private Integer durationMins;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent = true;

    /**
     * Hex SHA-256, {@code CHAR(64)} with {@code ascii_bin} (PLAN.md §3.1). The
     * {@link SqlTypes#CHAR} code is what makes {@code ddl-auto=validate} agree;
     * without it Hibernate expects {@code VARCHAR} and refuses to start.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * A-009's stored generated column, {@code IF(is_current = 1, ticket_id,
     * NULL)} — MySQL's stand-in for the partial index PostgreSQL would have
     * used (PLAN.md §3.3). Read-only by definition; the database recomputes it
     * when the seal drops {@code is_current}. Mapped because "where is this
     * ticket right now" is asked on every detail-page load and every ribbon
     * render, and querying this column is what keeps that off a scan.
     */
    @Column(name = "current_ticket_id", insertable = false, updatable = false)
    private Long currentTicketId;

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

    public short getIterationNo() {
        return iterationNo;
    }

    public void setIterationNo(short iterationNo) {
        this.iterationNo = iterationNo;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(int seqNo) {
        this.seqNo = seqNo;
    }

    public String getFromStage() {
        return fromStage;
    }

    public void setFromStage(String fromStage) {
        this.fromStage = fromStage;
    }

    public String getToStage() {
        return toStage;
    }

    public void setToStage(String toStage) {
        this.toStage = toStage;
    }

    public Long getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(Long fromUserId) {
        this.fromUserId = fromUserId;
    }

    public Long getToUserId() {
        return toUserId;
    }

    public void setToUserId(Long toUserId) {
        this.toUserId = toUserId;
    }

    public String getActionCode() {
        return actionCode;
    }

    public void setActionCode(String actionCode) {
        this.actionCode = actionCode;
    }

    public String getHandoffNote() {
        return handoffNote;
    }

    public void setHandoffNote(String handoffNote) {
        this.handoffNote = handoffNote;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }

    public void setEnteredAt(Instant enteredAt) {
        this.enteredAt = enteredAt;
    }

    public Instant getExitedAt() {
        return exitedAt;
    }

    public void setExitedAt(Instant exitedAt) {
        this.exitedAt = exitedAt;
    }

    public Integer getDurationMins() {
        return durationMins;
    }

    public void setDurationMins(Integer durationMins) {
        this.durationMins = durationMins;
    }

    public boolean isCurrent() {
        return isCurrent;
    }

    public void setCurrent(boolean current) {
        this.isCurrent = current;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCurrentTicketId() {
        return currentTicketId;
    }
}
