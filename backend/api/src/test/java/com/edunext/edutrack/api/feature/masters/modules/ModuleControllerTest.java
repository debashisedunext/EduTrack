package com.edunext.edutrack.api.feature.masters.modules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-064 · the envelope, the guard and the absence of a write path.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code TaskTypeControllerTest} and {@code RoleControllerTest} do:
 * {@code MasterRoutesTest} covers the one thing plain construction cannot see,
 * which is where the class is mounted.
 */
class ModuleControllerTest {

    private ModuleService service;
    private ModuleController controller;

    @BeforeEach
    void setUp() {
        service = mock(ModuleService.class);
        controller = new ModuleController(service);
    }

    /**
     * {@code { "data": [...] }} — the contract's {@code ModuleListResponse}, not
     * a bare array. A JSON array at the top level is the shape that cannot grow
     * a sibling field later without breaking every consumer.
     */
    @Test
    @DisplayName("the list is wrapped in the contract's data envelope")
    void listIsWrapped() {
        ModuleDtos.ModuleView fees = new ModuleDtos.ModuleView(3L, "FEES", "Fees", (short) 30, true);
        when(service.list()).thenReturn(List.of(fees));

        assertThat(controller.modules()).isEqualTo(new ModuleDtos.ModuleListResponse(List.of(fees)));
    }

    /** An empty master serialises as an empty array, never as {@code null}. */
    @Test
    @DisplayName("an empty master still answers with a data array")
    void emptyMasterStillCarriesData() {
        when(service.list()).thenReturn(List.of());

        assertThat(controller.modules().data()).isNotNull().isEmpty();
    }

    /**
     * <b>All six roles, and the assertion is on the annotation.</b>
     *
     * <p>{@code PermissionMatrix} states the same expectation independently and
     * {@code RouteAuthorizationTest} proves it end to end; this one is here so
     * that narrowing the read to {@code hasAuthority('master.write')} — a
     * plausible-looking edit, since every neighbouring master has writes that
     * say exactly that — fails in this file too, beside the reason.
     *
     * <p>The reason is §2 row 3: every role may raise a ticket, §7.5 puts the
     * Module field on the create form, so a role that could not list modules
     * could not fill it in.
     */
    @Test
    @DisplayName("the read is open to every authenticated role")
    void readIsOpenToEveryRole() throws NoSuchMethodException {
        PreAuthorize guard = ModuleController.class.getDeclaredMethod("modules")
                .getAnnotation(PreAuthorize.class);

        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("isAuthenticated()");
    }

    /**
     * B-064 · <b>reference data, served read-only.</b>
     *
     * <p>The backlog is explicit that an admin CRUD screen is a new task on the
     * S-11/S-12 pattern rather than a widening of this one — a write here would
     * arrive with no {@code ETag}, no {@code If-Match} precondition and no
     * {@code ticketCount} to make a retire decision informed, none of which the
     * absence of this test would make anybody notice.
     */
    @Test
    @DisplayName("there is no write route on this controller")
    void noWriteRouteExists() {
        assertThat(Arrays.stream(ModuleController.class.getDeclaredMethods())
                .filter(ModuleControllerTest::isWrite)
                .map(Method::getName))
                .as("modules are reference data — a write belongs to a separate admin-screen task")
                .isEmpty();
    }

    /** The prefix nothing declares globally — B-023 shipped a controller without it. */
    @Test
    @DisplayName("mounted under /api/v1/masters")
    void mountedWhereTheContractPutsIt() {
        assertThat(ModuleController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/masters");
    }

    private static boolean isWrite(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}
