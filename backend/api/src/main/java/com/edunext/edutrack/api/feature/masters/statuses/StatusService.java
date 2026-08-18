package com.edunext.edutrack.api.feature.masters.statuses;

import com.edunext.edutrack.domain.masters.Status;
import com.edunext.edutrack.domain.masters.StatusRepository;
import com.edunext.edutrack.domain.masters.WorkflowTransition;
import com.edunext.edutrack.domain.masters.WorkflowTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-039 · S-13 tab 1 — the Status Master.
 *
 * <p>The data layer was already here — A-007 created {@code statuses} and B-003
 * seeded the eight with their §12.1 chip colours. B-039 adds {@code category},
 * which §7.4 asks for and nothing could answer, and is the first code anywhere to
 * serve either this table or {@code workflow_transitions}.
 *
 * <h2>Status is not stage, and S-13 has three tabs because of it</h2>
 *
 * <p>Blueprint §3 keeps them apart on purpose: a ticket can be {@code IN_PROGRESS}
 * while sitting in the {@code QA} stage. This class is status; {@code B-040}'s is
 * the ribbon. Collapsing the two is the modelling mistake §3 exists to prevent —
 * the moment status doubles as position, a ticket handed to QA can no longer be
 * described as blocked.
 *
 * <h2>Nothing served either table until now</h2>
 *
 * <p>Unlike B-021's priorities — which had been in the contract, in the MSW mock
 * and in the generated client since D-001 with no controller — {@code statuses}
 * and {@code workflow_transitions} were not even <em>declared</em>. Two seeded
 * masters, eighty-two rows, reachable only by a migration. That is why this task
 * writes the contract as well as the server, and why no shipped screen breaks on
 * first contact: there was no screen.
 *
 * <h2>There is no delete, and here the stake is the highest of the three masters</h2>
 *
 * <p>{@code tickets.status} holds the <em>code</em> in a {@code VARCHAR},
 * deliberately not a foreign key. So a delete would <b>succeed</b>, exactly as it
 * would on {@code priorities} — but a level is decoration on a ticket that still
 * works, while a status is the left-hand side of every transition lookup. Deleting
 * one strands every ticket in it with no move offered on any screen. Retiring is
 * {@code isActive: false}, and even that refuses while tickets are still there.
 *
 * <h2>The four refusals</h2>
 *
 * <table>
 *   <tr><th>Rule</th><th>Where</th><th>Status</th></tr>
 *   <tr><td>A ninth status code</td><td>{@link #normaliseCode}</td><td>400 {@code validation}</td></tr>
 *   <tr><td>{@code code} is immutable once created</td><td>{@link #update}</td><td>409 {@code immutable-field}</td></tr>
 *   <tr><td>{@code code} and {@code name} are unique</td><td>{@link #create}/{@link #update}</td><td>409 {@code duplicate}</td></tr>
 *   <tr><td>Terminal and open at once</td><td>{@link #guardTerminalAndOpen}</td><td>409 {@code contradictory-state}</td></tr>
 *   <tr><td>Retiring a status tickets are in</td><td>{@link #guardRetire}</td><td>409 {@code in-use}</td></tr>
 * </table>
 *
 * <h2>Retiring is not local, and that is the whole risk in this task</h2>
 *
 * <p>The gate Stream C consults is
 * {@code WorkflowTransitionRepository.existsByFromStatusAndToStatusAndRoleCodeAndIsActiveTrue}
 * — it reads the <em>transition</em> row's {@code is_active} and never looks at
 * the status at all. So retiring {@code ON_HOLD} without touching the matrix
 * leaves {@code IN_PROGRESS → ON_HOLD} live: the master says the status is gone
 * and the engine goes on moving tickets into it. Nothing fails; the two simply
 * disagree, and the disagreement is discovered on a ticket page weeks later.
 *
 * <p>Closing it in Stream C's gate would have been the smaller diff and is not
 * this stream's file to change. Closing it here costs one extra write on a rare
 * operation and keeps the fix inside the screen that causes the problem:
 * {@link #retireTransitionsTouching} deactivates both ends in the same
 * transaction, and the count travels back on the response so the dialog can state
 * it before the click rather than after.
 *
 * <h2>What reactivating does not do</h2>
 *
 * <p>It does not bring the transitions back. They are data an Admin authored, and
 * a restore would have to guess which of them were deactivated <em>by this
 * retire</em> versus cleared deliberately at some point in between. Guessing
 * wrong grants a move nobody approved — on a whitelist, that is the one direction
 * an error must not go. The matrix tab is where they come back, explicitly.
 */
@Service
public class StatusService {

    /**
     * The eight the contract's {@code StatusCode} enum declares.
     *
     * <p>Mirrors {@code PriorityService.CONTRACT_LEVELS} exactly, and exists for
     * exactly its reason: a ninth code stored here serialises into a response the
     * generated client's own zod rejects, and Stream C's status chips key off
     * {@code Record<StatusCode, …>} maps a ninth key would leave undefined.
     */
    static final Set<String> CONTRACT_CODES = Set.of(
            "NEW", "IN_PROGRESS", "ON_HOLD", "AWAITING_INFO",
            "REWORK", "RESOLVED", "CLOSED", "REOPENED");

    private static final short SEQ_STEP = 10;

    private final StatusRepository statuses;
    private final WorkflowTransitionRepository transitions;
    private final StatusUsageRepository usage;

    StatusService(StatusRepository statuses,
                  WorkflowTransitionRepository transitions,
                  StatusUsageRepository usage) {
        this.statuses = statuses;
        this.transitions = transitions;
        this.usage = usage;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Active statuses by default; {@code includeInactive} for the S-13 grid.
     *
     * <p>The narrow default follows {@code PriorityService.list} rather than
     * {@code TaskTypeService}'s, and the argument is the same: nothing downstream
     * filters this list, so a retired row handed to a ticket screen's status
     * filter offers a value matching no ticket anybody can still create.
     *
     * <p><b>Ordered by {@code seq}, not by category.</b> The category is a
     * grouping the screen applies; {@code seq} is the lifecycle order an Admin
     * arranged and the order the ticket screens' filters render. Sorting here by
     * category would make the two disagree about what follows what, and one of
     * them would be wrong.
     */
    @Transactional(readOnly = true)
    public List<StatusDtos.StatusView> list(boolean includeInactive) {
        StatusUsageRepository.Counts counts = usage.all();
        List<Status> rows = includeInactive
                ? statuses.findAllByOrderBySeqAscIdAsc()
                : statuses.findByIsActiveTrueOrderBySeqAsc();
        return rows.stream().map(s -> toView(s, counts.of(s.getCode()))).toList();
    }

    @Transactional(readOnly = true)
    public Optional<StatusDtos.StatusView> find(int statusId) {
        return statuses.findById(statusId).map(this::toView);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public StatusDtos.StatusView create(StatusDtos.StatusWrite write) {
        String code = normaliseCode(write.code());
        if (statuses.existsByCode(code)) {
            throw new DuplicateStatusException("code", "A status with code '" + code
                    + "' already exists. To bring back a retired one, reactivate it instead.");
        }

        String name = write.name().trim();
        statuses.findByNameIgnoreCase(name).ifPresent(clash -> {
            throw new DuplicateStatusException("name", "'" + clash.getName()
                    + "' already exists. Two statuses with the same name are indistinguishable "
                    + "in the ticket grid, in every status filter and on the board.");
        });

        boolean open = write.isOpen() == null || write.isOpen();
        boolean terminal = Boolean.TRUE.equals(write.isTerminal());
        guardTerminalAndOpen(terminal, open);

        Status status = new Status();
        status.setCode(code);
        status.setName(name);
        status.setCategory(write.category().trim().toUpperCase(Locale.ROOT));
        status.setColour(write.colour().trim());
        status.setSeq(write.seq() != null ? toSeq(write.seq()) : nextSeq());
        status.setOpen(open);
        status.setTerminal(terminal);
        status.setActive(write.isActive() == null || write.isActive());

        Status saved = statuses.save(status);

        // A status nothing has been raised into yet and no transition names, so
        // both counts are known without asking — and asking would be two
        // statements for numbers that cannot be anything else.
        return toView(saved, new StatusUsageRepository.Counts.Row(0L, 0));
    }

    /**
     * Edit, or retire.
     *
     * <p><b>The end state is derived once, before anything is written</b> — the
     * ordering bug {@code PriorityService.update} documents, in its second form.
     * {@code {"isActive": false, "isTerminal": true, "isOpen": true}} is a single
     * request whose guards disagree if each reads the entity's <em>stored</em>
     * state: the contradiction check sees the old booleans and the retire check
     * sees a status nothing has moved into yet. Deriving {@code willBe*} up front
     * is what closes it, rather than ordering three writes carefully and hoping
     * nobody reorders them later.
     */
    @Transactional
    public Optional<StatusDtos.StatusView> update(int statusId, StatusDtos.StatusPatch patch) {
        Optional<Status> found = statuses.findById(statusId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Status status = found.get();

        if (patch.code() != null
                && !patch.code().trim().toUpperCase(Locale.ROOT).equals(status.getCode())) {
            throw new ImmutableStatusCodeException(status.getCode());
        }

        boolean willBeActive = patch.isActive() != null ? patch.isActive() : status.isActive();
        boolean willBeOpen = patch.isOpen() != null ? patch.isOpen() : status.isOpen();
        boolean willBeTerminal =
                patch.isTerminal() != null ? patch.isTerminal() : status.isTerminal();

        guardTerminalAndOpen(willBeTerminal, willBeOpen);

        boolean retiring = status.isActive() && !willBeActive;
        if (retiring) {
            guardRetire(status);
        }

        if (patch.name() != null) {
            String name = patch.name().trim();
            statuses.findByNameIgnoreCase(name)
                    .filter(clash -> !clash.getId().equals(status.getId()))
                    .ifPresent(clash -> {
                        throw new DuplicateStatusException("name", "'" + clash.getName()
                                + "' already exists. Two statuses with the same name are "
                                + "indistinguishable in the ticket grid, in every status filter "
                                + "and on the board.");
                    });
            status.setName(name);
        }
        if (patch.category() != null) {
            status.setCategory(patch.category().trim().toUpperCase(Locale.ROOT));
        }
        if (patch.colour() != null) {
            status.setColour(patch.colour().trim());
        }
        if (patch.seq() != null) {
            status.setSeq(toSeq(patch.seq()));
        }
        status.setOpen(willBeOpen);
        status.setTerminal(willBeTerminal);
        status.setActive(willBeActive);

        Status saved = statuses.save(status);

        // The cascade runs after the row is written, not before: if any guard
        // above throws, the transaction rolls back and no transition has been
        // touched. Ordering it the other way would leave the deactivation to be
        // undone by a rollback rather than never having happened — correct, but
        // it makes the guarantee depend on the rollback.
        Integer deactivated = retiring ? retireTransitionsTouching(saved.getCode()) : null;

        return Optional.of(withDeactivated(toView(saved), deactivated));
    }

    // ------------------------------------------------------------------
    // The rules the schema does not encode
    // ------------------------------------------------------------------

    /**
     * A ninth status is refused, and the message names what has to change.
     *
     * <p>Identical in shape and reasoning to
     * {@code PriorityService.normaliseLevel}: the contract's enum types responses
     * the generated TypeScript client validates with zod, so a code outside the
     * eight is not a row somebody can add — it is a ticket list that breaks on
     * read because of what was saved on a master screen. Refused here rather than
     * accepted and discovered later as a rendering failure.
     */
    private String normaliseCode(String raw) {
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (!CONTRACT_CODES.contains(code)) {
            throw new StatusValidationException("code",
                    "'" + code + "' is not one of the eight statuses this release supports ("
                            + String.join(", ", sortedContractCodes()) + "). The contract's "
                            + "StatusCode enum types tickets.status on every response, so a ninth "
                            + "code would be rejected by the generated client's own validation "
                            + "before any screen rendered it. Opening the set is a coordinated "
                            + "change across contracts/openapi.yaml (Stream D), the ticket "
                            + "screens' status chips (Stream C) and the summary tables (Stream A) "
                            + "— not one this screen can make alone.");
        }
        return code;
    }

    /**
     * Terminal and open at once is a contradiction, and the only one of the three
     * flags' combinations that is.
     *
     * <p>{@code isTerminal} means only a reopen moves a ticket on; {@code isOpen}
     * means the dashboard counts it as outstanding work. A status claiming both
     * would be counted in "open tickets" forever by a figure nobody can drive to
     * zero — the seeded {@code CLOSED} carries {@code isOpen = 0, isTerminal = 1}
     * for exactly this reason.
     *
     * <p><b>Category is deliberately not part of this check.</b> The obvious
     * fourth rule — "DONE implies not open" — is wrong, and {@code RESOLVED} is
     * the counter-example sitting in the seed: {@code DONE} work on a ticket that
     * stays open until sign-off. Enforcing it would refuse the row the blueprint
     * asks for. The category describes the work; {@code isOpen} describes the
     * ticket record, and the gap between them is why the column exists.
     */
    private void guardTerminalAndOpen(boolean terminal, boolean open) {
        if (terminal && open) {
            throw new ContradictoryStatusException(
                    "A status cannot be both terminal and open. Terminal means only a reopen "
                            + "moves a ticket on; open means the dashboard counts it as "
                            + "outstanding. Together they would put every ticket that reached "
                            + "this status into an open count nobody can drive to zero.");
        }
    }

    /**
     * A retire refused because tickets are still in this status.
     *
     * <p><b>The mirror of {@code PriorityService.guardRetire}, and it blocks on
     * the count that one deliberately ignores.</b> A ticket at a retired
     * <em>level</em> keeps rendering it and nothing else changes — that is the
     * entire point of {@code tickets.level} being a {@code VARCHAR}. A ticket in a
     * retired <em>status</em> is stranded: this retire deactivates every
     * transition out of the status, so no screen offers a move, and the only
     * screen that could repair it is a different one.
     *
     * <p>CLAUDE.md's rule, stated on the priority master and applying with more
     * force here: one screen must not be able to put another into a state it
     * cannot get out of.
     */
    private void guardRetire(Status status) {
        StatusUsageRepository.Counts.Row counts = usage.forCode(status.getCode());
        if (counts.tickets() > 0) {
            throw new StatusInUseException(counts.tickets(),
                    counts.tickets() + " ticket" + (counts.tickets() == 1 ? " is" : "s are")
                            + " currently in '" + status.getName() + "'. Retiring it deactivates "
                            + "every transition out of it, which would leave "
                            + (counts.tickets() == 1 ? "that ticket" : "those tickets")
                            + " with no move offered on any screen. Move them to another status "
                            + "first.");
        }
    }

    /**
     * Deactivates every active transition naming this status on either side.
     *
     * <p>Both ends, not just the outgoing ones. Leaving the incoming rows live
     * would let a ticket be moved <em>into</em> a status the master says is gone —
     * which is the same disagreement the whole cascade exists to prevent, just
     * pointing the other way.
     *
     * <p>Read-modify-write over eighty-odd rows rather than a bulk
     * {@code @Modifying} update: the count returned has to be the number actually
     * changed rather than the number matched, and a JPQL update's return value
     * counts rows the {@code WHERE} touched including ones already inactive.
     */
    private int retireTransitionsTouching(String code) {
        List<WorkflowTransition> affected = transitions.findAllByOrderByIdAsc().stream()
                .filter(WorkflowTransition::isActive)
                .filter(t -> code.equals(t.getFromStatus()) || code.equals(t.getToStatus()))
                .toList();
        affected.forEach(t -> t.setActive(false));
        transitions.saveAll(affected);
        return affected.size();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<String> sortedContractCodes() {
        return CONTRACT_CODES.stream().sorted().toList();
    }

    private short nextSeq() {
        short max = statuses.findAllByOrderBySeqAscIdAsc().stream()
                .map(Status::getSeq)
                .reduce((short) 0, (a, b) -> (short) Math.max(a, b));
        return (short) (max + SEQ_STEP);
    }

    private static short toSeq(int seq) {
        return (short) Math.max(0, Math.min(Short.MAX_VALUE, seq));
    }

    private StatusDtos.StatusView toView(Status status) {
        return toView(status, usage.forCode(status.getCode()));
    }

    private static StatusDtos.StatusView toView(Status status,
                                                StatusUsageRepository.Counts.Row counts) {
        return new StatusDtos.StatusView(
                status.getId(),
                status.getCode(),
                status.getName(),
                status.getCategory(),
                status.getColour(),
                status.getSeq(),
                status.isOpen(),
                status.isTerminal(),
                status.isActive(),
                counts.tickets(),
                counts.transitions(),
                null);
    }

    private static StatusDtos.StatusView withDeactivated(StatusDtos.StatusView view,
                                                         Integer deactivated) {
        if (deactivated == null) {
            return view;
        }
        return new StatusDtos.StatusView(view.id(), view.code(), view.name(), view.category(),
                view.colour(), view.seq(), view.isOpen(), view.isTerminal(), view.isActive(),
                view.ticketCount(), view.transitionCount(), deactivated);
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    static class DuplicateStatusException extends RuntimeException {
        private final String field;

        DuplicateStatusException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    static class ImmutableStatusCodeException extends RuntimeException {
        ImmutableStatusCodeException(String code) {
            super("A status code cannot be changed once created. This one is '" + code
                    + "'. tickets.status stores the code and is not a foreign key, so a rename "
                    + "would not cascade — it would orphan every ticket ever raised in this "
                    + "status. Retire it and create the replacement instead.");
        }
    }

    static class ContradictoryStatusException extends RuntimeException {
        ContradictoryStatusException(String message) {
            super(message);
        }
    }

    static class StatusInUseException extends RuntimeException {
        private final long ticketCount;

        StatusInUseException(long ticketCount, String message) {
            super(message);
            this.ticketCount = ticketCount;
        }

        long ticketCount() {
            return ticketCount;
        }
    }

    static class StatusValidationException extends RuntimeException {
        private final String field;

        StatusValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
