package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.common.canonical.CanonicalJsonException;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.ObStepHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C-107 · the only door to {@code ob_step_history}, on {@link TicketJournal}'s
 * exact precedent one module over.
 *
 * <p>{@code AppendOnlyRulesTest.theProtectedTablesAreWrittenOnlyThroughTheJournal}
 * is stated over {@code assignableTo(AppendOnly.class)}, not over a list of
 * three ticketing names — so {@link ObStepHistoryRepository}, the moment it
 * extends {@code AppendOnly}, is covered by the identical rule: no class
 * outside {@code domain.journal} may depend on it. {@code
 * ObJourneyStepLifecycleService} held it directly for exactly one commit
 * before CI said so.
 *
 * <p>⚠ <b>Touches Stream A's {@code domain/journal/}</b> (TEAM-PLAN.md §6),
 * on {@code TicketJournal#hopsFor}'s own precedent for the identical
 * shape of gap — flagged for sign-off rather than added quietly. There was
 * no other legal home: the rule this class exists to satisfy names this
 * package specifically.
 *
 * <h2>One method, not five</h2>
 *
 * <p>{@link TicketJournal} grew {@code append}, {@code reverse*} and
 * {@code seal} because three tables and a decade of correction patterns
 * needed them. {@code ob_step_history} has exactly one writer today —
 * {@code ObJourneyStepLifecycleService#skip} — and no compensating-entry
 * requirement has been asked for it yet (plan §3 does not mention correcting
 * a skip). Adding {@code reverse} now would be exactly the kind of
 * speculative surface {@code TicketJournal}'s own javadoc warns a caller off
 * — it does not orchestrate, and neither does this. The next task that needs
 * a second event type (C-105's clock pauses, C-119's activations) extends
 * this class rather than writing a second door.
 */
@Service
public class ObStepJournal {

    /** Bump alongside any change to {@link #chainPayload}, on {@code ChainPayloads}' own precedent. */
    private static final int CHAIN_PAYLOAD_VERSION = 1;

    private final ObJourneyRepository journeys;
    private final ObStepHistoryRepository history;

    public ObStepJournal(ObJourneyRepository journeys, ObStepHistoryRepository history) {
        this.journeys = journeys;
        this.history = history;
    }

    /**
     * Append one {@code ob_step_history} entry, chained per journey.
     *
     * <p>{@code MANDATORY}, on {@link TicketJournal#append(com.edunext.edutrack.domain.tickets.TicketHistory)}'s
     * own reasoning: the lock this method takes must span exactly the
     * caller's transaction, or a step mutation and its history row could
     * each open and close their own, leaving a window for a concurrent
     * append to interleave.
     *
     * @return the managed instance, its generated id populated
     * @throws AppendRejectedException if the entry carries a hash or an id
     *         already, omits {@code journeyId}, or names a journey that does
     *         not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ObStepHistory append(ObStepHistory entry) {
        if (entry == null) {
            throw new AppendRejectedException("a step history entry is required");
        }
        rejectPresetIdentityOrHash(entry);
        require(entry.getJourneyId() != null,
                "a step history entry needs a journey_id — it is what the chain is keyed by");

        lockJourneyFor(entry.getJourneyId());
        chain(entry, previousRowHash(entry.getJourneyId()));
        return history.insert(entry);
    }

    /**
     * The lock, and the existence check that comes free with it — {@code
     * TicketJournal#lockTicketFor}'s own shape.
     */
    private void lockJourneyFor(Long journeyId) {
        if (journeys.findByIdForUpdate(journeyId).isEmpty()) {
            throw new AppendRejectedException(
                    "no journey " + journeyId + " to append a step history entry to");
        }
    }

    /**
     * The tail of this journey's chain, read <b>after</b> the lock and never
     * before it — {@code TicketJournal#previousRowHash}'s own reasoning
     * against the identical MySQL snapshot-read hazard.
     *
     * @return the predecessor's {@code row_hash}, or {@code null} if this row
     *         begins the chain
     */
    private String previousRowHash(Long journeyId) {
        return history.findFirstByJourneyIdOrderByIdDesc(journeyId)
                .map(ObStepHistory::getRowHash)
                .orElse(null);
    }

    private void chain(ObStepHistory entry, String prevHash) {
        entry.setPrevHash(prevHash);
        try {
            entry.setRowHash(ChainDigest.rowHash(prevHash, chainPayload(entry)));
        } catch (CanonicalJsonException e) {
            throw new AppendRejectedException(
                    "this step history entry cannot be hashed: " + e.getMessage(), e);
        }
    }

    /**
     * The hashed columns of an {@code ob_step_history} row, on {@code
     * ChainPayloads.of(TicketHistory)}'s exact convention: snake_case keys
     * matching the schema, a {@code _v} version marker, {@code id} and
     * {@code created_at} excluded because both are {@code @Generated}/{@code
     * AUTO_INCREMENT} and null at hash time, {@code prev_hash}/{@code
     * row_hash} excluded because they are the chain rather than the payload.
     *
     * <p>Not folded into {@code ChainPayloads} itself: that class is Stream
     * A's shared builder for the three ticketing tables, and a fourth
     * {@code of(ObStepHistory)} overload there would be the same kind of
     * quiet cross-stream edit this class's own javadoc flags for the class
     * as a whole — kept local instead, since this is the only caller.
     */
    private static Map<String, Object> chainPayload(ObStepHistory entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("_v", CHAIN_PAYLOAD_VERSION);
        payload.put("journey_id", entry.getJourneyId());
        payload.put("step_id", entry.getStepId());
        payload.put("ob_client_id", entry.getObClientId());
        payload.put("event_type", entry.getEventType());
        payload.put("field_name", entry.getFieldName());
        payload.put("old_value", entry.getOldValue());
        payload.put("new_value", entry.getNewValue());
        payload.put("actor_id", entry.getActorId());
        payload.put("actor_type", entry.getActorType());
        payload.put("actor_contact_id", entry.getActorContactId());
        payload.put("remarks", entry.getRemarks());
        payload.put("is_correction", entry.isCorrection());
        payload.put("corrects_entry_id", entry.getCorrectsEntryId());
        return payload;
    }

    /**
     * The chain columns and the identifier belong to the journal, not the
     * caller — {@code TicketJournal#rejectPresetIdentityOrHash}'s own
     * reasoning.
     */
    private static void rejectPresetIdentityOrHash(ObStepHistory entry) {
        if (entry.getId() != null) {
            throw new AppendRejectedException(
                    "this step history entry already carries id " + entry.getId() + ". An "
                            + "append-only row is written once; a correction is a new row "
                            + "pointing at it, never a re-save.");
        }
        if (entry.getPrevHash() != null || entry.getRowHash() != null) {
            throw new AppendRejectedException(
                    "prev_hash and row_hash are written by the journal under the per-journey "
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
