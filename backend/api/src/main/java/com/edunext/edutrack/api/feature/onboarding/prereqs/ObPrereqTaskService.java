package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.journal.ObPrereqJournal;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTaskRepository;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.domain.onboarding.ObPrereqHistory;
import com.edunext.edutrack.domain.onboarding.ObPrereqSubmittedVia;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * B-125 · the prerequisite task lifecycle — plan §5.3's
 * {@code PENDING → SUBMITTED → VERIFIED}, its return loop, and its one valve.
 *
 * <h2>Four transitions, each with its own refusals</h2>
 *
 * <p>There is no {@code setStatus}. Every move is a named method with its own
 * required reason and its own preconditions, exactly as
 * {@code ObJourneyStepLifecycleService} models step transitions and for the
 * same reason: one of these moves opens the gate for every journey the client
 * has, and a status field would be a second way to make it with none of the
 * rules.
 *
 * <ul>
 *   <li>{@link #submit} — {@code PENDING → SUBMITTED}. <b>The one route both
 *       principals reach.</b> Plan §4 records {@code submitted_via} precisely
 *       because a SPOC frequently emails a document to their implementor;
 *       without a staff path the implementor would have to leave it
 *       outstanding or log in as the client, and the second is how shared
 *       credentials start.</li>
 *   <li>{@link #verify} — {@code SUBMITTED → VERIFIED}, staff only. A client
 *       cannot verify their own work, which is the entire point of the state
 *       existing. Verifying straight from {@code PENDING} is refused rather
 *       than allowed as a shortcut: {@code submittedAt} is what plan §5.4
 *       attributes client-side waiting time with, and a task that skipped it
 *       has no defensible clock.</li>
 *   <li>{@link #returnToClient} — {@code SUBMITTED → PENDING}, with a
 *       mandatory comment. <b>The clock is not reset</b>: prerequisite time
 *       is the client's, and a return that restarted it would let an
 *       incomplete submission buy an extension.</li>
 *   <li>{@link #skip} — {@code → SKIPPED}, reason mandatory, non-mandatory
 *       tasks only. See below.</li>
 * </ul>
 *
 * <h2>The valve is deliberately narrow</h2>
 *
 * <p>Plan §5.3 leaves exactly one way to move a gate a client cannot clear.
 * <b>Mandatory tasks cannot be skipped</b> — 422, not a permission the right
 * role unlocks — because a skippable mandatory task is not a mandatory task,
 * and the gate would be a convention rather than a guarantee. The database
 * says so too, at {@code ck_ob_client_prereq_tasks_mandatory_not_skipped}.
 *
 * <h2>Every transition writes the chain, and the gate is evaluated after each</h2>
 *
 * <p>{@link ObPrereqJournal} is the only door to {@code ob_prereq_history},
 * and it takes the per-client lock. {@link ObPrereqGate#evaluate} then runs in
 * the same transaction — plan §5.3 evaluates the gate on <em>every</em>
 * prerequisite transition, not only on verification, because a skip can clear
 * the last outstanding task just as a verification can.
 */
@Service
public class ObPrereqTaskService {

    private final ObClientPrereqTaskRepository tasks;
    private final ObClientPrereqsRepository headers;
    private final ObPrereqThreadRepository thread;
    private final ObPrereqJournal journal;
    private final ObPrereqGate gate;
    private final ObClientPrereqService clientPrereqs;

    public ObPrereqTaskService(ObClientPrereqTaskRepository tasks,
                               ObClientPrereqsRepository headers,
                               ObPrereqThreadRepository thread,
                               ObPrereqJournal journal,
                               ObPrereqGate gate,
                               ObClientPrereqService clientPrereqs) {
        this.tasks = tasks;
        this.headers = headers;
        this.thread = thread;
        this.journal = journal;
        this.gate = gate;
        this.clientPrereqs = clientPrereqs;
    }

    // ── reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ObClientPrereqTask require(long prereqTaskId) {
        return tasks.findById(prereqTaskId)
                .orElseThrow(() -> new PrereqTaskNotFoundException(prereqTaskId));
    }

    // ── the ad-hoc addition ──────────────────────────────────────────────

    /**
     * Plan §4's "ad-hoc per-client tasks" — something this client's situation
     * needs that the master does not ask everybody for. It does not go back
     * into the master; this instance is the only place it exists.
     *
     * <p>Appended at the end of the checklist. Its clock starts now, not at
     * boarding: the client is being asked for it now.
     *
     * <p><b>The caller is expected to have checked the client's prerequisite
     * {@code ETag} first</b> — an ad-hoc task can be mandatory, and adding a
     * mandatory task re-locks a gate that may have just opened. That
     * precondition is the controller's, because it is about a representation
     * the controller served.
     */
    @Transactional
    public ObClientPrereqTask addAdHoc(long obClientId, String title, String description,
                                       int tatDays, boolean mandatory, long actorUserId) {
        ObClientPrereqs header = headers.findByObClientId(obClientId)
                .orElseThrow(() -> new ClientPrereqsNotFoundException(obClientId));

        ObClientPrereqTask task = new ObClientPrereqTask();
        task.setHeaderId(header.getId());
        task.setObClientId(obClientId);
        task.setTemplateTaskId(null);
        task.setAdHoc(true);
        task.setSequence(nextSequence(header.getId()));
        task.setTitle(title);
        task.setDescription(description);
        task.setTatDays(tatDays);
        task.setMandatory(mandatory);
        task.setDueAt(clientPrereqs.dueAt(Instant.now(), tatDays));
        ObClientPrereqTask saved = tasks.save(task);

        // The chain's first entry for this task. `fromStatus` is null —
        // creation has nothing to come from, which is the same shape the
        // contract declares for an instantiated task's first entry.
        appendHistory(saved, null, ObPrereqTaskStatus.PENDING,
                ObPrereqActorType.STAFF, actorUserId, null, null);
        return saved;
    }

    // ── the edit ─────────────────────────────────────────────────────────

    /**
     * Staff edit of wording or TAT. {@code status} and {@code isMandatory}
     * are deliberately not editable here — the contract says why at length,
     * and the short version is that flipping either is a change to whether
     * the gate can open, which belongs to the routes that own that
     * consequence.
     *
     * <p>{@code dueAt} is recomputed against the working calendar when
     * {@code tatDays} moves, from the task's original creation rather than
     * from now: extending a TAT should move the deadline by the difference,
     * not hand the client a fresh full budget starting today.
     *
     * @throws PrereqTaskSettledException the task is {@code VERIFIED} or
     *         {@code SKIPPED} — a settled task is not reworded, because the
     *         client agreed to what it said.
     */
    @Transactional
    public ObClientPrereqTask update(long prereqTaskId, String title, String description,
                                     Integer tatDays) {
        ObClientPrereqTask task = require(prereqTaskId);
        if (task.getStatus().isSettled()) {
            throw new PrereqTaskSettledException(prereqTaskId, task.getStatus());
        }
        if (title != null) {
            task.setTitle(title);
        }
        if (description != null) {
            task.setDescription(description);
        }
        if (tatDays != null && tatDays != task.getTatDays()) {
            task.setTatDays(tatDays);
            task.setDueAt(clientPrereqs.dueAt(task.getCreatedAt(), tatDays));
        }
        return tasks.save(task);
    }

    // ── the four transitions ─────────────────────────────────────────────

    /**
     * {@code PENDING → SUBMITTED}. Re-submitting a returned task is the
     * normal loop and is allowed — a returned task is {@code PENDING} again.
     *
     * @param contactId the client contact submitting through the portal, or
     *                  null when staff are recording a submission that
     *                  arrived by email
     * @param userId    the staff member recording it, or null on the portal
     *                  path
     */
    @Transactional
    public ObClientPrereqTask submit(long prereqTaskId, Long userId, Long contactId, String note) {
        ObClientPrereqTask task = require(prereqTaskId);
        if (task.getStatus() != ObPrereqTaskStatus.PENDING) {
            throw new PrereqTransitionException(prereqTaskId, task.getStatus(),
                    "submitted", "ob-prereq-not-submittable");
        }

        boolean viaPortal = contactId != null;
        task.setStatus(ObPrereqTaskStatus.SUBMITTED);
        task.setSubmittedAt(Instant.now());
        task.setSubmittedVia(viaPortal ? ObPrereqSubmittedVia.PORTAL : ObPrereqSubmittedVia.STAFF);
        task.setSubmittedByContact(viaPortal ? contactId : null);
        task.setSubmittedByUser(viaPortal ? null : userId);
        tasks.save(task);

        appendHistory(task, ObPrereqTaskStatus.PENDING, ObPrereqTaskStatus.SUBMITTED,
                viaPortal ? ObPrereqActorType.CLIENT : ObPrereqActorType.STAFF,
                viaPortal ? null : userId, viaPortal ? contactId : null, note);
        return task;
    }

    /**
     * {@code SUBMITTED → VERIFIED}, and the transition that starts the
     * module. The gate is evaluated in this transaction — see
     * {@link ObPrereqGate}.
     */
    @Transactional
    public Settled verify(long prereqTaskId, long userId, String note) {
        ObClientPrereqTask task = require(prereqTaskId);
        if (task.getStatus() != ObPrereqTaskStatus.SUBMITTED) {
            throw new PrereqTransitionException(prereqTaskId, task.getStatus(),
                    "verified", "ob-prereq-not-verifiable");
        }

        task.setStatus(ObPrereqTaskStatus.VERIFIED);
        task.setVerifiedAt(Instant.now());
        task.setVerifiedBy(userId);
        tasks.save(task);

        appendHistory(task, ObPrereqTaskStatus.SUBMITTED, ObPrereqTaskStatus.VERIFIED,
                ObPrereqActorType.STAFF, userId, null, note);
        return settle(task);
    }

    /**
     * {@code SUBMITTED → PENDING}, with the mandatory comment written to the
     * task's thread as well as into the chain.
     *
     * <p>Both, deliberately: the thread is where the client reads it and the
     * chain is what makes it defensible later. The comment is marked
     * {@code isSystem} so CP-04 renders it as an event — a client should not
     * appear to have been answered by a string the server composed.
     */
    @Transactional
    public ObClientPrereqTask returnToClient(long prereqTaskId, long userId, String comment) {
        ObClientPrereqTask task = require(prereqTaskId);
        if (task.getStatus() != ObPrereqTaskStatus.SUBMITTED) {
            throw new PrereqTransitionException(prereqTaskId, task.getStatus(),
                    "returned", "ob-prereq-not-returnable");
        }

        task.setStatus(ObPrereqTaskStatus.PENDING);
        // The submission stamps are cleared: the task is outstanding again,
        // and a `submittedAt` on a PENDING row would tell the scanner and
        // every report that the client had already answered.
        //
        // **`dueAt` is untouched.** Plan §5.4 attributes this time to the
        // client, and a return that reset the clock would let an incomplete
        // submission buy an extension.
        task.setSubmittedAt(null);
        task.setSubmittedVia(null);
        task.setSubmittedByUser(null);
        task.setSubmittedByContact(null);
        tasks.save(task);

        addComment(prereqTaskId, ObPrereqActorType.STAFF, userId, null, comment, true);
        appendHistory(task, ObPrereqTaskStatus.SUBMITTED, ObPrereqTaskStatus.PENDING,
                ObPrereqActorType.STAFF, userId, null, comment);
        return task;
    }

    /**
     * The gate's only valve — {@code → SKIPPED}, reason mandatory,
     * non-mandatory tasks only.
     *
     * <p>The role check is the caller's: the contract answers 403 rather than
     * 404 here, because the caller has already read this task and denying its
     * existence would refuse a row they are looking at.
     *
     * @throws MandatoryTaskNotSkippableException the task is mandatory. 422,
     *         not a permission the right role unlocks.
     */
    @Transactional
    public Settled skip(long prereqTaskId, long userId, String reason) {
        ObClientPrereqTask task = require(prereqTaskId);
        if (task.isMandatory()) {
            throw new MandatoryTaskNotSkippableException(prereqTaskId);
        }
        if (task.getStatus().isSettled()) {
            throw new PrereqTransitionException(prereqTaskId, task.getStatus(),
                    "skipped", "ob-prereq-not-skippable");
        }

        ObPrereqTaskStatus from = task.getStatus();
        task.setStatus(ObPrereqTaskStatus.SKIPPED);
        task.setSkippedAt(Instant.now());
        task.setSkippedBy(userId);
        task.setSkipReason(reason);
        tasks.save(task);

        appendHistory(task, from, ObPrereqTaskStatus.SKIPPED,
                ObPrereqActorType.STAFF, userId, null, reason);
        return settle(task);
    }

    // ── comments ─────────────────────────────────────────────────────────

    /**
     * Both principals. The author comes from the token and is never in the
     * body — a client who could name their own author id could write as a
     * member of staff.
     */
    @Transactional
    public long addComment(long prereqTaskId, ObPrereqActorType authorType,
                           Long userId, Long contactId, String body, boolean system) {

        return thread.insertComment(prereqTaskId, authorType,
                authorType == ObPrereqActorType.STAFF ? userId : null,
                authorType == ObPrereqActorType.CLIENT ? contactId : null,
                body, system);
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    /**
     * What a verification or a skip answers: the task as it now stands, plus
     * what that did to the gate.
     */
    public record Settled(ObClientPrereqTask task,
                          ObPrereqGate.Outcome gate,
                          ObClientPrereqService.Progress progress) {
    }

    private Settled settle(ObClientPrereqTask task) {
        // Re-read the whole set rather than mutating a cached copy: the gate
        // condition is over every task the client has, and evaluating it
        // against a stale list is how a gate opens one task early.
        List<ObClientPrereqTask> all = tasks.findByObClientIdOrderBySequenceAsc(task.getObClientId());
        ObPrereqGate.Outcome outcome = gate.evaluate(task.getObClientId(), all);
        return new Settled(task, outcome, ObClientPrereqService.Progress.of(all));
    }

    private void appendHistory(ObClientPrereqTask task, ObPrereqTaskStatus from,
                               ObPrereqTaskStatus to, ObPrereqActorType actorType,
                               Long userId, Long contactId, String reason) {

        ObPrereqHistory entry = new ObPrereqHistory();
        entry.setObClientId(task.getObClientId());
        entry.setPrereqTaskId(task.getId());
        entry.setOccurredAt(Instant.now());
        entry.setActorType(actorType);
        entry.setActorUserId(actorType == ObPrereqActorType.STAFF ? userId : null);
        entry.setActorContactId(actorType == ObPrereqActorType.CLIENT ? contactId : null);
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setReason(reason);
        journal.append(entry);
    }

    private int nextSequence(Long headerId) {
        return tasks.findTopByHeaderIdOrderBySequenceDesc(headerId)
                .map(t -> t.getSequence() + 1)
                .orElse(1);
    }
}
