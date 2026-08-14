package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A-042 · which columns of a protected row the hash chain is computed over.
 *
 * <p>PLAN.md §3.7 defines the chain as
 * {@code row_hash = SHA256(prev_hash ‖ canonical_json(payload))}. A-041 fixed
 * the <i>bytes</i> — key order, timestamp format, escaping — and deliberately
 * left the column set open, saying so in {@code CanonicalJsonGoldenFileTest}:
 * "Which columns A-042 actually hashes is A-042's to define in {@code domain},
 * where the entities are." This is that definition.
 *
 * <p>It lives in {@code domain} because {@code common} cannot see the entities
 * and must not — it is on every module's classpath precisely because it depends
 * on none of them. {@code domain} is on both {@code api}'s and {@code worker}'s,
 * so A-042's journal and A-044's nightly verifier share <b>one</b> builder.
 * Two builders, one per side, is the failure A-041 exists to prevent, one level
 * up: they agree until somebody edits one.
 *
 * <h2>What is hashed, and what is not</h2>
 *
 * <p>Three exclusions, each forced rather than chosen:
 *
 * <ol>
 *   <li><b>{@code id} and the row timestamps.</b> {@code created_at},
 *       {@code logged_at} and {@code id} are {@code @Generated(event = INSERT)}
 *       or {@code AUTO_INCREMENT} — at hash time they are null, and by
 *       verification time they hold a value. Hashing one guarantees a mismatch
 *       on every row, which is the single failure that looks exactly like the
 *       whole table having been tampered with. A-041's javadoc says the same.
 *       <p><b>Consequence worth stating: a history entry's own timestamp is not
 *       in the chain.</b> {@code trg_hist_no_update} rejects every update to
 *       {@code ticket_history}, so it is trigger-protected; it is not
 *       chain-protected. Making it settable would put the application clock in
 *       charge across instances and would let a caller backdate an audit row,
 *       which is worse than the gap.</li>
 *   <li><b>{@code prev_hash} and {@code row_hash}.</b> They are the chain, not
 *       the payload — {@code prev_hash} is concatenated <i>outside</i>
 *       {@code canonical_json(...)}, exactly as §3.7 writes it, and
 *       {@code row_hash} is the result.</li>
 *   <li><b>{@code exited_at}, {@code duration_mins} and {@code is_current}</b>
 *       on a transition. See the next heading — this is the one exclusion that
 *       is a real gap rather than an accounting detail.</li>
 * </ol>
 *
 * <h2>The seal columns, and why excluding them costs nothing here but is still
 * a gap</h2>
 *
 * <p>{@link TicketJournal#append(TicketStageTransition)} refuses any hop that is
 * not appended open: {@code exited_at} null, {@code duration_mins} null,
 * {@code is_current} true. Those three are therefore <em>constants</em> at hash
 * time, and a constant contributes no entropy to a digest — including them would
 * produce an identically strong hash and a longer payload. There is no version
 * of this class in which the sealed values are covered by the hop's own
 * {@code row_hash}: the seal happens in a later transaction, and re-hashing is
 * impossible anyway because {@code trg_stage_seal_only} lists {@code row_hash}
 * among the columns a seal may not touch.
 *
 * <p>So the gap is not created by this choice, it is inherent — but it is real
 * and belongs written down. After a seal, {@code duration_mins} is protected by
 * the A-008 trigger (which rejects any update once {@code exited_at} is
 * non-null) and by there being no service method that touches it. It is
 * <b>not</b> protected by the grants — {@code edutrack_app} holds {@code UPDATE}
 * on this table precisely so sealing works — and it is not protected by the
 * chain. Two layers where every other column has four, on the number PLAN.md
 * §3.5 names the chain as the backstop for, and the number the DDL comment says
 * tells a manager "a 2-day stage with 2 hours of effort is a queue problem".
 *
 * <p><b>For A-044:</b> closing this needs the sealed values to enter some chain
 * — the natural candidate is the history row Stream C writes alongside every
 * transition — and then a cross-table check rather than a chain walk. It was
 * considered for A-042 and left out because it reverses A-040's stated boundary
 * ("writing the history row that accompanies a transition ... belongs to Stream
 * C's transition service") and would change {@link TicketJournal#seal}'s
 * signature to carry an actor.
 *
 * <h2>The version marker</h2>
 *
 * <p>Every payload carries {@code _v}. A-041 suggested one and it is worth being
 * exact about what it does, because the name promises more than it delivers: to
 * reproduce an old row's hash the verifier must <i>know</i> which version to
 * apply, and there is nowhere to record it per row — {@code row_hash} is
 * {@code CHAR(64)}, exactly a SHA-256, with no room. Trying v1 and then v2 would
 * hand a forger two attempts.
 *
 * <p>So when the hashed column set next changes, the mechanism is an id boundary
 * per table, recorded as a constant alongside the migration that changed it, and
 * {@code _v} is what makes the two rule sets unambiguous in the bytes rather
 * than something inferred. Today there is one version and the boundary list is
 * empty. Bump {@link #VERSION} in the same commit as any change to a column set
 * below, or rows hashed under both rules become indistinguishable.
 *
 * <h2>Keys are the column names</h2>
 *
 * <p>snake_case, matching the schema. A-041 sorts keys lexicographically, so
 * this choice changes the bytes and cannot be revisited once rows exist. Both
 * sides share this builder, so agreement was never at stake — legibility was.
 * Somebody debugging a hash mismatch is holding a payload in one hand and a
 * {@code SELECT *} in the other, and the row speaks snake_case.
 *
 * <h2>Values are passed through, never adjusted</h2>
 *
 * <p>In particular an {@code Instant} is <b>not</b> truncated here.
 * {@code CanonicalJson} refuses sub-microsecond precision rather than truncating
 * it, and this class must not undo that: the value that goes into the hash and
 * the value that goes into the {@code INSERT} are the same object, so truncating
 * on the way to the digest only would guarantee the mismatch the refusal exists
 * to prevent. The caller truncates once, before either.
 */
public final class ChainPayloads {

    /**
     * The revision of the column sets below. See the class javadoc — bump this
     * in the same commit that changes any of them, and record the id boundary.
     */
    public static final int VERSION = 1;

    /** The version marker's key. Sorts first among the column names, being {@code _}. */
    private static final String VERSION_KEY = "_v";

    private ChainPayloads() {
    }

    /**
     * The hashed columns of a {@code ticket_history} row.
     *
     * <p>Everything the caller supplies, which is everything except {@code id}
     * and {@code created_at}. {@code actor_id} and {@code actor_type} are both
     * present because the pair is the claim being made — that a named person, or
     * the system, did this — and a chain that covered only one of them would let
     * an entry be reattributed.
     */
    public static Map<String, Object> of(TicketHistory entry) {
        Objects.requireNonNull(entry, "a history entry is required to build its payload");

        Map<String, Object> payload = versioned();
        payload.put("ticket_id", entry.getTicketId());
        payload.put("cycle_no", entry.getCycleNo());
        payload.put("event_type", entry.getEventType());
        payload.put("field_name", entry.getFieldName());
        payload.put("old_value", entry.getOldValue());
        payload.put("new_value", entry.getNewValue());
        payload.put("actor_id", entry.getActorId());
        payload.put("actor_type", entry.getActorType());
        payload.put("remarks", entry.getRemarks());
        payload.put("is_correction", entry.isCorrection());
        payload.put("corrects_entry_id", entry.getCorrectsEntryId());
        return payload;
    }

    /**
     * The hashed columns of a {@code ticket_effort_logs} row.
     *
     * <p>{@code hours} is a {@code BigDecimal} and reaches
     * {@code CanonicalJson} as one: the column is {@code DECIMAL(5,2)}, so a row
     * written from {@code 8} is read back by the verifier as {@code 8.00}, and
     * only {@code stripTrailingZeros} makes those one value. {@code work_date}
     * is a {@code LocalDate} — it is a calendar date in the worker's own terms,
     * not an instant, and rendering it through a timezone is how the same day's
     * effort acquires two hashes.
     */
    public static Map<String, Object> of(TicketEffortLog entry) {
        Objects.requireNonNull(entry, "an effort log is required to build its payload");

        Map<String, Object> payload = versioned();
        payload.put("ticket_id", entry.getTicketId());
        payload.put("cycle_no", entry.getCycleNo());
        payload.put("stage_code", entry.getStageCode());
        payload.put("iteration_no", entry.getIterationNo());
        payload.put("user_id", entry.getUserId());
        payload.put("work_date", entry.getWorkDate());
        payload.put("hours", entry.getHours());
        payload.put("note", entry.getNote());
        payload.put("is_correction", entry.isCorrection());
        payload.put("corrects_entry_id", entry.getCorrectsEntryId());
        return payload;
    }

    /**
     * The hashed columns of a {@code ticket_stage_transitions} hop, as appended
     * — open.
     *
     * <p>{@code entered_at} <b>is</b> hashed even though the row's
     * {@code created_at} is not: the caller supplies it, so it exists at hash
     * time, and it is the timestamp the ribbon's arithmetic is actually built on.
     *
     * <p>The three seal columns are absent by the reasoning in the class javadoc.
     * A verifier reconstructing this payload from a <i>sealed</i> row therefore
     * needs no special case — it simply does not read them.
     */
    public static Map<String, Object> of(TicketStageTransition hop) {
        Objects.requireNonNull(hop, "a stage transition is required to build its payload");

        Map<String, Object> payload = versioned();
        payload.put("ticket_id", hop.getTicketId());
        payload.put("cycle_no", hop.getCycleNo());
        payload.put("iteration_no", hop.getIterationNo());
        payload.put("seq_no", hop.getSeqNo());
        payload.put("from_stage", hop.getFromStage());
        payload.put("to_stage", hop.getToStage());
        payload.put("from_user_id", hop.getFromUserId());
        payload.put("to_user_id", hop.getToUserId());
        payload.put("action_code", hop.getActionCode());
        payload.put("handoff_note", hop.getHandoffNote());
        payload.put("reason", hop.getReason());
        payload.put("entered_at", hop.getEnteredAt());
        return payload;
    }

    /**
     * A mutable map, because every payload here has null-valued columns and
     * {@code Map.of} rejects those. {@code LinkedHashMap} rather than
     * {@code HashMap} only so that printing one during an investigation shows
     * the columns in schema order; {@code CanonicalJson} sorts them regardless,
     * and nothing may depend on the insertion order.
     */
    private static Map<String, Object> versioned() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(VERSION_KEY, VERSION);
        return payload;
    }
}
