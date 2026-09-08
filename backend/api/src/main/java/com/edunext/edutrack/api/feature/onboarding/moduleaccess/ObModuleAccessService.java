package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.jwt.JwtProperties;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.PageMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.AlreadyRevokedException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.DuplicateGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantNotFoundException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantValidationException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.LastAdminGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.NotAnOnboardingAdminException;

/**
 * A-117 · OB-08's three operations.
 *
 * <h2>The Admin check is here, not on the controller</h2>
 *
 * <p>{@code @PreAuthorize} cannot express it. The onboarding module has its own
 * role vocabulary in {@code user_module_access} — plan §3's six — which is not
 * blueprint §2's six and is not what Spring's authorities carry. The role is in
 * the {@code moduleRoles} claim, read through {@link CallerIdentity}, and the
 * only place that can read it is Java. Every onboarding controller in this
 * codebase says the same thing; this is the eighth.
 *
 * <h2>Auditing is not called from here, and that is deliberate</h2>
 *
 * <p>A-071 derives the audit term from the route in {@code AuditInterceptor},
 * registered across {@code /api/**}. So the grant and the revoke are logged the
 * day they are written, without a line in this class. Adding an explicit
 * {@code AuditTrail.record} call would produce a second row for the same act
 * and start the per-service pattern A-071's own javadoc argues against — a log
 * whose completeness is as good as the last person who remembered.
 *
 * <p>The row-level audit — <em>who</em> granted, <em>who</em> revoked and
 * when — is the table's own four columns, written below. That is the part an
 * access audit reads, and it is why A-109 revokes rather than deletes.
 */
@Service
class ObModuleAccessService {

    /** {@code ObModule}. Closed by {@code ck_user_module_access_module}. */
    private static final Set<String> MODULES = Set.of("TICKETING", "ONBOARDING");

    /** {@code ObModuleRole}. Closed by {@code ck_user_module_access_module_role}. */
    private static final Set<String> MODULE_ROLES = Set.of(
            "OB_ADMIN", "OB_MANAGER", "OB_SALES", "OB_STEP_OWNER", "OB_VIEWER", "TICKETING_MEMBER");

    private static final String OB_ADMIN = "OB_ADMIN";

    /** {@code Limit}'s default and ceiling, as every paged onboarding read uses. */
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final ObModuleAccessRepository repository;
    private final JwtProperties jwt;
    private final Clock clock;

    /**
     * {@code api} publishes no {@code Clock} bean — {@code worker} does, this
     * module does not — so one is supplied here rather than injected.
     * {@code DashboardService} and {@code TicketCodeGenerator} set this pattern
     * and the reason is worth repeating: asking the container for a
     * {@code Clock} that nothing declares does not fail at the call site, it
     * fails the <em>whole application context</em>, and every
     * {@code @SpringBootTest} in the module goes red at once with an error
     * naming the context rather than the cause. This class cost exactly that
     * before the constructor was split.
     */
    @Autowired
    ObModuleAccessService(ObModuleAccessRepository repository, JwtProperties jwt) {
        this(repository, jwt, Clock.systemUTC());
    }

    /**
     * Test seam. {@code grantedAt} and {@code revokedAt} are the audit trail,
     * so they have to be assertable against a fixed instant rather than
     * inferred from a clock that only moves forwards.
     */
    ObModuleAccessService(ObModuleAccessRepository repository, JwtProperties jwt, Clock clock) {
        this.repository = repository;
        this.jwt = jwt;
        this.clock = clock;
    }

    /**
     * <h2>Not {@code @Transactional}, and that is load-bearing</h2>
     *
     * <p>All three methods here refuse a non-Admin as their first act, and a
     * transactional boundary would open a connection <em>before</em> that
     * refusal. In an environment with no reachable database — which is what
     * {@code PermissionMatrixTest} is — the 403 would arrive as a 500 from
     * {@code CannotCreateTransactionException}, so the guard would look broken
     * in exactly the suite written to prove it is not. An authorisation check
     * that needs a database to say no is the wrong shape.
     *
     * <p>Nothing is given up. This is one {@code JdbcClient} query; the write
     * paths below explain why they do not need a transaction either.
     */
    ObModuleAccessDtos.GrantListResponse list(CallerIdentity caller,
                                              String module,
                                              Long userId,
                                              String moduleRole,
                                              boolean includeRevoked,
                                              String cursor,
                                              Integer limit) {
        requireAdmin(caller);

        int size = clampLimit(limit);
        // One more than asked for, so hasMore is answered by the read rather
        // than by a second COUNT over the same predicate.
        List<ObModuleAccessRepository.Row> rows = repository.search(
                userId, normalise(module), normalise(moduleRole), includeRevoked, cursor, size + 1);

        boolean hasMore = rows.size() > size;
        List<ObModuleAccessRepository.Row> page = hasMore ? rows.subList(0, size) : rows;

        PageMeta meta = hasMore
                ? new PageMeta(cursorAfter(page.get(page.size() - 1)), true)
                : PageMeta.last();

        return new ObModuleAccessDtos.GrantListResponse(page.stream().map(this::toDto).toList(), meta);
    }

    /**
     * OB-08's grant.
     *
     * <p>The duplicate check and the insert are not wrapped in a transaction,
     * because a transaction would not have made them atomic against a
     * concurrent grant anyway — at READ COMMITTED both callers pass the check
     * and one loses at the index. {@code uq_user_module_access_live} is the
     * guarantee; the check exists for the <em>message</em>, so the ordinary
     * case never sees a MySQL constraint name.
     *
     * <p>So the loser of that race is caught below and given the same 409 the
     * check gives, rather than the 500 a bare
     * {@code DuplicateKeyException} would become. That is the outcome a
     * transaction was supposed to buy and did not.
     */
    ObModuleAccessDtos.Grant grant(CallerIdentity caller, ObModuleAccessDtos.GrantRequest request) {
        requireAdmin(caller);

        String module = requireEnum(normalise(request.module()), MODULES, "module");
        String moduleRole = requireEnum(normalise(request.moduleRole()), MODULE_ROLES, "moduleRole");
        long userId = request.userId();

        if (!repository.isActiveUser(userId)) {
            // Deliberately the same 400 an unknown module gets, rather than a
            // 404: the missing thing is a value in the body, not the resource
            // being addressed, and OB-08 renders it against the user field.
            throw new GrantValidationException("No active user with id " + userId + ".");
        }
        if (repository.findLiveGrant(userId, module).isPresent()) {
            throw new DuplicateGrantException(module);
        }

        Instant now = clock.instant();
        long id;
        try {
            id = repository.insert(userId, module, moduleRole, caller.userId(), now);
        } catch (DuplicateKeyException lostTheRace) {
            // uq_user_module_access_live refused a second live grant. The check
            // above passed, so somebody granted the same pair in between — the
            // answer is the one they would have got a moment earlier.
            throw new DuplicateGrantException(module);
        }

        return repository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("grant " + id + " vanished after insert"));
    }

    /**
     * OB-08's revoke.
     *
     * <p>Order matters and is asserted in the tests: <b>the last-admin check
     * runs before the update</b>. Reversed, the module would be left without an
     * administrator and the refusal would have to undo a write — and a
     * compensating write is exactly what this table's design refuses to rely
     * on. That is also why the ordering is asserted on the call sequence rather
     * than on the final state: with no transaction here there is no rollback to
     * make "check afterwards" nearly equivalent.
     *
     * <p>The update itself is atomic without one. {@code WHERE revoked_at IS
     * NULL} makes it a compare-and-set, so a concurrent revoke changes no rows
     * and answers the same 422 the sequential case gets.
     */
    ObModuleAccessDtos.Grant revoke(CallerIdentity caller, long grantId) {
        requireAdmin(caller);

        ObModuleAccessRepository.Row existing = repository.findById(grantId)
                .orElseThrow(() -> new GrantNotFoundException(grantId));

        if (!existing.isLive()) {
            throw new AlreadyRevokedException();
        }
        if (OB_ADMIN.equals(existing.moduleRole()) && repository.countLiveAdminGrants() <= 1) {
            throw new LastAdminGrantException();
        }

        int changed = repository.revoke(grantId, caller.userId(), clock.instant());
        if (changed == 0) {
            // Somebody else revoked it between the read above and this update.
            // The same answer the sequential case gets, which is the point of
            // the WHERE revoked_at IS NULL on the statement.
            throw new AlreadyRevokedException();
        }

        return repository.findById(grantId)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("grant " + grantId + " vanished after revoke"));
    }

    /**
     * <p>The claim, not the table. A per-request read of
     * {@code user_module_access} would make the check exact rather than up to
     * fifteen minutes stale — and it is the read A-111's design deliberately
     * does not make, on every request in the module, to answer a question the
     * token already carries. The staleness is stated on the wire instead, as
     * {@code tokenLagSeconds}, so OB-08 can tell an admin what it means.
     */
    private void requireAdmin(CallerIdentity caller) {
        if (caller.moduleRole(ModuleAccessGuard.ONBOARDING).filter(OB_ADMIN::equals).isEmpty()) {
            throw new NotAnOnboardingAdminException();
        }
    }

    private ObModuleAccessDtos.Grant toDto(ObModuleAccessRepository.Row row) {
        return new ObModuleAccessDtos.Grant(
                row.id(),
                new ObModuleAccessDtos.UserRef(row.userId(), row.userName()),
                row.module(),
                row.moduleRole(),
                userRef(row.grantedBy(), row.grantedByName()),
                row.grantedAt(),
                row.isLive(),
                userRef(row.revokedBy(), row.revokedByName()),
                row.revokedAt(),
                (int) jwt.accessTokenTtl().toSeconds());
    }

    /** Null rather than a placeholder — see the schema's note on migration-made grants. */
    private static ObModuleAccessDtos.UserRef userRef(Long id, String displayName) {
        return id == null ? null : new ObModuleAccessDtos.UserRef(id, displayName);
    }

    private static String cursorAfter(ObModuleAccessRepository.Row row) {
        return new Cursor(row.grantedAt().toString(), row.id()).encode();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Upper-cased so a client sending {@code onboarding} is not silently told
     * there are no grants — the filter is over a closed vocabulary stored in
     * upper case, and a case-sensitive miss reads as an empty module rather
     * than as a typo.
     */
    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireEnum(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new GrantValidationException(
                    field + " must be one of " + allowed.stream().sorted().toList() + ".");
        }
        return value;
    }
}
