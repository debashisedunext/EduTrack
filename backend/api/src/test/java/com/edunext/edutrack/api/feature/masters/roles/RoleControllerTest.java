package com.edunext.edutrack.api.feature.masters.roles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

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
 * B-015 · what the controller decides before delegating — the {@code If-Match}
 * precondition and the 404 that comes before it.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code ResourceControllerTest} does: everything asserted here is method-level,
 * and {@code MasterRoutesTest} covers the one thing plain construction cannot
 * see, which is where the class is mounted.
 */
class RoleControllerTest {

    private RoleService service;
    private RoleController controller;

    @BeforeEach
    void setUp() {
        service = mock(RoleService.class);
        controller = new RoleController(service);
    }

    @Nested
    @DisplayName("If-Match")
    class Preconditions {

        @BeforeEach
        void storedRole() {
            when(service.find(9)).thenReturn(Optional.of(detail(9, List.of("ticket.create"))));
        }

        @Test
        @DisplayName("a write without If-Match is 428, not allowed through")
        void missingPreconditionIs428() {
            // Treating a missing precondition as "no conflict" would mean the
            // guard protects only the clients that already opted in — the set
            // that needed it least.
            assertThatThrownBy(() -> controller.update(9, null,
                    new RoleDtos.RolePatch(null, "Auditor", null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

            verify(service, never()).update(anyInt(), any());
        }

        @Test
        @DisplayName("a blank If-Match is treated as absent")
        void blankPreconditionIs428() {
            assertThatThrownBy(() -> controller.replacePermissions(9, "   ",
                    new RoleDtos.RolePermissionsWrite(List.of())))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale If-Match is 412")
        void stalePreconditionIs412() {
            assertThatThrownBy(() -> controller.update(9, "\"deadbeef\"",
                    new RoleDtos.RolePatch(null, "Auditor", null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_FAILED));

            verify(service, never()).update(anyInt(), any());
        }

        @Test
        @DisplayName("the tag from the read is accepted back on the write")
        void currentTagRoundTrips() {
            String tag = controller.role(9).getHeaders().getETag();
            when(service.update(anyInt(), any()))
                    .thenReturn(Optional.of(detail(9, List.of("ticket.create"))));

            ResponseEntity<RoleDtos.RoleDetailResponse> saved = controller.update(9, tag,
                    new RoleDtos.RolePatch(null, "Auditor", null, null));

            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(saved.getHeaders().getETag()).isNotNull();
        }

        @Test
        @DisplayName("the tag covers the grants, so a matrix save invalidates a concurrent rename")
        void tagCoversTheGrants() {
            // Two people on the same screen: one ticks a box, the other renames
            // the role. The second must be told to reload rather than silently
            // overwrite what the first saved.
            String beforeGrantChange = controller.role(9).getHeaders().getETag();
            when(service.find(9)).thenReturn(
                    Optional.of(detail(9, List.of("ticket.create", "ticket.close"))));

            assertThatThrownBy(() -> controller.update(9, beforeGrantChange,
                    new RoleDtos.RolePatch(null, "Auditor", null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.PRECONDITION_FAILED));
        }

        @Test
        @DisplayName("`*` matches anything, per RFC 9110")
        void wildcardMatches() {
            when(service.update(anyInt(), any()))
                    .thenReturn(Optional.of(detail(9, List.of("ticket.create"))));

            assertThat(controller.update(9, "*",
                    new RoleDtos.RolePatch(null, "Auditor", null, null)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("not found")
    class NotFound {

        @Test
        @DisplayName("404 beats 428 — a tag cannot be fetched from a URL that 404s")
        void missingRoleIs404EvenWithoutAPrecondition() {
            when(service.find(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(404, null,
                    new RoleDtos.RolePatch(null, "x", null, null)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a delete of an unknown role is 404, never 403")
        void deleteOfUnknownRoleIs404() {
            when(service.delete(404)).thenReturn(false);

            assertThatThrownBy(() -> controller.delete(404))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a successful delete is 204 with no body")
        void deleteIs204() {
            when(service.delete(9)).thenReturn(true);

            assertThat(controller.delete(9).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Test
    @DisplayName("create answers 201 carrying the tag its first edit will need")
    void createIs201WithAnETag() {
        when(service.create(any())).thenReturn(detail(9, List.of()));

        ResponseEntity<RoleDtos.RoleDetailResponse> created = controller.create(
                new RoleDtos.RoleWrite("AUDITOR", "Auditor", null, null));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getETag()).isNotNull();
    }

    @Test
    @DisplayName("the role list passes its isActive filter through untouched")
    void listPassesTheFilter() {
        when(service.list(true)).thenReturn(List.of());

        controller.roles(true);

        verify(service).list(true);
    }

    private static RoleDtos.RoleDetail detail(int id, List<String> codes) {
        return new RoleDtos.RoleDetail(id, "AUDITOR", "Auditor", null, false, true,
                0L, codes.size(), codes);
    }
}
