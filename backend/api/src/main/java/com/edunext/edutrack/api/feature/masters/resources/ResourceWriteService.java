package com.edunext.edutrack.api.feature.masters.resources;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * B-011 · creating and editing a resource (S-08).
 *
 * <p>Separate from {@link ResourceService}, whose class comment says it
 * deliberately holds no writes beyond one boolean. That is still true: this
 * class holds the rest, and the split is the same one the repositories make —
 * a read model that a five-thousand-row export streams through, and a write
 * path that touches one row at a time and has to reason about uniqueness,
 * memberships and open tickets.
 *
 * <h2>What this validates, and what it leaves to the database</h2>
 *
 * <p>Uniqueness is checked here <b>and</b> enforced by
 * {@code uq_users_username}, {@code uq_users_email} and
 * {@code uq_users_emp_code}. The check is not redundant: the index can only
 * refuse the whole statement, and the form needs to know <i>which</i> of the
 * three fields to highlight and would like to be told about all three at once.
 * The index remains the thing that is actually true — two admins submitting the
 * same username in the same millisecond both pass the check, and one of them
 * gets a constraint violation, which {@link ResourceExceptionHandler} turns
 * back into the same 409 the check would have produced.
 *
 * <h2>Reporting-manager cycles</h2>
 *
 * <p><b>Refused at any depth (B-012).</b> {@link #validateManager} walks up
 * {@code reporting_manager_id} from the proposed manager and refuses if the
 * edited resource is anywhere on the chain — A→B→C→A is as broken as A→A, and
 * {@code trg_users_no_self_manager_ins/upd} only ever caught the latter. The
 * trigger stays as the backstop for anything that writes the column without
 * coming through here.
 */
@Service
public class ResourceWriteService {

    /**
     * Matches the {@code users.timezone} column default and
     * {@code working_calendar.timezone}.
     *
     * <p>Not {@code ZoneId.systemDefault()}: a resource created on a CI runner
     * in UTC would then get a different display zone from one created on a
     * developer's laptop, for no reason the data records.
     */
    static final String DEFAULT_TIMEZONE = "Asia/Kolkata";

    /** S-08's stated default. */
    static final BigDecimal DEFAULT_CAPACITY_HRS = new BigDecimal("8.00");

    /**
     * B-012 · how far up the reporting line the cycle check walks.
     *
     * <p>Sixty-four is not a limit on how deep an organisation may be — nothing
     * refuses a 64-level hierarchy that already exists, and no read walks this
     * far. It is the point past which "this chain has no end" is a better
     * explanation than "this organisation is very tall": a 64-deep management
     * line in a company of the size this product is built for is not a shape
     * anybody meant.
     *
     * <p>Well under MySQL's {@code cte_max_recursion_depth} (1,000), so the
     * recursion stops at a bound this code chose rather than at one the server
     * enforces by failing the statement.
     */
    static final int MAX_MANAGER_CHAIN = 64;

    private final ResourceWriteRepository writes;
    private final ResourceRepository reads;
    private final PasswordEncoder passwordEncoder;

    ResourceWriteService(ResourceWriteRepository writes,
                         ResourceRepository reads,
                         PasswordEncoder passwordEncoder) {
        this.writes = writes;
        this.reads = reads;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** @throws ResourceNotFoundException if there is no such resource */
    @Transactional(readOnly = true)
    public ResourceDtos.ResourceDetail detail(long userId) {
        int openTickets = reads.openTicketCounts(List.of(userId)).getOrDefault(userId, 0);
        return writes.findDetail(userId, openTickets)
                .orElseThrow(() -> new ResourceNotFoundException(userId));
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    /**
     * Creates a resource and returns it alongside the one-time password.
     *
     * <p>The password is generated, hashed and then held only in the return
     * value. It is never logged and never written anywhere but the Argon2id
     * column, which is why an admin who loses it has to issue a reset rather
     * than look it up.
     */
    @Transactional
    public Created create(ResourceDtos.ResourceWriteRequest request) {
        String username = trimmed(request.getUsername());
        String email = trimmed(request.getEmail());
        String empCode = trimmed(request.getEmployeeCode());

        int roleId = resolveRole(request.getRole());
        rejectConflicts(writes.findConflicts(username, email, empCode, null));

        Long managerId = value(request.getReportingManagerId());
        validateManager(managerId, null);

        // `value`, not `.orElse` — an absent `projects` key leaves the field
        // null, and calling orElse on it is an NPE on the ordinary case of a
        // resource created before their projects are known.
        List<ResourceDtos.ProjectAssignment> assignments =
                normaliseAssignments(value(request.getProjects()));
        validateProjects(assignments);

        String temporaryPassword = TemporaryPasswords.generate();

        long id = writes.insert(new ResourceWriteRepository.NewResource(
                empCode,
                username,
                email,
                passwordEncoder.encode(temporaryPassword),
                trimmed(request.getDisplayName()),
                trimmedOrNull(value(request.getMobile())),
                roleId,
                managerId,
                trimmedOrNull(value(request.getDepartment())),
                trimmedOrNull(value(request.getDesignation())),
                trimmedOrNull(value(request.getLocation())),
                orDefault(request.getDailyCapacityHrs(), DEFAULT_CAPACITY_HRS),
                orDefault(request.getTimezone(), DEFAULT_TIMEZONE),
                // A resource created inactive is one nobody can be assigned
                // work; permitted, because bulk onboarding ahead of a start
                // date is a real thing an admin does.
                orDefault(request.getIsActive(), Boolean.TRUE),
                // S-08's "force change on first login", and the only value this
                // is ever set to on create. It is not a request field: an admin
                // who could clear it would be creating an account whose password
                // somebody other than its owner knows and will keep.
                true,
                value(request.getDateOfJoining()),
                trimmedOrNull(value(request.getAvatarUrl())),
                normaliseWeeklyOff(value(request.getWeeklyOff())),
                normaliseSkills(value(request.getSkills()))));

        if (!assignments.isEmpty()) {
            writes.replaceMemberships(id, assignments);
        }

        return new Created(reload(id), temporaryPassword);
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    /**
     * Applies a partial edit.
     *
     * <p>Only the keys the caller sent are written — see
     * {@link ResourceWriteRepository#update} for why a fixed statement listing
     * every column would reintroduce the lost update {@code If-Match} exists to
     * prevent.
     *
     * @param current the state the caller's {@code If-Match} was checked
     *                against, so the deactivation guard and the self-reference
     *                guard reason about the same row the precondition did
     */
    @Transactional
    public ResourceDtos.ResourceDetail update(ResourceDtos.ResourceDetail current,
                                              ResourceDtos.ResourceWriteRequest request) {
        long userId = current.id();

        String username = trimmed(request.getUsername());
        String email = trimmed(request.getEmail());
        String empCode = trimmed(request.getEmployeeCode());
        rejectConflicts(writes.findConflicts(username, email, empCode, userId));

        LinkedHashMap<String, Object> changes = new LinkedHashMap<>();
        changes.put("full_name", trimmed(request.getDisplayName()));
        changes.put("username", username);
        changes.put("email", email);
        changes.put("emp_code", empCode);
        changes.put("role_id", resolveRole(request.getRole()));

        // ── nullable columns · absent leaves alone, explicit null clears ──
        ifSent(request.getMobile(), changes, "mobile", ResourceWriteService::trimmedOrNull);
        ifSent(request.getAvatarUrl(), changes, "avatar_url", ResourceWriteService::trimmedOrNull);
        ifSent(request.getDateOfJoining(), changes, "date_of_joining", day -> day);
        ifSent(request.getDepartment(), changes, "department", ResourceWriteService::trimmedOrNull);
        ifSent(request.getDesignation(), changes, "designation", ResourceWriteService::trimmedOrNull);
        ifSent(request.getLocation(), changes, "location", ResourceWriteService::trimmedOrNull);

        if (request.getReportingManagerId() != null) {
            Long managerId = request.getReportingManagerId().orElse(null);
            validateManager(managerId, userId);
            changes.put("reporting_manager_id", managerId);
        }

        // ── NOT NULL columns · absent leaves alone, and there is no clear ──
        // An explicit null on either of these is already a 400 from the
        // @NotNull inside the Optional, so an empty Optional cannot reach here.
        ifSent(request.getTimezone(), changes, "timezone", ResourceWriteService::trimmed);
        ifSent(request.getDailyCapacityHrs(), changes, "daily_capacity_hrs", hrs -> hrs);

        // ── JSON columns · null is a value, not an absence ────────────────
        // `weeklyOff: null` means "inherit the org working week" and has to
        // reach the column; only an absent key leaves it alone.
        ifSent(request.getWeeklyOff(), changes, "weekly_off",
                days -> writes.writeJson(normaliseWeeklyOff(days)));
        ifSent(request.getSkills(), changes, "skills",
                skills -> writes.writeJson(normaliseSkills(skills)));

        if (request.getIsActive() != null && request.getIsActive().isPresent()) {
            boolean target = request.getIsActive().get();
            guardDeactivation(current, target);
            changes.put("is_active", target);
        }

        writes.update(userId, changes);

        if (request.getProjects() != null) {
            List<ResourceDtos.ProjectAssignment> assignments =
                    normaliseAssignments(request.getProjects().orElse(null));
            validateProjects(assignments);
            writes.replaceMemberships(userId, assignments);
        }

        return reload(userId);
    }

    /**
     * The form must not be a way round the status route's guard.
     *
     * <p>{@code PATCH /users/{id}/status} refuses to deactivate somebody holding
     * open tickets, and so does the bulk route. If this one did not, "edit the
     * resource and untick Active" would orphan exactly the live work those two
     * exist to protect — and it is the more discoverable path of the three.
     *
     * <p>Only deactivation is guarded, and only when the flag actually moves:
     * saving an already-inactive resource's other fields must not be refused
     * because they still hold tickets from before they were deactivated, which
     * is what a half-finished reassignment leaves behind. Same ordering
     * {@code ResourceService.apply} uses, for the same reason.
     */
    private void guardDeactivation(ResourceDtos.ResourceDetail current, boolean target) {
        if (target || !Boolean.TRUE.equals(current.isActive())) {
            return;
        }
        int openTickets = current.openTicketCount() == null ? 0 : current.openTicketCount();
        if (openTickets > 0) {
            throw new OpenTicketsException(current.id(), openTickets);
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private int resolveRole(String roleCode) {
        String code = roleCode.trim().toUpperCase(Locale.ROOT);
        return writes.findRoleId(code).orElseThrow(() -> new ResourceValidationException(
                "role", "no role with code " + code));
    }

    private void rejectConflicts(ResourceWriteRepository.Conflicts conflicts) {
        if (conflicts.any()) {
            throw new DuplicateResourceException(conflicts.asFieldErrors());
        }
    }

    /**
     * B-012 · refuses a manager who reports, at any remove, back to the edited
     * resource.
     *
     * <p>Three refusals, in the order they are cheapest to make. Self-reference
     * needs no query at all. Then one walk up the chain answers the other two:
     * an empty chain is a manager who does not exist, and a chain containing the
     * edited resource is the cycle.
     *
     * <p><b>A chain that reached the cap is refused as well</b>, and that is not
     * a defensive nicety. This service is the only thing that refuses cycles, so
     * the rows it reads may already contain one — from before B-012, or from a
     * hand-run {@code UPDATE} the trigger let through because it was not
     * self-reference. On such data the walk cannot distinguish "not a cycle"
     * from "a cycle I stopped short of", and reading a truncated chain as a pass
     * would let the one case this task exists to prevent through on exactly the
     * data where it is already real. Attaching somebody to a reporting line with
     * no root is refused; repairing the line is not this screen's job.
     *
     * <p>Run on create too. A row with no id yet cannot appear on anybody's
     * chain, so the cycle check is vacuous — but the existence check is not, and
     * neither is the cap: a new hire attached to an already-broken subtree
     * inherits the break.
     *
     * @param editedUserId the resource being edited, or null on a create
     */
    private void validateManager(Long managerId, Long editedUserId) {
        if (managerId == null) {
            return;
        }
        if (editedUserId != null && managerId.equals(editedUserId)) {
            throw new ManagerCycleException("a resource cannot be their own reporting manager");
        }

        List<Long> chain = writes.managerChain(managerId, MAX_MANAGER_CHAIN);
        if (chain.isEmpty()) {
            throw new ResourceValidationException(
                    "reportingManagerId", "no resource with id " + managerId);
        }
        if (editedUserId != null && chain.contains(editedUserId)) {
            // The distance is worth naming: at one level the admin can see the
            // problem on the screen they are on, and at four they cannot, which
            // is the case this task exists for.
            throw new ManagerCycleException(
                    "that would create a reporting cycle — they already report to this "
                            + "resource, " + chain.indexOf(editedUserId) + " level(s) up");
        }
        if (chain.size() >= MAX_MANAGER_CHAIN) {
            throw new ManagerCycleException(
                    "that resource's reporting line is more than " + MAX_MANAGER_CHAIN
                            + " levels deep and does not end, which means it already "
                            + "contains a cycle. Fix the line above them first.");
        }
    }

    private void validateProjects(List<ResourceDtos.ProjectAssignment> assignments) {
        if (assignments.isEmpty()) {
            return;
        }
        Set<Long> requested = new LinkedHashSet<>(assignments.stream()
                .map(ResourceDtos.ProjectAssignment::projectId).toList());
        Set<Long> found = writes.existingProjectIds(requested);

        List<Long> missing = requested.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new ResourceValidationException("projects", "no project with id " + missing);
        }
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    /**
     * De-duplicates by project, last assignment winning.
     *
     * <p>A form that sends the same project twice with two different roles is
     * asking an incoherent question, and both alternatives to answering it are
     * worse than picking one: a 400 makes the admin fix something the UI should
     * not have let them express, and letting both through gives
     * {@code ON DUPLICATE KEY UPDATE} a race with itself in statement order.
     */
    private static List<ResourceDtos.ProjectAssignment> normaliseAssignments(
            List<ResourceDtos.ProjectAssignment> assignments) {

        if (assignments == null) {
            return List.of();
        }
        Map<Long, ResourceDtos.ProjectAssignment> byProject = new LinkedHashMap<>();
        for (ResourceDtos.ProjectAssignment assignment : assignments) {
            String role = assignment.roleInProject() == null ? null
                    : assignment.roleInProject().trim().toUpperCase(Locale.ROOT);
            byProject.put(assignment.projectId(),
                    new ResourceDtos.ProjectAssignment(assignment.projectId(), role));
        }
        return List.copyOf(byProject.values());
    }

    /**
     * Sorted and de-duplicated.
     *
     * <p>{@code [7, 6]} and {@code [6, 7]} are the same working week, and
     * storing them differently would make the {@code ETag} of an unchanged
     * resource depend on the order a dropdown happened to emit — a 412 for an
     * edit that conflicts with nothing.
     */
    private static List<Integer> normaliseWeeklyOff(List<Integer> days) {
        if (days == null) {
            return null;
        }
        return days.stream().distinct().sorted().toList();
    }

    /** Trimmed, blank-free and de-duplicated; order is the admin's and is kept. */
    private static List<String> normaliseSkills(List<String> skills) {
        if (skills == null) {
            return null;
        }
        return List.copyOf(new LinkedHashSet<>(skills.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList()));
    }

    private ResourceDtos.ResourceDetail reload(long userId) {
        return writes.findDetail(userId, reads.openTicketCounts(List.of(userId)).getOrDefault(userId, 0))
                .orElseThrow(() -> new IllegalStateException(
                        "resource " + userId + " vanished inside its own transaction"));
    }

    // ------------------------------------------------------------------
    // Optional helpers
    // ------------------------------------------------------------------

    /**
     * The value of a key that was sent, or null for one that was not.
     *
     * <p>{@code null} field means absent; {@code Optional.empty()} means an
     * explicit JSON null. On a create the two collapse — there is nothing to
     * leave alone — so this flattens both to null.
     */
    private static <T> T value(Optional<T> field) {
        return field == null ? null : field.orElse(null);
    }

    private static <T> T orDefault(Optional<T> field, T fallback) {
        T sent = value(field);
        return sent == null ? fallback : sent;
    }

    /**
     * Stages one column, but only if the caller actually sent its key.
     *
     * <p>The three states in one place. Absent ({@code field == null}) adds
     * nothing, so {@link ResourceWriteRepository#update} never names the column
     * and the row keeps whatever is in it. Explicit null stages a null, which
     * clears it. A value is passed through {@code mapper} first — trimming for
     * text, JSON serialisation for the array columns.
     *
     * <p>Written as one helper rather than a null check per field because
     * fourteen hand-written pairs of them is fourteen chances to write
     * {@code ifPresent} on a field that can be null, which is an NPE on the
     * common case of a form that did not send an optional key.
     */
    private static <T> void ifSent(Optional<T> field,
                                   LinkedHashMap<String, Object> changes,
                                   String column,
                                   Function<? super T, ?> mapper) {
        if (field == null) {
            return;
        }
        changes.put(column, field.map(mapper).orElse(null));
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /** Trimmed, with an all-whitespace value becoming null rather than {@code ""}. */
    private static String trimmedOrNull(String value) {
        String trimmed = trimmed(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    /** A created resource and the password that will never be readable again. */
    public record Created(ResourceDtos.ResourceDetail resource, String temporaryPassword) {
    }

    /** 404 — or out of scope, once A-034 lands. CONVENTIONS.md §7. */
    public static class ResourceNotFoundException extends NoSuchElementException {

        @Serial
        private static final long serialVersionUID = 1L;

        ResourceNotFoundException(long userId) {
            super("no resource with id " + userId);
        }
    }

    /** 400, field-keyed so the form can put the message against the input. */
    public static class ResourceValidationException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient String field;

        ResourceValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** 409 — a unique constraint, named per field. */
    public static class DuplicateResourceException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient Map<String, String> fieldErrors;

        DuplicateResourceException(Map<String, String> fieldErrors) {
            super(String.join("; ", fieldErrors.values()));
            this.fieldErrors = Map.copyOf(fieldErrors);
        }

        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }

    /**
     * B-012 · 409, because the request is well formed and the row it names is
     * real — what is wrong is the shape the two of them would make together.
     *
     * <p><b>Self-reference raises this too, and that is a change from B-011</b>,
     * which returned a 400 for A→A and would have returned nothing at all for
     * A→B→C→A. One refusal for one rule: a client that has to branch on 400 for
     * depth 1 and 409 for depth 4 is being told about the implementation rather
     * than the rule. 409 is what the contract has said all along and what the
     * MSW mock has always returned — the server was the odd one out.
     *
     * <p>Field-keyed at the handler, so the form still puts the message against
     * the manager picker rather than in a banner.
     */
    public static class ManagerCycleException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        /** The request field this is raised against — the form's own name for it. */
        public static final String FIELD = "reportingManagerId";

        ManagerCycleException(String message) {
            super(message);
        }
    }

    /**
     * 409 with {@code openTicketCount} and {@code reassignUrl}, matching
     * {@code PATCH /users/{userId}/status} exactly. Same problem, so the screen
     * should not have to recognise two shapes of it.
     */
    public static class OpenTicketsException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final long userId;
        private final int openTicketCount;

        OpenTicketsException(long userId, int openTicketCount) {
            super("resource " + userId + " still holds " + openTicketCount + " open ticket(s)");
            this.userId = userId;
            this.openTicketCount = openTicketCount;
        }

        public long userId() {
            return userId;
        }

        public int openTicketCount() {
            return openTicketCount;
        }
    }
}
