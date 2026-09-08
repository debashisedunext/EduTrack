package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.common.canonical.CanonicalJsonException;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqHistory;
import com.edunext.edutrack.domain.onboarding.ObPrereqHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B-125 · the only door to {@code ob_prereq_history}, on
 * {@link ObStepJournal}'s exact precedent — which is itself
 * {@link TicketJournal}'s, one module over.
 *
 * <p>{@code AppendOnlyRulesTest.theProtectedTablesAreWrittenOnlyThroughTheJournal}
 * is stated over {@code assignableTo(AppendOnly.class)}, not over a list of
 * table names — so {@code ObPrereqHistoryRepository}, the moment it extends
 * {@code AppendOnly}, is covered by the identical rule: no class outside
 * {@code domain.journal} may depend on it.
 *
 * <p>⚠ <b>Touches Stream A's {@code domain/journal/}</b> (TEAM-PLAN.md §6),
 * on {@link ObStepJournal}'s own precedent for the identical shape of gap —
 * flagged for sign-off rather than added quietly. There is no other legal
 * home: the rule this class exists to satisfy names this package
 * specifically.
 *
 * <h2>The chain is per client</h2>
 *
 * <p>{@link ObStepJournal} chains per journey; this chains per client, and
 * the migration's §4 carries the argument. In short: the gate evaluation
 * reads every task the client has and moves the header row in the same
 * transaction, so the client is the parent every append already touches. Per
 * task would mean the gate-clearing verification holding N locks in an order
 * nothing guarantees, which is a deadlock against ourselves.
 *
 * <h2>One method, not five</h2>
 *
 * <p>{@link TicketJournal} grew {@code reverse*} and {@code seal} because
 * three tables and a decade of correction patterns needed them. The contract
 * declares {@code isCorrection}/{@code correctsEntryId} on this table's wire
 * shape but exposes no route that writes one, so a {@code reverse} here would
 * be surface with no caller — exactly what {@code TicketJournal}'s own
 * javadoc warns against. The entity carries both columns, so the task that
 * needs a compensating entry adds the method rather than a second door.
 */
@Service
public class ObPrereqJournal {

    /** Bump alongside any change to {@link #chainPayload}, on {@code ChainPayloads}' own precedent. */
    private static final int CHAIN_PAYLOAD_VERSION = 1;

    private final ObClientRepository clients;
    private final ObPrereqHistoryRepository history;

    public ObPrereqJournal(ObClientRepository clients, ObPrereqHistoryRepository history) {
        this.clients = clients;
        this.history = history;
    }

    /**
     * Append one {@code ob_prereq_history} entry, chained per client.
     *
     * <p>{@code MANDATORY}, on {@link ObStepJournal#append}'s own reasoning:
     * the lock this method takes must span exactly the caller's transaction,
     * or a task mutation and its history row could each open and close their
     * own, leaving a window for a concurrent append to interleave.
     *
     * @return the managed instance, its generated id populated
     * @throws AppendRejectedException if the entry carries a hash or an id
     *         already, omits {@code obClientId}, or names a client that does
     *         not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObPrereqHistory append(ObPrereqHistory entry) {
        if (entry == null) {
            throw new AppendRejectedException("a prerequisite history entry is required");
        }
        rejectPresetIdentityOrHash(entry);
        require(entry.getObClientId() != null,
                "a prerequisite history entry needs an ob_client_id — it is what the chain is keyed by");
        require(entry.getPrereqTaskId() != null,
                "a prerequisite history entry needs a prereq_task_id");
        require(entry.getToStatus() != null,
                "a prerequisite history entry needs a to_status — it is what the entry records");
        require(entry.getOccurredAt() != null,
                "a prerequisite history entry needs an occurred_at; created_at is when the row "
                        + "was written, which is not always when the thing happened");

        lockClientFor(entry.getObClientId());
        chain(entry, previousRowHash(entry.getObClientId()));
        return history.insert(entry);
    }

    /**
     * The lock, and the existence check that comes free with it — {@code
     * ObStepJournal#lockJourneyFor}'s own shape.
     */
    private void lockClientFor(Long obClientId) {
        if (clients.findByIdForUpdate(obClientId).isEmpty()) {
            throw new AppendRejectedException(
                    "no client " + obClientId + " to append a prerequisite history entry to");
        }
    }

    /**
     * The tail of this client's chain, read <b>after</b> the lock and never
     * before it — {@code ObStepJournal#previousRowHash}'s own reasoning
     * against the identical MySQL snapshot-read hazard.
     *
     * @return the predecessor's {@code row_hash}, or {@code null} if this row
     *         begins the chain
     */
    private String previousRowHash(Long obClientId) {
        return history.findFirstByObClientIdOrderByIdDesc(obClientId)
                .map(ObPrereqHistory::getRowHash)
                .orElse(null);
    }

    private void chain(ObPrereqHistory entry, String prevHash) {
        entry.setChainPayloadVersion(CHAIN_PAYLOAD_VERSION);
        entry.setPrevHash(prevHash);
        try {
            entry.setRowHash(ChainDigest.rowHash(prevHash, chainPayload(entry)));
        } catch (CanonicalJsonException e) {
            throw new AppendRejectedException(
                    "this prerequisite history entry cannot be hashed: " + e.getMessage(), e);
        }
    }

    /**
     * The hashed columns of an {@code ob_prereq_history} row, on
     * {@code ObStepJournal#chainPayload}'s exact convention: snake_case keys
     * matching the schema, a {@code _v} version marker, {@code id} and
     * {@code created_at} excluded because both are generated and null at hash
     * time, {@code prev_hash}/{@code row_hash} excluded because they are the
     * chain rather than the payload.
     *
     * <p>Enums are hashed by {@code name()} rather than by the enum instance,
     * so the payload is the string the column holds. A canonical serialiser
     * given an enum is free to render it however it likes; the verifier reads
     * the column back as text and must reproduce the same bytes.
     *
     * <p>Not folded into {@code ChainPayloads}: that class is Stream A's
     * shared builder for the three ticketing tables, and a fifth overload
     * there would be the quiet cross-stream edit this class's own javadoc
     * flags for the class as a whole.
     */
    private static Map<String, Object> chainPayload(ObPrereqHistory entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("_v", CHAIN_PAYLOAD_VERSION);
        payload.put("ob_client_id", entry.getObClientId());
        payload.put("prereq_task_id", entry.getPrereqTaskId());
        payload.put("occurred_at", entry.getOccurredAt());
        payload.put("actor_type", name(entry.getActorType()));
        payload.put("actor_user_id", entry.getActorUserId());
        payload.put("actor_contact_id", entry.getActorContactId());
        payload.put("from_status", name(entry.getFromStatus()));
        payload.put("to_status", name(entry.getToStatus()));
        payload.put("reason", entry.getReason());
        payload.put("is_correction", entry.isCorrection());
        payload.put("corrects_entry_id", entry.getCorrectsEntryId());
        return payload;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * The chain columns and the identifier belong to the journal, not the
     * caller — {@code ObStepJournal#rejectPresetIdentityOrHash}'s own
     * reasoning.
     */
    private static void rejectPresetIdentityOrHash(ObPrereqHistory entry) {
        if (entry.getId() != null) {
            throw new AppendRejectedException(
                    "this prerequisite history entry already carries id " + entry.getId()
                            + ". An append-only row is written once; a correction is a new row "
                            + "pointing at it, never a re-save.");
        }
        if (entry.getPrevHash() != null || entry.getRowHash() != null) {
            throw new AppendRejectedException(
                    "prev_hash and row_hash are written by the journal under the per-client "
                            + "lock, not by the caller. An entry arriving with either set has "
                            + "computed a chain link from a tail it read without the lock.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AppendRejectedException(message);
        }
    }
}
