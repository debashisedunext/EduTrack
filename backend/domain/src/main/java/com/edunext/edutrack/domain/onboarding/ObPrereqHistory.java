package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * B-125 · one entry of {@code ob_prereq_history} — append-only and
 * hash-chained, <b>per client</b>.
 *
 * <p>{@link Immutable} is layer two of the four CLAUDE.md's append-only rule
 * names: it stops Hibernate dirty-checking a loaded row into an
 * {@code UPDATE} nobody wrote. Layer one is
 * {@link ObPrereqHistoryRepository} extending the bare {@code Repository}
 * marker plus {@code AppendOnly}, layer three the {@code edutrack_app} grant,
 * layer four the {@code BEFORE UPDATE}/{@code BEFORE DELETE} triggers
 * {@code V20260908_1600} installs.
 *
 * <h2>The chain is per client, and only the journal writes it</h2>
 *
 * <p>{@code ObPrereqJournal} is the only door. {@link #prevHash} and
 * {@link #rowHash} are set there, under a {@code SELECT … FOR UPDATE} on the
 * client row — an entry arriving with either already set has computed a link
 * from a tail it read without the lock, and is refused.
 *
 * <p>{@code prevHash} is null for the first row of each client. That is the
 * chain's anchor, not a missing value: the verifier walks per client and
 * starts where it is null.
 *
 * <h2>{@code reason} is copied, not referenced</h2>
 *
 * <p>The skip reason and the return comment are written here as text at the
 * moment they were given. A history row pointing at a comment row would be a
 * hash-chained record whose meaning lived in a table with no chain of its own
 * — it would prove that <em>something</em> was skipped and nothing about why.
 */
@Entity
@Immutable
@Table(name = "ob_prereq_history")
public class ObPrereqHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The chain key. Every hash walk is scoped to one client. */
    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "prereq_task_id", nullable = false)
    private Long prereqTaskId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ObPrereqActorType actorType = ObPrereqActorType.STAFF;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_contact_id")
    private Long actorContactId;

    /** Null on the first entry — instantiation has nothing to come from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 12)
    private ObPrereqTaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 12)
    private ObPrereqTaskStatus toStatus;

    @Column(name = "reason")
    private String reason;

    @Column(name = "is_correction", nullable = false)
    private boolean isCorrection;

    @Column(name = "corrects_entry_id")
    private Long correctsEntryId;

    @Column(name = "chain_payload_version", nullable = false)
    private int chainPayloadVersion = 1;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getObClientId() {
        return obClientId;
    }

    public void setObClientId(Long obClientId) {
        this.obClientId = obClientId;
    }

    public Long getPrereqTaskId() {
        return prereqTaskId;
    }

    public void setPrereqTaskId(Long prereqTaskId) {
        this.prereqTaskId = prereqTaskId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public ObPrereqActorType getActorType() {
        return actorType;
    }

    public void setActorType(ObPrereqActorType actorType) {
        this.actorType = actorType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(Long actorUserId) {
        this.actorUserId = actorUserId;
    }

    public Long getActorContactId() {
        return actorContactId;
    }

    public void setActorContactId(Long actorContactId) {
        this.actorContactId = actorContactId;
    }

    public ObPrereqTaskStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(ObPrereqTaskStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public ObPrereqTaskStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(ObPrereqTaskStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isCorrection() {
        return isCorrection;
    }

    public void setCorrection(boolean correction) {
        isCorrection = correction;
    }

    public Long getCorrectsEntryId() {
        return correctsEntryId;
    }

    public void setCorrectsEntryId(Long correctsEntryId) {
        this.correctsEntryId = correctsEntryId;
    }

    public int getChainPayloadVersion() {
        return chainPayloadVersion;
    }

    public void setChainPayloadVersion(int chainPayloadVersion) {
        this.chainPayloadVersion = chainPayloadVersion;
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
}
