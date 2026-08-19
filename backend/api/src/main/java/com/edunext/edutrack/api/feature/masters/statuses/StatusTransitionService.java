package com.edunext.edutrack.api.feature.masters.statuses;

import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.masters.Status;
import com.edunext.edutrack.domain.masters.StatusRepository;
import com.edunext.edutrack.domain.masters.WorkflowTransition;
import com.edunext.edutrack.domain.masters.WorkflowTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B-039 · S-13 tab 1's second half — the allowed-transition matrix, per role.
 *
 * <h2>The table is a whitelist, and that is the whole design</h2>
 *
 * <p>A missing {@code (from, to, role)} row means the move is impossible for that
 * role. There is no second place to consult and no default. Which is why
 * governance decision G-3 (PLAN.md §5) — <em>may a Developer close a ticket?</em>
 * — is <b>data rather than code</b>: there is simply no
 * {@code (RESOLVED, CLOSED, DEVELOPER)} row, and never was.
 *
 * <p>B-003's seed header put it as "changing that policy is a seed edit, not a
 * deploy". This class makes it a screen edit, and <b>deliberately does not
 * hard-code G-3 as a refusal</b>. Writing that rule in here would put back into
 * code the one decision the table exists to keep out of it, and would mean an
 * organisation whose sign-off process differs from ours cannot express it without
 * a release. The S-13 grid flags governance-locked cells visually — advice an
 * Admin can read and overrule, which is not the same thing as a lock.
 *
 * <h2>Replace, not patch, and upsert rather than delete-and-reinsert</h2>
 *
 * <p>The matrix is authored and saved as a whole because a cell's meaning depends
 * on its neighbours: {@link #guardAtLeastOneOnCreate} cannot be checked against a
 * single cell, only against the set that will exist afterwards.
 *
 * <p>A row already present is <b>updated in place and keeps its id and
 * {@code createdAt}</b>; a row absent from the body is <b>deactivated, not
 * deleted</b>. That is B-017's and B-018's argument against replacing
 * {@code project_members} and {@code sla_policies} by delete, applied to a table
 * whose rows likewise carry facts of their own — {@code requiresReason} and
 * {@code requiresEffort} are decisions somebody made, and a cleared cell that
 * kept them is a cell that can be restored as it was rather than re-guessed.
 *
 * <h2>The one invariant</h2>
 *
 * <p><b>At least one {@code fromStatus: null} row must survive.</b> With none, no
 * role can raise a ticket on any screen — and the screen that could undo it is
 * this one. It is the only edit here that can lock the product out of itself, so
 * it is the only one refused unconditionally.
 *
 * <h2>The three refusals that are about the row itself</h2>
 *
 * <ul>
 *   <li><b>An unknown status or role code.</b> Not pedantry: the columns are
 *       plain {@code VARCHAR}s with no foreign keys, so a wrong code is not a
 *       constraint violation — it is a row that silently matches no caller ever.
 *       That is precisely the defect B-008 found, where thirteen seeded
 *       {@code SUPPORT_DESK} rows left the Support Desk unable to make any status
 *       move at all and nothing failed. The database will not catch this; this
 *       method is what does.</li>
 *   <li><b>{@code fromStatus == toStatus}.</b> A move that changes nothing, which
 *       {@code uq_workflow_transitions} would happily store.</li>
 *   <li><b>The same cell twice in one body.</b> The result would depend on
 *       iteration order, and the caller would see one of their two answers
 *       without being told which.</li>
 * </ul>
 *
 * <p>A transition naming a <em>retired</em> status is <b>not</b> refused. The
 * matrix is authored ahead of the vocabulary as often as behind it, and
 * {@code StatusService} deactivates the rows touching a status it retires — so
 * this is the route that puts them back, and refusing them here would make that
 * impossible.
 */
@Service
public class StatusTransitionService {

    private final WorkflowTransitionRepository transitions;
    private final StatusRepository statuses;
    private final RoleRepository roles;

    StatusTransitionService(WorkflowTransitionRepository transitions,
                            StatusRepository statuses,
                            RoleRepository roles) {
        this.transitions = transitions;
        this.statuses = statuses;
        this.roles = roles;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The whole matrix, or one role's column.
     *
     * <p><b>Retired rows are returned, carrying {@code isActive: false}.</b> Every
     * other read of this table is the engine asking "may this move happen?", where
     * a retired row and an absent row are the same answer. The grid asks a
     * different question: an Admin restoring a cell needs to see whether it was
     * cleared or never configured, because the first is a click and the second is
     * a decision.
     */
    @Transactional(readOnly = true)
    public List<StatusDtos.TransitionView> list(String roleCode) {
        List<WorkflowTransition> rows = roleCode == null || roleCode.isBlank()
                ? transitions.findAllByOrderByIdAsc()
                : transitions.findByRoleCodeOrderByIdAsc(
                        roleCode.trim().toUpperCase(Locale.ROOT));
        return rows.stream().map(StatusTransitionService::toView).toList();
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    /**
     * Replaces the matrix.
     *
     * <p>Every guard runs against the <em>whole body</em> before a single row is
     * written. Validating cell by cell as they are applied would leave a partly
     * saved matrix behind on the first bad cell — correct only because the
     * transaction rolls it back, which makes the guarantee depend on the rollback
     * rather than on never having written. The same call
     * {@code PriorityService.create} makes about its escalation flag.
     */
    @Transactional
    public List<StatusDtos.TransitionView> replace(StatusDtos.TransitionMatrixWrite write) {
        List<StatusDtos.TransitionWrite> wanted = write.transitions();

        Set<String> knownStatuses = statuses.findAll().stream()
                .map(Status::getCode).collect(Collectors.toSet());
        Set<String> knownRoles = roles.findAll().stream()
                .map(Role::getCode).collect(Collectors.toSet());

        Set<String> seen = new HashSet<>();
        List<Cell> cells = new ArrayList<>(wanted.size());

        for (StatusDtos.TransitionWrite row : wanted) {
            String from = normalise(row.fromStatus());
            String to = normalise(row.toStatus());
            String role = normalise(row.roleCode());

            if (from != null && !knownStatuses.contains(from)) {
                throw unknown("fromStatus", from, "status");
            }
            if (to == null || !knownStatuses.contains(to)) {
                throw unknown("toStatus", String.valueOf(to), "status");
            }
            if (role == null || !knownRoles.contains(role)) {
                throw unknown("roleCode", String.valueOf(role), "role");
            }
            if (to.equals(from)) {
                throw new InvalidTransitionException("toStatus",
                        "'" + from + "' cannot transition to itself. A move that changes nothing "
                                + "is not a permission, and the unique key would store it as one.");
            }
            if (!seen.add(key(from, to, role))) {
                throw new InvalidTransitionException("transitions",
                        "The move " + describe(from, to) + " for " + role + " appears twice. "
                                + "Which of the two sets of flags won would depend on iteration "
                                + "order, and you would not be told which you got.");
            }

            cells.add(new Cell(from, to, role,
                    Boolean.TRUE.equals(row.requiresReason()),
                    Boolean.TRUE.equals(row.requiresEffort())));
        }

        guardAtLeastOneOnCreate(cells);

        // Upsert. The lookup matches uq_workflow_transitions exactly, which is
        // what makes this an upsert rather than an insert that sometimes
        // violates a unique key.
        for (Cell cell : cells) {
            WorkflowTransition row = transitions
                    .findByFromStatusAndToStatusAndRoleCode(cell.from(), cell.to(), cell.role())
                    .orElseGet(() -> {
                        WorkflowTransition fresh = new WorkflowTransition();
                        fresh.setFromStatus(cell.from());
                        fresh.setToStatus(cell.to());
                        fresh.setRoleCode(cell.role());
                        return fresh;
                    });
            row.setRequiresReason(cell.requiresReason());
            row.setRequiresEffort(cell.requiresEffort());
            row.setActive(true);
            transitions.save(row);
        }

        // Anything the body did not name is deactivated rather than deleted —
        // see the class javadoc. Recomputed from the repository rather than from
        // `cells`, so a row inserted moments ago by another request is included.
        List<WorkflowTransition> retired = transitions.findAllByOrderByIdAsc().stream()
                .filter(WorkflowTransition::isActive)
                .filter(t -> !seen.contains(key(t.getFromStatus(), t.getToStatus(), t.getRoleCode())))
                .toList();
        retired.forEach(t -> t.setActive(false));
        transitions.saveAll(retired);

        return list(null);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * The only edit on this screen that can lock the product out of itself.
     *
     * <p>{@code fromStatus: null} is "on creation" — the only way a ticket enters
     * the system. Clear every one of those rows and no role can raise a ticket on
     * any screen, which also means nobody can reach the state where the mistake
     * shows up as anything other than a create form that refuses. The screen that
     * could put it back is this one, and it stays reachable, but the product in
     * between is not one anybody can use.
     *
     * <p>Checked against the incoming set rather than against the database,
     * because the question is what will exist afterwards.
     */
    private void guardAtLeastOneOnCreate(List<Cell> cells) {
        boolean any = cells.stream().anyMatch(c -> c.from() == null);
        if (!any) {
            throw new NoCreateTransitionException(
                    "At least one on-creation move must remain. Those are the rows with no "
                            + "'from' status, and they are the only way a ticket enters the "
                            + "system — with none of them, no role can raise a ticket on any "
                            + "screen. Every other cell can be cleared; this one cannot.");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Null-safe, and null is a real key here: it is the on-create row. */
    private static String key(String from, String to, String role) {
        return (from == null ? "" : from) + ' ' + to + ' ' + role;
    }

    private static String describe(String from, String to) {
        return (from == null ? "on creation" : from) + " -> " + to;
    }

    private static InvalidTransitionException unknown(String field, String value, String kind) {
        return new InvalidTransitionException(field,
                "'" + value + "' is not a " + kind + " code this system knows. "
                        + "workflow_transitions has no foreign key to either master, so a wrong "
                        + "code is not a constraint violation — it is a row that silently matches "
                        + "no caller, ever. That is exactly how thirteen seeded rows once left "
                        + "the Support Desk unable to make any status move at all, with nothing "
                        + "failing anywhere.");
    }

    private static StatusDtos.TransitionView toView(WorkflowTransition t) {
        return new StatusDtos.TransitionView(
                t.getId(), t.getFromStatus(), t.getToStatus(), t.getRoleCode(),
                t.isRequiresReason(), t.isRequiresEffort(), t.isActive());
    }

    private record Cell(String from, String to, String role,
                        boolean requiresReason, boolean requiresEffort) {
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    static class InvalidTransitionException extends RuntimeException {
        private final String field;

        InvalidTransitionException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    static class NoCreateTransitionException extends RuntimeException {
        NoCreateTransitionException(String message) {
            super(message);
        }
    }
}
