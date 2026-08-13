package com.edunext.edutrack.api.feature.masters.roles;

import com.edunext.edutrack.domain.identity.Permission;
import com.edunext.edutrack.domain.identity.PermissionRepository;
import com.edunext.edutrack.domain.identity.Role;
import com.edunext.edutrack.domain.identity.RolePermission;
import com.edunext.edutrack.domain.identity.RolePermissionRepository;
import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.identity.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-015 · the rules the schema deliberately does not encode.
 *
 * <p>{@code RoleMasterIT} proves the same decisions against real MySQL, where
 * the foreign keys and unique indexes have an opinion of their own. This proves
 * the decisions themselves, without Docker.
 */
class RoleServiceTest {

    private RoleRepository roles;
    private PermissionRepository permissions;
    private RolePermissionRepository grants;
    private UserRepository users;
    private RoleService service;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        permissions = mock(PermissionRepository.class);
        grants = mock(RolePermissionRepository.class);
        users = mock(UserRepository.class);
        // Only `list()` reaches for JdbcClient, and RoleMasterIT covers that
        // path against real MySQL. Deep-stubbing a fluent builder here would
        // assert the shape of the call rather than what it answers.
        service = new RoleService(roles, permissions, grants, users,
                mock(JdbcClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS));

        when(grants.findById_RoleId(anyInt())).thenReturn(List.of());
        when(grants.findAll()).thenReturn(List.of());
        when(users.countByRoleId(anyInt())).thenReturn(0L);
        // Stands in for AUTO_INCREMENT: without an id, mapping the saved role
        // to a detail unboxes null and the failure looks like a mapping bug
        // rather than a missing stub.
        when(roles.save(any())).thenAnswer(call -> {
            Role saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(9);
            }
            return saved;
        });
    }

    // ------------------------------------------------------------------
    // the catalogue
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the permission catalogue")
    class Catalogue {

        @Test
        @DisplayName("history.edit_delete is returned, and marked ungrantable")
        void historyEditDeleteIsShownDisabledRatherThanHidden() {
            // Blueprint §2: "Edit / delete history or ribbon — ❌ (nobody can)".
            // B-001 seeds it with zero grants *so that this screen can render
            // it* — omitting the row would leave an admin unable to tell whether
            // the append-only guarantee exists or was forgotten.
            when(permissions.findAllByOrderByCategoryAscCodeAsc()).thenReturn(List.of(
                    permission(1, "history.edit_delete", "history"),
                    permission(2, "history.view_team", "history")));

            List<RoleDtos.Permission> catalogue = service.permissions();

            assertThat(catalogue).extracting(RoleDtos.Permission::code)
                    .containsExactly("history.edit_delete", "history.view_team");
            assertThat(catalogue.get(0).isGrantable()).isFalse();
            assertThat(catalogue.get(1).isGrantable()).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("the code is upper-cased before uniqueness is checked, not after")
        void upperCasesBeforeTheDuplicateCheck() {
            // Checking first and upper-casing second would store DEVELOPER
            // beside an existing DEVELOPER on a case-sensitive collation, and
            // the two would be indistinguishable in every screen.
            when(roles.existsByCode("AUDITOR")).thenReturn(true);

            assertThatThrownBy(() -> service.create(
                    new RoleDtos.RoleWrite("auditor", "Auditor", null, null)))
                    .isInstanceOf(RoleService.DuplicateRoleCodeException.class);

            verify(roles, never()).save(any());
        }

        @Test
        @DisplayName("nothing created here can claim to be a system role")
        void neverCreatesASystemRole() {
            RoleDtos.RoleDetail created = service.create(
                    new RoleDtos.RoleWrite("auditor", "Auditor", "Read-only oversight", null));

            assertThat(created.code()).isEqualTo("AUDITOR");
            assertThat(created.isSystem()).isFalse();
            assertThat(created.isActive()).isTrue();
            assertThat(created.permissionCodes()).isEmpty();
        }

        @Test
        @DisplayName("a blank description is stored as null")
        void blankDescriptionBecomesNull() {
            // Otherwise "has a description" is true for a role whose
            // description renders as nothing.
            RoleDtos.RoleDetail created = service.create(
                    new RoleDtos.RoleWrite("AUDITOR", "Auditor", "   ", null));

            assertThat(created.description()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("a code change is refused even on a non-system role")
        void codeIsImmutable() {
            // The code is denormalised into every issued JWT, into the
            // @PreAuthorize expressions and into workflow_transitions.role_code.
            when(roles.findById(9)).thenReturn(Optional.of(role(9, "AUDITOR", false)));

            assertThatThrownBy(() -> service.update(9,
                    new RoleDtos.RolePatch("REVIEWER", null, null, null)))
                    .isInstanceOf(RoleService.ImmutableRoleCodeException.class);

            verify(roles, never()).save(any());
        }

        @Test
        @DisplayName("resending the code unchanged is not a code change")
        void resendingTheSameCodeIsAccepted() {
            // A form that round-trips every field it read would otherwise be
            // unable to save anything at all.
            when(roles.findById(9)).thenReturn(Optional.of(role(9, "AUDITOR", false)));

            Optional<RoleDtos.RoleDetail> saved = service.update(9,
                    new RoleDtos.RolePatch("auditor", "Auditor (external)", null, null));

            assertThat(saved).isPresent();
            assertThat(saved.get().name()).isEqualTo("Auditor (external)");
        }

        @Test
        @DisplayName("a system role may be renamed and deactivated")
        void systemRolesAreEditableJustNotDeletable() {
            // isSystem guards deletion, not editing. Blocking deactivation
            // would make a role impossible to retire from the pickers without
            // breaking the resources that hold it.
            when(roles.findById(1)).thenReturn(Optional.of(role(1, "SUPPORT", true)));

            Optional<RoleDtos.RoleDetail> saved = service.update(1,
                    new RoleDtos.RolePatch(null, "Support Desk (APAC)", null, false));

            assertThat(saved).isPresent();
            assertThat(saved.get().name()).isEqualTo("Support Desk (APAC)");
            assertThat(saved.get().isActive()).isFalse();
            assertThat(saved.get().isSystem()).isTrue();
        }

        @Test
        @DisplayName("an omitted field keeps its stored value")
        void patchIsPartial() {
            Role stored = role(9, "AUDITOR", false);
            stored.setDescription("Read-only oversight");
            when(roles.findById(9)).thenReturn(Optional.of(stored));

            Optional<RoleDtos.RoleDetail> saved = service.update(9,
                    new RoleDtos.RolePatch(null, "Auditor", null, null));

            assertThat(saved).isPresent();
            assertThat(saved.get().description()).isEqualTo("Read-only oversight");
        }

        @Test
        @DisplayName("an unknown role is empty, not an exception")
        void unknownRoleIsEmpty() {
            when(roles.findById(404)).thenReturn(Optional.empty());

            assertThat(service.update(404, new RoleDtos.RolePatch(null, "x", null, null)))
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // delete
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("a system role is refused — deactivate instead")
        void systemRoleIsUndeletable() {
            when(roles.findById(1)).thenReturn(Optional.of(role(1, "ADMIN", true)));

            assertThatThrownBy(() -> service.delete(1))
                    .isInstanceOf(RoleService.SystemRoleUndeletableException.class)
                    .hasMessageContaining("Deactivate it instead");

            verify(roles, never()).delete(any());
        }

        @Test
        @DisplayName("a role held by resources is refused, and the count says how many")
        void roleInUseIsRefusedWithACount() {
            when(roles.findById(9)).thenReturn(Optional.of(role(9, "AUDITOR", false)));
            when(users.countByRoleId(9)).thenReturn(4L);

            assertThatThrownBy(() -> service.delete(9))
                    .isInstanceOf(RoleService.RoleInUseException.class)
                    .satisfies(e -> assertThat(((RoleService.RoleInUseException) e).userCount())
                            .isEqualTo(4L));

            verify(roles, never()).delete(any());
        }

        @Test
        @DisplayName("the system check runs before the in-use check")
        void systemCheckComesFirst() {
            // A system role always has holders, so testing in-use first would
            // report "reassign 6 people" for a role that could never be deleted
            // however many people were reassigned.
            when(roles.findById(1)).thenReturn(Optional.of(role(1, "ADMIN", true)));
            when(users.countByRoleId(1)).thenReturn(6L);

            assertThatThrownBy(() -> service.delete(1))
                    .isInstanceOf(RoleService.SystemRoleUndeletableException.class);
        }

        @Test
        @DisplayName("an unused custom role deletes, taking its grants with it")
        void unusedCustomRoleDeletes() {
            when(roles.findById(9)).thenReturn(Optional.of(role(9, "AUDITOR", false)));

            assertThat(service.delete(9)).isTrue();

            verify(grants).deleteById_RoleId(9);
            verify(roles).delete(any());
        }

        @Test
        @DisplayName("an unknown role is false, which the controller turns into a 404")
        void unknownRoleIsFalse() {
            when(roles.findById(404)).thenReturn(Optional.empty());

            assertThat(service.delete(404)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // the matrix
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the permission matrix")
    class Matrix {

        @BeforeEach
        void catalogue() {
            when(permissions.findAll()).thenReturn(List.of(
                    permission(1, "ticket.create", "ticket"),
                    permission(2, "ticket.close", "ticket"),
                    permission(3, "history.edit_delete", "history")));
            when(roles.findById(9)).thenReturn(Optional.of(role(9, "AUDITOR", false)));
        }

        @Test
        @DisplayName("history.edit_delete is refused server-side, not merely greyed out")
        void grantingHistoryEditDeleteIsRefused() {
            // The disabled checkbox is a courtesy; this is the enforcement. It
            // is the one reachable UI edge on the append-only guarantee.
            assertThatThrownBy(() -> service.replacePermissions(9,
                    List.of("ticket.create", "history.edit_delete")))
                    .isInstanceOf(RoleService.UngrantablePermissionException.class);

            verify(grants, never()).deleteById_RoleId(anyInt());
            verify(grants, never()).saveAll(any());
        }

        @Test
        @DisplayName("an unknown code is refused naming it, not skipped")
        void unknownCodeIsRefused() {
            // A typo that quietly grants nothing is a permission bug found in
            // production, by someone who cannot do their job and has been told
            // they can.
            assertThatThrownBy(() -> service.replacePermissions(9,
                    List.of("ticket.create", "ticket.deleteAll")))
                    .isInstanceOf(RoleService.UnknownPermissionException.class)
                    .hasMessageContaining("ticket.deleteAll");

            verify(grants, never()).saveAll(any());
        }

        @Test
        @DisplayName("nothing is revoked when validation fails")
        void refusalIsAtomic() {
            // The clear-then-insert would otherwise leave the role with no
            // permissions at all after a rejected save.
            assertThatThrownBy(() -> service.replacePermissions(9, List.of("nope")))
                    .isInstanceOf(RoleService.UnknownPermissionException.class);

            verify(grants, never()).deleteById_RoleId(anyInt());
        }

        @Test
        @DisplayName("the save is replace-all: absent codes are revoked")
        void savesAsReplaceAll() {
            service.replacePermissions(9, List.of("ticket.close"));

            verify(grants).deleteById_RoleId(9);
            assertThat(savedCodes()).containsExactly("ticket.close");
        }

        @Test
        @DisplayName("an empty list is a legitimate save, not a malformed one")
        void emptyListRevokesEverything() {
            service.replacePermissions(9, List.of());

            verify(grants).deleteById_RoleId(9);
            assertThat(savedCodes()).isEmpty();
        }

        @Test
        @DisplayName("duplicates and blanks collapse rather than becoming duplicate rows")
        void duplicatesCollapse() {
            // role_permissions has a composite primary key, so a duplicate here
            // is a constraint violation rather than a second row.
            service.replacePermissions(9,
                    List.of("ticket.create", " ticket.create ", "", "  ", "ticket.close"));

            assertThat(savedCodes()).containsExactly("ticket.create", "ticket.close");
        }

        @Test
        @DisplayName("an unknown role is empty, not an exception")
        void unknownRoleIsEmpty() {
            when(roles.findById(404)).thenReturn(Optional.empty());

            assertThat(service.replacePermissions(404, List.of("ticket.create"))).isEmpty();
        }

        @SuppressWarnings("unchecked")
        private List<String> savedCodes() {
            var captor = org.mockito.ArgumentCaptor.forClass(Iterable.class);
            verify(grants).saveAll(captor.capture());
            List<String> codes = new ArrayList<>();
            ((Iterable<RolePermission>) captor.getValue())
                    .forEach(rp -> codes.add(rp.getPermission().getCode()));
            return codes;
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Role role(int id, String code, boolean isSystem) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        role.setSystem(isSystem);
        role.setActive(true);
        return role;
    }

    private static Permission permission(int id, String code, String category) {
        Permission p = new Permission();
        p.setId(id);
        p.setCode(code);
        p.setName(code);
        p.setCategory(category);
        return p;
    }
}
