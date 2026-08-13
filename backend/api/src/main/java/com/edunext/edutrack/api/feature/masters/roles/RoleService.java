package com.edunext.edutrack.api.feature.masters.roles;

import com.edunext.edutrack.domain.identity.Permission;
import com.edunext.edutrack.domain.identity.PermissionRepository;
import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RolePermission;
import com.edunext.edutrack.domain.identity.RolePermissionId;
import com.edunext.edutrack.domain.identity.RolePermissionRepository;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B-015 · S-09, the Role &amp; Permission Master.
 *
 * <p>The data layer was already here — A-003 created {@code roles},
 * {@code permissions} and {@code role_permissions}, and B-001 seeded the six
 * roles and the eighteen capabilities of blueprint §2 cell by cell. This class
 * is the rules that the schema deliberately does not encode.
 *
 * <h2>Deviation from the blueprint: the matrix is not CRUD</h2>
 *
 * <p>Blueprint §S-09 describes the matrix as
 * <em>module × create/read/update/delete/approve</em>. The seeded vocabulary is
 * not CRUD-shaped: eighteen dotted capability strings — {@code ticket.handoff},
 * {@code ticket.force_move}, {@code history.edit_delete} — grouped into six
 * categories. This screen renders <b>category × capability</b> instead, and the
 * deviation is deliberate on two grounds.
 *
 * <p>First, those codes are already load-bearing. {@code AuthUserRepository}
 * reads them into the JWT {@code permissions[]} claim and A-033's
 * {@code @PreAuthorize} expressions name them; replacing the vocabulary to suit
 * a screen would be a cross-stream breaking change wearing a UI costume.
 * Second, the CRUD grid does not exist to be drawn — the seven code prefixes
 * against the sixteen distinct verbs give a 112-cell grid with 18 cells filled,
 * which is less legible than the sections it would replace, not more. B-001's
 * own seed header already describes S-09 as rendering these codes.
 *
 * <h2>Two refusals that only exist here</h2>
 *
 * <p><b>A system role is never deletable</b>, and <b>a role held by resources is
 * never deletable</b>. Neither is enforced by the schema: {@code is_system} is a
 * plain flag, and {@code users.role_id} is a foreign key without a cascade, so
 * the second would fail at the database as a constraint violation naming a MySQL
 * index rather than a way forward. {@link Role}'s own javadoc says it — "the
 * rule lives in the service layer" — and this is that layer.
 */
@Service
public class RoleService {

    /**
     * Capabilities no role may hold, whatever the matrix is asked to save.
     *
     * <p>Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)".
     * {@code history.edit_delete} is seeded with zero grants precisely so S-09
     * can render it, disabled, rather than omit the row and leave an admin
     * unsure whether the guarantee exists at all.
     *
     * <p><b>This is a constant and not a column on purpose.</b> A
     * {@code permissions.is_grantable} flag would be truthful and editable — one
     * {@code UPDATE} from unlocking the append-only rule CLAUDE.md calls "the
     * guarantee that erodes first". The rule is not "this row is currently
     * ungrantable", it is "the append-only guarantee forbids it", and that
     * belongs in code that ships with the enforcement it describes.
     */
    static final Set<String> UNGRANTABLE = Set.of("history.edit_delete");

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionRepository grants;
    private final UserRepository users;
    private final JdbcClient jdbc;

    RoleService(RoleRepository roles,
                PermissionRepository permissions,
                RolePermissionRepository grants,
                UserRepository users,
                JdbcClient jdbc) {
        this.roles = roles;
        this.permissions = permissions;
        this.grants = grants;
        this.users = users;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // The permission catalogue
    // ------------------------------------------------------------------

    /**
     * The matrix's row axis, already in the order the screen groups it —
     * {@code (category, code)}, so no client sorts it a seventh way.
     */
    @Transactional(readOnly = true)
    public List<RoleDtos.Permission> permissions() {
        return permissions.findAllByOrderByCategoryAscCodeAsc().stream()
                .map(RoleService::toPermission)
                .toList();
    }

    // ------------------------------------------------------------------
    // Roles
    // ------------------------------------------------------------------

    /**
     * @param isActive null for every role; the picker passes {@code true}
     */
    @Transactional(readOnly = true)
    public List<RoleDtos.Role> list(Boolean isActive) {
        Map<Integer, Integer> grantCounts = grantCountsByRole();
        Map<Integer, Long> userCounts = userCountsByRole();
        return roles.findAll().stream()
                .filter(r -> isActive == null || r.isActive() == isActive)
                .sorted(Comparator.comparing(Role::getName, String.CASE_INSENSITIVE_ORDER))
                .map(r -> new RoleDtos.Role(
                        r.getId(), r.getCode(), r.getName(), r.getDescription(),
                        r.isSystem(), r.isActive(),
                        userCounts.getOrDefault(r.getId(), 0L),
                        grantCounts.getOrDefault(r.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<RoleDtos.RoleDetail> find(int roleId) {
        return roles.findById(roleId).map(this::toDetail);
    }

    /**
     * A new role starts with no grants — give it some through
     * {@link #replacePermissions}.
     *
     * <p>The code is upper-cased before the uniqueness check rather than after,
     * so {@code developer} is refused as a duplicate of {@code DEVELOPER}
     * instead of being stored beside it. Two roles differing only in case would
     * both satisfy {@code uq_roles_code} on a case-sensitive collation and be
     * indistinguishable in every screen that renders them.
     */
    @Transactional
    public RoleDtos.RoleDetail create(RoleDtos.RoleWrite write) {
        String code = write.code().trim().toUpperCase(Locale.ROOT);
        if (roles.existsByCode(code)) {
            throw new DuplicateRoleCodeException(code);
        }

        Role role = new Role();
        role.setCode(code);
        role.setName(write.name().trim());
        role.setDescription(blankToNull(write.description()));
        role.setActive(write.isActive() == null || write.isActive());
        // Not settable from the request. Nothing created here is one of the six.
        role.setSystem(false);

        return toDetail(roles.save(role));
    }

    /**
     * Rename, re-describe, activate or deactivate.
     *
     * <p><b>A code change is refused, including on a non-system role.</b> The
     * code is denormalised into every issued JWT's {@code role} claim, into the
     * {@code @PreAuthorize} expressions compiled at startup and into
     * {@code workflow_transitions.role_code}; changing it orphans all three at
     * once while leaving each of them looking healthy. It is the
     * {@code project_code} problem in a different table. Deactivate and create a
     * replacement.
     *
     * <p>A system role <em>may</em> be renamed and deactivated. {@code name} and
     * {@code description} are display text; {@code isSystem} guards deletion,
     * not editing, and blocking a deactivation would make a role impossible to
     * retire from the pickers without breaking the resources that hold it.
     */
    @Transactional
    public Optional<RoleDtos.RoleDetail> update(int roleId, RoleDtos.RolePatch patch) {
        Optional<Role> found = roles.findById(roleId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Role role = found.get();

        if (patch.code() != null
                && !patch.code().trim().toUpperCase(Locale.ROOT).equals(role.getCode())) {
            throw new ImmutableRoleCodeException(role.getCode());
        }
        if (patch.name() != null) {
            role.setName(patch.name().trim());
        }
        if (patch.description() != null) {
            role.setDescription(blankToNull(patch.description()));
        }
        if (patch.isActive() != null) {
            role.setActive(patch.isActive());
        }
        return Optional.of(toDetail(roles.save(role)));
    }

    /**
     * Two separate refusals, because the remedy differs: a system role is
     * deactivated instead, a role in use has its resources reassigned first.
     *
     * @return false when no such role exists — the caller turns that into a 404
     */
    @Transactional
    public boolean delete(int roleId) {
        Optional<Role> found = roles.findById(roleId);
        if (found.isEmpty()) {
            return false;
        }
        Role role = found.get();
        if (role.isSystem()) {
            throw new SystemRoleUndeletableException(role.getCode());
        }
        long held = users.countByRoleId(roleId);
        if (held > 0) {
            throw new RoleInUseException(role.getCode(), held);
        }
        // role_permissions cascades on the foreign key, but clearing it here
        // keeps the delete legible to anyone reading the service rather than
        // the DDL.
        grants.deleteById_RoleId(roleId);
        roles.delete(role);
        return true;
    }

    // ------------------------------------------------------------------
    // The matrix
    // ------------------------------------------------------------------

    /**
     * Saves one role's row of the matrix, as a replace-all.
     *
     * <p>Delete is legitimate here and nowhere else in this codebase:
     * {@code role_permissions} is <em>current state</em>, not the append-only
     * audit. {@link RolePermissionRepository#deleteById_RoleId} says so at the
     * repository, and the change itself is recorded in the audit log.
     *
     * <p>Unknown codes are refused naming each one, rather than skipped. A typo
     * that quietly grants nothing is a permission bug found in production, by
     * someone who cannot do their job and has been told they can.
     */
    @Transactional
    public Optional<RoleDtos.RoleDetail> replacePermissions(int roleId, List<String> codes) {
        Optional<Role> found = roles.findById(roleId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Role role = found.get();

        // LinkedHashSet: duplicates collapse, and the order the caller sent
        // survives into the error message if one of them is unknown.
        Set<String> wanted = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                wanted.add(code.trim());
            }
        }

        List<String> ungrantable = wanted.stream().filter(UNGRANTABLE::contains).toList();
        if (!ungrantable.isEmpty()) {
            throw new UngrantablePermissionException(ungrantable);
        }

        Map<String, Permission> byCode = permissions.findAll().stream()
                .collect(Collectors.toMap(Permission::getCode, Function.identity()));
        List<String> unknown = wanted.stream().filter(c -> !byCode.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            throw new UnknownPermissionException(unknown);
        }

        grants.deleteById_RoleId(roleId);
        // The delete and the inserts hit the same rows in one transaction;
        // without the flush Hibernate can order the inserts first and collide
        // with the primary key it is about to remove.
        grants.flush();

        List<RolePermission> replacement = new ArrayList<>(wanted.size());
        for (String code : wanted) {
            replacement.add(grant(role, byCode.get(code)));
        }
        grants.saveAll(replacement);

        return Optional.of(toDetail(role));
    }

    /**
     * Assembles a grant row.
     *
     * <p>The {@code @EmbeddedId} is set explicitly as well as the two
     * {@code @MapsId} associations. Hibernate will derive the key from the
     * associations on flush, but {@code saveAll} calls {@code isNew()} first,
     * and a null id makes every row look new — which is right here, and would
     * quietly become a {@code merge}-per-row {@code SELECT} if it were not.
     */
    private static RolePermission grant(Role role, Permission permission) {
        RolePermissionId id = new RolePermissionId();
        id.setRoleId(role.getId());
        id.setPermissionId(permission.getId());

        RolePermission rp = new RolePermission();
        rp.setId(id);
        rp.setRole(role);
        rp.setPermission(permission);
        return rp;
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    private RoleDtos.RoleDetail toDetail(Role role) {
        List<String> codes = grants.findById_RoleId(role.getId()).stream()
                .map(rp -> rp.getPermission())
                .sorted(Comparator.comparing(Permission::getCategory)
                        .thenComparing(Permission::getCode))
                .map(Permission::getCode)
                .toList();

        return new RoleDtos.RoleDetail(
                role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.isSystem(), role.isActive(),
                users.countByRoleId(role.getId()), codes.size(), codes);
    }

    /**
     * One query for every role's grant count, rather than one per row.
     *
     * <p>Six roles makes an N+1 here cheap enough to be invisible, which is
     * exactly how an N+1 survives to a table that is not six rows.
     */
    private Map<Integer, Integer> grantCountsByRole() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (RolePermission rp : grants.findAll()) {
            counts.merge(rp.getRole().getId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The same, for holders — one grouped count rather than one per role.
     *
     * <p>Plain SQL through {@link JdbcClient} because {@code users} is far too
     * large to count in memory the way the grants are, and a grouped projection
     * is not what {@code UserRepository} is for. {@code ResourceRepository} in
     * this feature reaches for the same tool for the same reason. The
     * per-role {@code countByRoleId} stays: the delete check and the detail
     * read each want exactly one number.
     */
    private Map<Integer, Long> userCountsByRole() {
        Map<Integer, Long> counts = new HashMap<>();
        jdbc.sql("SELECT role_id, COUNT(*) AS holders FROM users GROUP BY role_id")
                .query((rs, row) -> counts.put(rs.getInt("role_id"), rs.getLong("holders")))
                .list();
        return counts;
    }

    private static RoleDtos.Permission toPermission(Permission p) {
        return new RoleDtos.Permission(
                p.getId(), p.getCode(), p.getName(), p.getDescription(), p.getCategory(),
                !UNGRANTABLE.contains(p.getCode()));
    }

    /**
     * An empty description and an absent one mean the same thing, so they are
     * stored the same way — otherwise "has a description" is true for a role
     * whose description renders as nothing.
     */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------
    // Refusals — see RoleExceptionHandler for the wire shapes
    // ------------------------------------------------------------------

    static class DuplicateRoleCodeException extends RuntimeException {
        DuplicateRoleCodeException(String code) {
            super("A role with code '" + code + "' already exists");
        }
    }

    static class ImmutableRoleCodeException extends RuntimeException {
        ImmutableRoleCodeException(String code) {
            super("A role code cannot be changed once created — '" + code + "' is carried in "
                    + "issued tokens, authorisation rules and workflow transitions. "
                    + "Deactivate this role and create a replacement.");
        }
    }

    static class SystemRoleUndeletableException extends RuntimeException {
        SystemRoleUndeletableException(String code) {
            super("'" + code + "' is a system role and cannot be deleted. Deactivate it instead.");
        }
    }

    static class RoleInUseException extends RuntimeException {
        private final long userCount;

        RoleInUseException(String code, long userCount) {
            super(userCount + " resource" + (userCount == 1 ? "" : "s") + " still hold '" + code
                    + "'. Reassign them before deleting the role.");
            this.userCount = userCount;
        }

        long userCount() {
            return userCount;
        }
    }

    static class UnknownPermissionException extends RuntimeException {
        UnknownPermissionException(List<String> codes) {
            super("No such permission: " + String.join(", ", codes));
        }
    }

    static class UngrantablePermissionException extends RuntimeException {
        UngrantablePermissionException(List<String> codes) {
            super(String.join(", ", codes) + " cannot be granted to any role. Blueprint §2: "
                    + "ticket history and the ribbon are append-only, and nobody may edit them.");
        }
    }
}
