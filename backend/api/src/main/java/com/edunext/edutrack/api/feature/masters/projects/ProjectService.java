package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-016 · S-10, the Project Master.
 *
 * <h2>The rules that live here and not in the schema</h2>
 *
 * <ul>
 *   <li><b>{@code projectCode} is immutable once the project has issued a ticket
 *       ID.</b> The prefix of every code already quoted in mail, chat and client
 *       conversations. 409.</li>
 *   <li><b>{@code projectCode} is unique.</b> Two projects sharing one would
 *       issue colliding ticket IDs. 409, keyed on the field.</li>
 *   <li><b>A project manager is required and must be an active resource.</b>
 *       400, keyed on the field.</li>
 *   <li><b>A target end date may not precede the start date.</b> 400.</li>
 * </ul>
 *
 * <p>Only the first two are backed by anything in the database — and only the
 * uniqueness one, by {@code uq_projects_code}. Immutability is enforced nowhere
 * but here, which {@code domain.identity.Project}'s javadoc has said since B-005:
 * "The Project Master rejects the edit; nothing in the schema can."
 *
 * <h2>There is no delete</h2>
 *
 * S-10 does not ask for one and the table cannot support one. A project that has
 * issued ticket IDs cannot be removed without orphaning every one of them, and
 * {@code tickets.project_id} is a foreign key with no cascade, so the database
 * would refuse anyway — as a constraint violation naming an index rather than as
 * a way forward. {@code CLOSED} is the retirement path, the way deactivation is
 * for a resource.
 */
@Service
class ProjectService {

    static final String ACTIVE = "ACTIVE";
    static final String ON_HOLD = "ON_HOLD";
    static final String CLOSED = "CLOSED";

    /**
     * Blueprint S-10's three, and {@code ck_projects_status}' three.
     *
     * <p>A constant rather than an enum because the column is a
     * {@code VARCHAR(20)} and the contract is a string enum; a Java enum here
     * would add a third statement of the same list and put a
     * {@code valueOf} failure — {@code IllegalArgumentException}, a 500 — on the
     * deserialisation path for a value that deserves a 400.
     */
    private static final Set<String> STATUSES = Set.of(ACTIVE, ON_HOLD, CLOSED);

    static final String MANUAL = "MANUAL";

    private static final Set<String> AUTO_ASSIGN_RULES = Set.of("ROUND_ROBIN", "LEAST_LOADED", MANUAL);

    private final ProjectMasterRepository repository;

    ProjectService(ProjectMasterRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * {@code isActive} is {@code status <> 'CLOSED'} — <b>not</b>
     * {@code status = 'ACTIVE'}, which is the reading the name invites.
     *
     * <p>Five screens already send {@code ?isActive=true} and all five use it to
     * fill a picker: the project switcher, the ticket list's filter, the
     * create-ticket form, the resource grid and the resource form. Deriving it
     * the obvious way would mean that putting a project on hold silently removed
     * it from the create-ticket picker, with nothing on the form saying why —
     * and the support desk would be unable to file against it and unable to find
     * out from the screen.
     *
     * <p>Whether On Hold <i>should</i> stop new tickets is a real product
     * question and possibly a yes. It is not a question a derived boolean should
     * answer on its way past: the answer belongs on Stream C's create form,
     * where it can carry a message. <b>Flagged for Stream C</b> rather than
     * decided here.
     */
    static boolean isActive(String status) {
        return !CLOSED.equals(status);
    }

    List<ProjectDtos.Project> page(ProjectMasterRepository.ProjectFilter filter, ProjectCursor cursor, int limit) {
        return repository.page(filter, cursor, limit);
    }

    Optional<ProjectDtos.ProjectDetail> find(long projectId) {
        return repository.findDetail(projectId);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Create — the only operation that may set {@code projectCode} freely.
     *
     * <p>Upper-cased before anything else looks at it, so the uniqueness check,
     * the stored value and the eventual ticket prefix are one string. The column
     * collates case-insensitively, so {@code crm} would collide with {@code CRM}
     * at the index regardless; upper-casing means it collides at the check
     * instead, which is the one that produces a usable message.
     */
    @Transactional
    ProjectDtos.ProjectDetail create(ProjectDtos.ProjectWrite write) {
        String code = normaliseCode(write.projectCode());
        requireCodeAvailable(code, -1L);

        String status = validStatus(write.status(), ACTIVE);
        String rule = validAutoAssignRule(write.autoAssignRule(), MANUAL);
        validateDates(write.startDate(), write.endDate());
        long managerId = validateManager(write.projectManagerId());

        long id = repository.insert(new ProjectMasterRepository.ProjectRow(
                code,
                write.name().trim(),
                blankToNull(write.description()),
                blankToNull(write.clientName()),
                managerId,
                write.startDate(),
                write.endDate(),
                status,
                blankToNull(write.colourTag()),
                rule));

        return repository.findDetail(id).orElseThrow(
                () -> new IllegalStateException("project " + id + " vanished between insert and read"));
    }

    /**
     * Edit. An omitted field keeps its stored value.
     *
     * <p>Read-modify-write inside one transaction, and the read is the same
     * {@code findDetail} the controller used to build the {@code ETag}. That is
     * what makes the {@code If-Match} check meaningful: the controller compares
     * tags against a state, and this re-reads that state under the transaction
     * before deciding what the patch means.
     *
     * @return empty when there is no such project — the controller turns that
     *         into a 404, never a 403 (CONVENTIONS.md §7)
     */
    @Transactional
    Optional<ProjectDtos.ProjectDetail> update(long projectId, ProjectDtos.ProjectPatch patch) {
        Optional<ProjectDtos.ProjectDetail> found = repository.findDetail(projectId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProjectDtos.ProjectDetail current = found.get();

        String code = resolveCode(current, patch.projectCode());
        String status = validStatus(patch.status(), current.status());
        String rule = validAutoAssignRule(patch.autoAssignRule(), current.autoAssignRule());

        LocalDate startDate = patch.startDate() != null ? patch.startDate() : current.startDate();
        LocalDate endDate = patch.endDate() != null ? patch.endDate() : current.endDate();
        validateDates(startDate, endDate);

        // An if/else, and not the obvious ternary. `cond ? long : Long` applies
        // binary numeric promotion and unboxes the null branch, so a project
        // with no manager NPEs on an edit that never mentioned one — widening
        // the target to `Long` does not help, because the promotion happens
        // inside the conditional. Caught by
        // `ProjectServiceTest.aManagerlessProjectIsStillEditable`.
        Long managerId = currentManagerId(current);
        if (patch.projectManagerId() != null) {
            managerId = validateManager(patch.projectManagerId());
        }

        repository.update(projectId, new ProjectMasterRepository.ProjectRow(
                code,
                patch.name() != null ? patch.name().trim() : current.name(),
                patch.description() != null ? blankToNull(patch.description()) : current.description(),
                patch.clientName() != null ? blankToNull(patch.clientName()) : current.clientName(),
                managerId,
                startDate,
                endDate,
                status,
                patch.colourTag() != null ? blankToNull(patch.colourTag()) : current.colourTag(),
                rule));

        return repository.findDetail(projectId);
    }

    // ------------------------------------------------------------------
    // The four rules
    // ------------------------------------------------------------------

    /**
     * The immutability rule, and the whole of it.
     *
     * <p><b>The test is {@code ticketsIssued > 0}, not "a ticket row exists".</b>
     * {@code projects.ticket_seq} counts codes that have been <i>issued</i>; a
     * ticket created and later hard-deleted still had {@code CRM-26-00347} sent
     * to a client, quoted in a mail thread and written into chat. Counting live
     * ticket rows instead would let a cleanup quietly re-open the prefix for
     * editing, and the orphaning would happen later and look like something
     * else. It is also on the row already, so this costs no join.
     *
     * <p><b>Sending the same code back is always fine, and has to be.</b> S-10
     * submits the whole form on every save, so any other reading would make every
     * edit to a live project a 409 — the mirror of the {@code u.id <> ?} that
     * B-013 documents on the resource form's uniqueness check.
     */
    private String resolveCode(ProjectDtos.ProjectDetail current, String requested) {
        if (requested == null) {
            return current.projectCode();
        }
        String code = normaliseCode(requested);
        if (code.equals(current.projectCode())) {
            return code;
        }
        if (current.ticketsIssued() > 0) {
            throw new ImmutableCodeException(current.projectCode(), current.ticketsIssued());
        }
        requireCodeAvailable(code, current.id());
        return code;
    }

    private void requireCodeAvailable(String code, long excludingId) {
        if (repository.codeTaken(code, excludingId)) {
            throw new DuplicateCodeException(code);
        }
    }

    /**
     * A project manager is required, and must be somebody who can be reached.
     *
     * <p><b>The role is not checked</b>, deliberately. Restricting this to
     * {@code PM} and {@code ADMIN} is the obvious rule and the wrong one twice
     * over: an organisation running a support-led project has a legitimate
     * reason to name its Support Desk lead, and a hardcoded role set is exactly
     * what B-015 removed from {@code ResourceController} — the first custom role
     * an Admin creates would be refused here by a screen that had just granted
     * it every capability. The rule that matters is that the person exists and
     * is active, because this is who Stream D's SLA scanners escalate to.
     *
     * <p>Inactive is refused rather than accepted: a deactivated resource is
     * somebody who has left, and naming them means every escalation on the
     * project goes to a mailbox nobody reads.
     */
    private long validateManager(Long projectManagerId) {
        if (projectManagerId == null) {
            throw new ProjectValidationException("projectManagerId", "a project manager is required");
        }
        ProjectMasterRepository.ManagerCandidate manager = repository.findManager(projectManagerId)
                .orElseThrow(() -> new ProjectValidationException(
                        "projectManagerId", "no such resource"));

        if (!manager.isActive()) {
            throw new ProjectValidationException("projectManagerId",
                    manager.displayName() + " is deactivated and cannot be a project manager");
        }
        return manager.id();
    }

    /**
     * A row that predates the mandatory-manager rule keeps its null rather than
     * being refused on an unrelated edit.
     *
     * <p>The alternative — demanding a manager on every save — would make a
     * project with no manager uneditable until somebody supplied one, including
     * for the edit that was going to supply it, since S-10 sends the whole form
     * and a form loaded from a null renders an empty picker.
     */
    private static Long currentManagerId(ProjectDtos.ProjectDetail current) {
        return current.projectManager() == null ? null : current.projectManager().id();
    }

    /** Two fields, so Bean Validation cannot say it. */
    private static void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ProjectValidationException("endDate",
                    "the target end date cannot be before the start date");
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code Locale.ROOT}, not the default locale.
     *
     * <p>A server running under a Turkish locale upper-cases {@code i} to
     * {@code İ} (U+0130), which is not in {@code ^[A-Z][A-Z0-9]{1,9}$} and is not
     * what anybody typed. A ticket prefix is an identifier, not prose.
     */
    private static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String validStatus(String requested, String fallback) {
        if (requested == null) {
            return fallback;
        }
        String status = requested.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ProjectValidationException("status",
                    "status must be one of ACTIVE, ON_HOLD, CLOSED");
        }
        return status;
    }

    private static String validAutoAssignRule(String requested, String fallback) {
        if (requested == null) {
            return fallback == null ? MANUAL : fallback;
        }
        String rule = requested.trim().toUpperCase(Locale.ROOT);
        if (!AUTO_ASSIGN_RULES.contains(rule)) {
            throw new ProjectValidationException("autoAssignRule",
                    "autoAssignRule must be one of ROUND_ROBIN, LEAST_LOADED, MANUAL");
        }
        return rule;
    }

    /**
     * An empty string clears a nullable column rather than storing {@code ""}.
     *
     * <p>A cleared text input posts {@code ""}, and storing that makes "no
     * description" two values that every reader has to handle. The one field
     * where this would be wrong is {@code name}, which is {@code @NotBlank} and
     * never reaches here.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /** 400, keyed on the field, so the message lands on the input. */
    static class ProjectValidationException extends RuntimeException {

        private final String field;

        ProjectValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return this.field;
        }
    }

    /** 409 — the request is well formed and would have been accepted a moment earlier. */
    static class DuplicateCodeException extends RuntimeException {

        static final String FIELD = "projectCode";

        DuplicateCodeException(String code) {
            super(code + " is already the prefix of another project");
        }
    }

    /**
     * 409, and a distinct {@code type} from the duplicate.
     *
     * <p>The two are both 409 and are nothing alike: one is fixed by choosing a
     * different code, the other cannot be fixed at all. A client that has to
     * read the prose to tell them apart is reading the part of the document that
     * is allowed to change without notice — B-012's reasoning for splitting
     * {@code manager-cycle} from {@code duplicate}.
     */
    static class ImmutableCodeException extends RuntimeException {

        static final String FIELD = "projectCode";

        private final long ticketsIssued;

        ImmutableCodeException(String currentCode, long ticketsIssued) {
            super("this project has issued " + ticketsIssued + " ticket "
                    + (ticketsIssued == 1 ? "ID" : "IDs") + " under " + currentCode
                    + ", so its code can no longer change");
            this.ticketsIssued = ticketsIssued;
        }

        long ticketsIssued() {
            return this.ticketsIssued;
        }
    }
}
