package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.common.canonical.CanonicalJsonException;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A-040 · the only door to {@code ticket_history}, {@code ticket_effort_logs}
 * and {@code ticket_stage_transitions}.
 *
 * <p>The three repositories already publish nothing but {@code insert} — B-005
 * made that structural by extending the bare {@code Repository} marker instead
 * of {@code JpaRepository}, so no {@code save} or {@code delete} exists to be
 * called. What they cannot express is everything that has to be <em>true around</em>
 * an append, and this class exists for exactly that list.
 *
 * <h2>Why a door and not three service beans</h2>
 *
 * <p>The backlog says "services", plural. One class, because the thing being
 * guarded is shared: PLAN.md §3.7 requires that a handoff writing history,
 * effort and a transition together holds <b>one</b> lock rather than three, and
 * the lock is therefore not a per-table concern. Three classes would also mean
 * three copies of the same six lines — and {@code AppendOnlyImpl}'s javadoc
 * already records what three copies lead to: one of them eventually grows an
 * {@code update}.
 *
 * <h2>What a caller gets that a repository call does not</h2>
 *
 * <ol>
 *   <li><b>The per-ticket lock.</b> {@code SELECT … FOR UPDATE} on the parent
 *       ticket row before every append (PLAN.md §3.7). Two concurrent appends
 *       that both read the same chain tail produce a fork — two rows sharing a
 *       {@code prev_hash} — which A-044's nightly verifier reports as tampering
 *       when it was really our own race, months after the code that caused it
 *       shipped.</li>
 *   <li><b>{@link Propagation#MANDATORY}.</b> See below; it is the half of the
 *       lock that is easiest to get wrong.</li>
 *   <li><b>The chain columns are not the caller's to write.</b> A-042 fills
 *       {@code prevHash} and {@code rowHash} inside this class, under the lock
 *       that has just been taken. A caller arriving with either already set is
 *       refused now, so that when A-042 lands there is no existing call site
 *       whose hand-rolled hash has to be honoured or migrated.</li>
 *   <li><b>The invariants the database cannot hold</b> — and only those. See
 *       the next heading.</li>
 *   <li><b>Sealing</b>, the single mutation the A-008 trigger permits, kept
 *       beside the appends because it takes the same lock for the same
 *       reason.</li>
 * </ol>
 *
 * <h2>Why {@code MANDATORY} rather than the default</h2>
 *
 * <p>A pessimistic lock is held for the length of the transaction that took it.
 * With the default {@code REQUIRED}, a caller outside a transaction gets one
 * opened and committed around this single method — the row is locked and
 * released before the caller's next append, so a handoff writing three tables
 * would take three separate locks in sequence and a concurrent handoff could
 * interleave between them. Nothing fails; the ribbon simply ends up with two
 * open hops, or the chain forks. {@code MANDATORY} makes that unwriteable: the
 * caller must already have a transaction, so every append inside it shares one
 * lock. {@code TicketCodeAllocator#next} is {@code MANDATORY} for the same
 * reason and set the precedent.
 *
 * <h2>What this class deliberately does not check</h2>
 *
 * <p>It does not restate a constraint the database already holds.
 * {@code ck_effort_hours} rejects zero hours, more than 24, and a negative
 * outside a correction row; {@code uq_stage_transitions} rejects a duplicate
 * {@code seq_no}; the {@code NOT NULL} columns reject themselves. Repeating any
 * of them here would produce a friendlier message today and two rules that
 * disagree the first time one is changed — which is the failure the four-layer
 * immutability design is otherwise built to avoid. What is enforced below is
 * only what no constraint can see: a relationship between two columns, or
 * between a new row and one already stored.
 *
 * <p>It also does not orchestrate. Deciding which stage comes next, allocating
 * {@code seqNo}, computing working minutes from the B-024 calendar, writing the
 * history row that accompanies a transition — those belong to Stream C's
 * transition service, which calls this. The journal is a door, not a workflow.
 */
@Service
public class TicketJournal {

    /**
     * Backward moves, per §4A.2/§4A.6. {@code OVERRIDE} is not here on purpose:
     * a PM reopening a closed ticket moves it backwards through the workflow but
     * carries its own reason on the cycle, and {@code SKIP} moves forwards.
     */
    private static final Set<String> BACKWARD_ACTIONS =
            Set.of("REWORK", "DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED");

    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final TicketRepository tickets;
    private final TicketHistoryRepository history;
    private final TicketEffortLogRepository effortLogs;
    private final TicketStageTransitionRepository transitions;

    public TicketJournal(TicketRepository tickets,
                         TicketHistoryRepository history,
                         TicketEffortLogRepository effortLogs,
                         TicketStageTransitionRepository transitions) {
        this.tickets = tickets;
        this.history = history;
        this.effortLogs = effortLogs;
        this.transitions = transitions;
    }

    // ------------------------------------------------------------------
    // Appends
    // ------------------------------------------------------------------

    /**
     * Append one history entry.
     *
     * <p>{@code actorId} and {@code actorType} must agree: the schema's
     * {@code actor_id NULL} means SYSTEM, so a row naming nobody must say
     * {@code SYSTEM}, and a row that says {@code SYSTEM} must name nobody. An
     * unattributed entry that still claims a human actor is the one shape of
     * history that cannot be read back — it is in the audit log and there is no
     * way to find out who did it.
     *
     * @return the managed instance, its generated id populated
     * @throws AppendRejectedException if the entry contradicts itself, carries a
     *         hash, or names a ticket that does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketHistory append(TicketHistory entry) {
        if (entry == null) {
            throw new AppendRejectedException("a history entry is required");
        }
        rejectPresetIdentityOrHash(entry.getId(), entry.getPrevHash(), entry.getRowHash(), "history entry");
        requireCorrectionPair(entry.isCorrection(), entry.getCorrectsEntryId(), "history entry");

        require(isNotBlank(entry.getEventType()), "a history entry needs an event_type");
        boolean systemActor = SYSTEM_ACTOR.equals(entry.getActorType());
        require(systemActor == (entry.getActorId() == null),
                "actor_id and actor_type must agree: a history entry with no actor_id is SYSTEM, "
                        + "and a SYSTEM entry carries no actor_id (got actor_type="
                        + entry.getActorType() + ", actor_id=" + entry.getActorId() + ")");

        lockTicketFor(entry.getTicketId(), "history");
        requireCorrectsTheSameTicket(entry);
        chain(entry, previousRowHash(
                history.findFirstByTicketIdOrderByIdDesc(entry.getTicketId()),
                TicketHistory::getRowHash, entry.getTicketId(), "ticket_history"));
        return history.insert(entry);
    }

    /**
     * A-043 · retract one history entry.
     *
     * <p><b>A history correction annotates; it never hides.</b> Effort nets
     * because the §4A.4 grid sums it, and a negative row cancels arithmetically.
     * There is nothing to sum here, so the equivalent move would be for readers
     * to filter the corrected entry out — and that is exactly the capability
     * this table exists to withhold. Four layers stop a row being deleted; a
     * filter in every reader would hand the same effect back at the presentation
     * layer, below the triggers, the grants and the hash chain alike.
     *
     * <p>So both rows always render, and {@code is_correction} decides how the
     * retraction is <em>drawn</em>, never whether the original is returned. That
     * is a rule for Stream C's timeline and A-071's audit viewer, and it is
     * stated here because it is the opposite of the effort answer four lines up.
     *
     * <p>The descriptive columns are copied from the original so the row
     * explains itself in a query that has not joined back to the target. The
     * actor is <em>not</em> copied: {@code actorId} is whoever is retracting,
     * which is very often not whoever made the entry.
     *
     * @param historyEntryId the entry to retract
     * @param actorId        who is retracting it; {@code null} means SYSTEM
     * @param remarks        why — the whole value of the row
     * @throws AppendRejectedException if the entry does not exist, or has
     *         already been corrected
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketHistory reverseHistory(Long historyEntryId, Long actorId, String remarks) {
        require(historyEntryId != null, "a history entry id is required to correct one");
        require(isNotBlank(remarks),
                "a history correction needs a reason — it is the entire content of the row. "
                        + "The original is not removed and its values are copied here, so without "
                        + "a remark the correction says only that somebody disagreed.");
        TicketHistory original = history.findById(historyEntryId)
                .orElseThrow(() -> new AppendRejectedException(
                        "no history entry " + historyEntryId + " to correct"));

        TicketHistory correction = new TicketHistory();
        correction.setTicketId(original.getTicketId());
        correction.setCycleNo(original.getCycleNo());
        correction.setEventType(original.getEventType());
        correction.setFieldName(original.getFieldName());
        correction.setOldValue(original.getOldValue());
        correction.setNewValue(original.getNewValue());
        correction.setActorId(actorId);
        correction.setActorType(actorId == null ? SYSTEM_ACTOR : "USER");
        correction.setRemarks(remarks);
        correction.setCorrection(true);
        correction.setCorrectsEntryId(original.getId());
        return append(correction);
    }

    /**
     * Append one effort log.
     *
     * <p>The hours themselves are the database's business — {@code ck_effort_hours}
     * already rejects zero, more than 24, and a negative value outside a
     * correction row. What is checked here is the correction pair, which no
     * constraint can see: a compensating {@code -8} that points at nothing
     * cannot be reconciled against the entry it reverses, and the §4A.4 grid
     * then reports a total nobody can explain.
     *
     * @throws AppendRejectedException if the log contradicts itself, carries a
     *         hash, or names a ticket that does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketEffortLog append(TicketEffortLog entry) {
        if (entry == null) {
            throw new AppendRejectedException("an effort log is required");
        }
        rejectPresetIdentityOrHash(entry.getId(), entry.getPrevHash(), entry.getRowHash(), "effort log");
        requireCorrectionPair(entry.isCorrection(), entry.getCorrectsEntryId(), "effort log");

        lockTicketFor(entry.getTicketId(), "effort log");
        requireReversesItsOwnBucket(entry);
        chain(entry, previousRowHash(
                effortLogs.findFirstByTicketIdOrderByIdDesc(entry.getTicketId()),
                TicketEffortLog::getRowHash, entry.getTicketId(), "ticket_effort_logs"));
        return effortLogs.insert(entry);
    }

    /**
     * A-043 · reverse one effort log — the compensating entry, computed rather
     * than described.
     *
     * <p>The hours are <b>not</b> a parameter, and that is the point. A caller
     * able to write {@code reverse(9001, -5)} against an eight-hour entry
     * produces a row that satisfies every other rule here and still leaves three
     * hours standing in a cell nobody worked. The original is in hand under the
     * lock, so the only correct value is derivable and the wrong one is
     * unwriteable.
     *
     * <p>Everything that decides <em>where</em> the reversal lands is copied
     * from the original for the same reason — see
     * {@link #requireReversesItsOwnBucket}. {@code workDate} included: the
     * negative belongs on the day the work was claimed, or the daily effort
     * report shows hours removed from a day nobody logged any.
     *
     * <p><b>Restating the right value is a separate, ordinary append.</b> There
     * is deliberately no {@code correct(id, newHours)} that writes both rows: it
     * reads like {@code UPDATE hours = 6}, which is the mental model this whole
     * table is built to refuse, and the two rows it writes are not related by
     * anything the schema can express anyway.
     *
     * @param effortLogId the entry to reverse
     * @param note        why — carried on the compensating row, not the original
     * @return the compensating entry, its generated id populated
     * @throws AppendRejectedException if the entry does not exist, or has
     *         already been reversed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketEffortLog reverseEffort(Long effortLogId, String note) {
        require(effortLogId != null, "an effort log id is required to reverse one");
        TicketEffortLog original = effortLogs.findById(effortLogId)
                .orElseThrow(() -> new AppendRejectedException(
                        "no effort log " + effortLogId + " to reverse"));

        TicketEffortLog reversal = new TicketEffortLog();
        reversal.setTicketId(original.getTicketId());
        reversal.setCycleNo(original.getCycleNo());
        reversal.setStageCode(original.getStageCode());
        reversal.setIterationNo(original.getIterationNo());
        reversal.setUserId(original.getUserId());
        reversal.setWorkDate(original.getWorkDate());
        reversal.setHours(original.getHours().negate());
        reversal.setNote(note);
        reversal.setCorrection(true);
        reversal.setCorrectsEntryId(original.getId());
        return append(reversal);
    }

    /**
     * Append one hop of the ribbon.
     *
     * <p>A hop is always appended <b>open</b> — {@code exitedAt} and
     * {@code durationMins} null, {@code isCurrent} true — and closed later
     * through {@link #seal}. Two things follow, and both are enforced here
     * because nothing else can:
     *
     * <ul>
     *   <li><b>The previous hop must already be sealed.</b> A-009's
     *       {@code current_ticket_id} generated column is
     *       {@code IF(is_current = 1, ticket_id, NULL)} with a unique-ish read
     *       behind it, and {@code findByCurrentTicketId} returns an
     *       {@code Optional} — a second open hop turns "where is this ticket
     *       right now" into an exception at some unrelated later read, on a
     *       ticket detail page, rather than at the append that caused it. Seal
     *       the stage you are leaving, then append the one you are entering;
     *       that is also the order in which the two things actually happen.</li>
     *   <li><b>A backward move carries a reason.</b> The DDL comment on
     *       {@code reason} says "mandatory on any backward move, enforced in the
     *       service (§4A.6)" and this is that service. A rework loop with no
     *       reason is the one row the §4A.4 rework report cannot explain, and by
     *       the time anybody reads it the person who moved the ticket has
     *       forgotten.</li>
     * </ul>
     *
     * @throws AppendRejectedException if the hop is not open, leaves an earlier
     *         hop open, omits a required reason, carries a hash, or names a
     *         ticket that does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TicketStageTransition append(TicketStageTransition hop) {
        if (hop == null) {
            throw new AppendRejectedException("a stage transition is required");
        }
        rejectPresetIdentityOrHash(hop.getId(), hop.getPrevHash(), hop.getRowHash(), "stage transition");

        require(isNotBlank(hop.getToStage()), "a stage transition needs a to_stage");
        require(isNotBlank(hop.getActionCode()), "a stage transition needs an action_code");
        require(hop.getEnteredAt() != null, "a stage transition needs an entered_at");
        require(hop.getExitedAt() == null && hop.getDurationMins() == null && hop.isCurrent(),
                "a stage transition is appended open and closed by seal(): exited_at, duration_mins "
                        + "and is_current = false describe a hop that has already ended");
        require(!BACKWARD_ACTIONS.contains(hop.getActionCode()) || isNotBlank(hop.getReason()),
                "action_code " + hop.getActionCode() + " is a backward move and §4A.6 makes its reason "
                        + "mandatory");

        lockTicketFor(hop.getTicketId(), "stage transition");
        transitions.findByCurrentTicketId(hop.getTicketId()).ifPresent(open -> {
            throw new AppendRejectedException(
                    "ticket " + hop.getTicketId() + " is still in stage " + open.getToStage()
                            + " (transition " + open.getId() + " is unsealed). Seal it before appending "
                            + "the hop into " + hop.getToStage() + " — a ticket is in one stage at a time, "
                            + "and current_ticket_id assumes it.");
        });

        chain(hop, previousRowHash(
                transitions.findFirstByTicketIdOrderByIdDesc(hop.getTicketId()),
                TicketStageTransition::getRowHash, hop.getTicketId(), "ticket_stage_transitions"));
        return transitions.insert(hop);
    }

    // ------------------------------------------------------------------
    // The one permitted mutation
    // ------------------------------------------------------------------

    /**
     * Close an open hop — {@code exitedAt} NULL to a timestamp, with
     * {@code durationMins} recorded and {@code isCurrent} dropped. The A-008
     * trigger permits this and nothing else on the table.
     *
     * <p><b>Reading the transition before taking the lock is deliberate and not
     * a race.</b> The read exists only to learn which ticket to lock, and
     * {@code ticket_id} is one of the columns {@code trg_stage_seal_only}
     * forbids changing — so the answer cannot go stale between the read and the
     * lock. The seal itself runs afterwards, under the lock, and its own
     * {@code exited_at is null} predicate decides the outcome.
     *
     * <p>{@code durationMins} is <b>working</b> minutes from the B-024 calendar,
     * never wall-clock, and the journal cannot compute it — that needs the
     * project's holidays and the assignee's leave, which is Stream C's to
     * resolve at the point of handoff. What it can do is refuse a figure that is
     * arithmetically impossible: working time is a subset of elapsed time, so a
     * duration longer than the wall-clock gap between entry and exit means the
     * caller passed elapsed minutes, or seconds, or minutes from a different
     * pair of timestamps. That is the shape this mistake takes, and it is
     * otherwise invisible — a stage duration is a plausible number whatever it
     * says.
     *
     * @return {@code true} if this call sealed the hop, {@code false} if it was
     *         already sealed. A double-seal is a no-op rather than an error: a
     *         caller that can tell "I sealed it" from "someone else got there
     *         first" does not need an exception to do it.
     * @throws AppendRejectedException if the transition does not exist, or the
     *         exit time or duration cannot be right
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean seal(Long transitionId, Instant exitedAt, Integer durationMins) {
        require(transitionId != null, "a transition id is required");
        if (exitedAt == null) {
            throw new AppendRejectedException("a seal needs an exited_at");
        }

        TicketStageTransition hop = transitions.findById(transitionId)
                .orElseThrow(() -> new AppendRejectedException(
                        "no stage transition " + transitionId + " to seal"));

        lockTicketFor(hop.getTicketId(), "seal");

        require(!exitedAt.isBefore(hop.getEnteredAt()),
                "a stage cannot be exited at " + exitedAt + ", before it was entered at "
                        + hop.getEnteredAt());
        if (durationMins != null) {
            require(durationMins >= 0, "duration_mins cannot be negative");
            long wallClockMins = ceilingMinutes(Duration.between(hop.getEnteredAt(), exitedAt));
            require(durationMins <= wallClockMins,
                    "duration_mins " + durationMins + " exceeds the " + wallClockMins + " wall-clock "
                            + "minutes between entry and exit. Working minutes are a subset of elapsed "
                            + "minutes, so this is elapsed time, seconds, or a different pair of "
                            + "timestamps — see WorkingHoursService.workingHoursBetween (B-024).");
        }

        return transitions.seal(transitionId, exitedAt, durationMins) == 1;
    }

    // ------------------------------------------------------------------
    // Shared guards
    // ------------------------------------------------------------------

    /**
     * The lock, and the existence check that comes free with it.
     *
     * <p>An append naming a ticket that does not exist would otherwise fail on
     * the foreign key — correct, but as an opaque
     * {@code DataIntegrityViolationException} well after the point of the
     * mistake, and only once the transaction flushes, which may be several
     * appends later.
     */
    private void lockTicketFor(Long ticketId, String what) {
        require(ticketId != null, "a " + what + " needs a ticket_id — it is what the chain is keyed by");
        if (tickets.findByIdForUpdate(ticketId).isEmpty()) {
            throw new AppendRejectedException(
                    "no ticket " + ticketId + " to append a " + what + " to");
        }
    }

    /**
     * The tail of this ticket's chain in one table, read <b>after</b> the lock
     * and never before it.
     *
     * <p>That ordering is the whole of PLAN.md §3.7. Two appends that both read
     * the tail before either inserts produce two rows carrying the same
     * {@code prev_hash} — a fork, which A-044 reports as tampering months later,
     * when it was our own race. Every caller here reads it between
     * {@link #lockTicketFor} and {@code insert}, inside a transaction
     * {@code MANDATORY} guarantees is the caller's own.
     *
     * @return the predecessor's {@code row_hash}, or {@code null} if this row
     *         begins the chain
     */
    private <T> String previousRowHash(Optional<T> tail, Function<T, String> rowHash,
                                       Long ticketId, String table) {
        if (tail.isEmpty()) {
            return null;   // genesis — see ChainDigest
        }
        String previous = rowHash.apply(tail.get());
        if (previous == null) {
            throw new AppendRejectedException(
                    "the last " + table + " row for ticket " + ticketId + " carries no row_hash, so "
                            + "this append has nothing to chain onto. Appending anyway would write a "
                            + "second genesis row and silently fork the ticket's chain in two — the "
                            + "one outcome §3.7 exists to prevent — so it is refused here instead. "
                            + "The row predates A-042 or was written through @DirectAppend; rebuild "
                            + "the database volume and re-seed, which is what makes every row "
                            + "chained from the first.");
        }
        return previous;
    }

    /**
     * Stamp the link. Called under the lock, immediately before the insert, so
     * that the tail this hashes onto cannot have moved.
     *
     * <p>The {@code CanonicalJsonException} translation matters more than it
     * looks: it is raised for a payload with no canonical form, and in practice
     * that means an {@code Instant} carrying sub-microsecond precision.
     * {@code DATETIME(6)} cannot store one and MySQL <i>rounds</i> rather than
     * truncating, so a row hashed over {@code .1234565} would be stored as
     * {@code .123457} and fail verification for ever. A-041 refuses it rather
     * than truncating, precisely so the caller fixes it at the source; this only
     * re-labels the refusal as the append rejection it is, keeping the original
     * message, which names the fix.
     */
    private void chain(TicketHistory entry, String prevHash) {
        entry.setPrevHash(prevHash);
        entry.setRowHash(digest(prevHash, ChainPayloads.of(entry), "history entry"));
    }

    private void chain(TicketEffortLog entry, String prevHash) {
        entry.setPrevHash(prevHash);
        entry.setRowHash(digest(prevHash, ChainPayloads.of(entry), "effort log"));
    }

    private void chain(TicketStageTransition hop, String prevHash) {
        hop.setPrevHash(prevHash);
        hop.setRowHash(digest(prevHash, ChainPayloads.of(hop), "stage transition"));
    }

    private static String digest(String prevHash, Map<String, Object> payload, String what) {
        try {
            return ChainDigest.rowHash(prevHash, payload);
        } catch (CanonicalJsonException e) {
            throw new AppendRejectedException(
                    "this " + what + " cannot be hashed: " + e.getMessage(), e);
        }
    }

    /**
     * The chain columns and the identifier belong to the journal, not to the
     * caller.
     *
     * <p>{@code AppendOnlyImpl} uses {@code persist} rather than {@code merge}
     * precisely so a row carrying an id fails instead of quietly rewriting one
     * the chain has already committed to — this says the same thing one layer
     * earlier and with a message that names the mistake. The hashes are refused
     * for a different reason: A-042 computes them here, under the lock taken
     * three lines above, and a call site that had been allowed to supply its own
     * would then have to be found and unwound.
     */
    private void rejectPresetIdentityOrHash(Long id, String prevHash, String rowHash, String what) {
        if (id != null) {
            throw new AppendRejectedException(
                    "this " + what + " already carries id " + id + ". An append-only row is written "
                            + "once; a correction is a new row pointing at it, never a re-save.");
        }
        if (prevHash != null || rowHash != null) {
            throw new AppendRejectedException(
                    "prev_hash and row_hash are written by the journal under the per-ticket lock "
                            + "(A-042), not by the caller. A " + what + " arriving with either set has "
                            + "computed a chain link from a tail it read without the lock.");
        }
    }

    /**
     * The compensating-entry pattern is a pair, in both directions. A row
     * flagged as a correction that names no target cannot be reconciled against
     * anything; a row naming a target without the flag is invisible to every
     * query that filters on {@code is_correction}, so the same entry is counted
     * twice. A-043 builds the ergonomic API over this; the pair itself is a
     * storage invariant and holds from the first append.
     */
    /**
     * A-043 · a compensating effort entry has to land in the cell it cancels.
     *
     * <p>The §4A.4 roll-up joins effort to transitions on {@code ticket_id},
     * {@code stage_code} and {@code iteration_no}, filtered by {@code cycle_no}
     * (PLAN.md §3.4). A reversal that differs on any of those does not cancel
     * anything — the original stays counted where it was and the negative lands
     * somewhere else. <b>The grand total still reconciles</b>, which is what
     * makes this worth a guard: one cell over-reports, another under-reports,
     * and the number anybody would sanity-check is right.
     *
     * <p>{@code user_id} is in the set because the grid also rolls up per
     * resource. The hours were that person's, so the reversal has to reduce
     * <em>their</em> figure; an admin correcting somebody's entry under their
     * own id would leave the developer's hours untouched and award the admin a
     * negative eight. Effort logs carry no "who filed this" column — only whose
     * effort it is — so who performed a correction is a {@code ticket_history}
     * fact, which is why {@link #reverseHistory} takes an actor and this does
     * not.
     *
     * <p>The hours must be the exact negation for the same reason
     * {@link #reverseEffort} computes them: any other value leaves a residue in
     * a cell that reads as real work. Checked here rather than only there,
     * because a caller can build a correction by hand and pass it to
     * {@link #append(TicketEffortLog)} — the convenience method is where the
     * value is easy to get right, this is where it is impossible to get wrong.
     *
     * <p>Not checked here: that the target has not already been reversed. That
     * is {@code uq_effort_corrects}, and A-040's boundary holds — the database
     * can express it, so restating it would give two rules to keep in step.
     */
    private void requireReversesItsOwnBucket(TicketEffortLog entry) {
        if (entry.getCorrectsEntryId() == null) {
            return;
        }
        TicketEffortLog original = effortLogs.findById(entry.getCorrectsEntryId())
                .orElseThrow(() -> new AppendRejectedException(
                        "this correction reverses effort log " + entry.getCorrectsEntryId()
                                + ", which does not exist"));

        require(original.getTicketId().equals(entry.getTicketId()),
                "this correction is on ticket " + entry.getTicketId() + " but reverses an effort log "
                        + "on ticket " + original.getTicketId() + ". A reversal belongs to the same "
                        + "ticket as the entry it cancels — otherwise both tickets' roll-ups are "
                        + "wrong and both still add up.");
        require(original.getCycleNo() == entry.getCycleNo()
                        && Objects.equals(original.getStageCode(), entry.getStageCode())
                        && original.getIterationNo() == entry.getIterationNo()
                        && original.getUserId().equals(entry.getUserId()),
                "this correction does not land where the entry it reverses did — original was "
                        + "cycle " + original.getCycleNo() + " / " + original.getStageCode() + " / "
                        + "iteration " + original.getIterationNo() + " / user " + original.getUserId()
                        + ", correction is cycle " + entry.getCycleNo() + " / " + entry.getStageCode()
                        + " / iteration " + entry.getIterationNo() + " / user " + entry.getUserId()
                        + ". The §4A.4 grid joins on exactly those, so the original would stay "
                        + "counted and the negative would appear in a different cell.");
        require(original.getHours().add(entry.getHours()).signum() == 0,
                "a reversal of " + original.getHours() + " hours must be exactly "
                        + original.getHours().negate() + ", not " + entry.getHours()
                        + ". A partial reversal leaves a residue that reads as real work; to record "
                        + "a different figure, reverse in full and append the correct entry.");
    }

    /**
     * A-043 · a history correction points at an entry on its own ticket.
     *
     * <p>{@code fk_history_corrects} proves the target row exists and nothing
     * more — it references {@code ticket_history(id)}, so any row on any ticket
     * satisfies it. A cross-ticket retraction would put a note on one ticket's
     * timeline claiming an entry on another was wrong, and the entry it names
     * would render, unretracted, somewhere the reader is not looking.
     */
    private void requireCorrectsTheSameTicket(TicketHistory entry) {
        if (entry.getCorrectsEntryId() == null) {
            return;
        }
        TicketHistory original = history.findById(entry.getCorrectsEntryId())
                .orElseThrow(() -> new AppendRejectedException(
                        "this correction points at history entry " + entry.getCorrectsEntryId()
                                + ", which does not exist"));
        require(original.getTicketId().equals(entry.getTicketId()),
                "this correction is on ticket " + entry.getTicketId() + " but points at a history "
                        + "entry on ticket " + original.getTicketId() + ". A retraction belongs on "
                        + "the same timeline as the entry it retracts, or the entry stays "
                        + "unqualified where anybody would actually read it.");
    }

    private void requireCorrectionPair(boolean isCorrection, Long correctsEntryId, String what) {
        require(isCorrection == (correctsEntryId != null),
                "a corrected " + what + " sets both is_correction and corrects_entry_id or neither "
                        + "(got is_correction=" + isCorrection + ", corrects_entry_id=" + correctsEntryId
                        + "). The original is never touched — the correction points at it.");
    }

    /** Rounds the allowance up, so the check can only fire on a real mistake. */
    private static long ceilingMinutes(Duration elapsed) {
        return (elapsed.toSeconds() + 59) / 60;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AppendRejectedException(message);
        }
    }
}
