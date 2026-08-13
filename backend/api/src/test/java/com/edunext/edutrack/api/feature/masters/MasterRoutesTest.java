package com.edunext.edutrack.api.feature.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010 · the request mappings of every controller in the masters feature carry
 * the {@code /api/v1} prefix.
 *
 * <p><b>This exists because the calendar's did not.</b> B-023 shipped
 * {@code CalendarController} mapped at {@code /masters}, and nothing in the
 * application declares that prefix globally — no servlet context path, no
 * {@code configurePathMatch} — so all nine calendar operations answered on a
 * path no client calls. The generated TypeScript, the MSW handlers and
 * {@code contracts/openapi.yaml} agree on {@code /api/v1/masters/…}; the server
 * alone disagreed, and the whole screen would have 404'd on first contact with
 * a real backend.
 *
 * <p>It survived review and a full unit-test suite because
 * {@code CalendarControllerTest} constructs the controller with {@code new} and
 * calls its methods directly — a perfectly good way to test the logic, and one
 * that never asks Spring where the class is mounted. A wiring mistake needs a
 * wiring test.
 *
 * <p>Reflection over the annotation rather than a {@code @SpringBootTest} that
 * boots the context and probes {@code RequestMappingHandlerMapping}: this
 * catches the same class of mistake in milliseconds and without a database, and
 * the assertion reads as the rule it enforces.
 */
class MasterRoutesTest {

    /**
     * Every controller this feature owns. A new one is added here on the same day.
     *
     * <p>By name rather than by {@code .class}, because controllers in this
     * codebase are package-private and {@code ResourceController} lives in the
     * {@code resources} subpackage. Widening a class to {@code public} so a test
     * can see it trades real encapsulation for test convenience; a rename
     * instead fails this test loudly, which is what we want anyway.
     */
    private static final List<String> CONTROLLERS = List.of(
            "com.edunext.edutrack.api.feature.masters.CalendarController",
            "com.edunext.edutrack.api.feature.masters.resources.ResourceController",
            "com.edunext.edutrack.api.feature.masters.roles.RoleController",
            "com.edunext.edutrack.api.feature.masters.projects.ProjectController");

    @Test
    @DisplayName("every masters controller is mapped under /api/v1")
    void everyControllerCarriesTheApiPrefix() {
        for (String className : CONTROLLERS) {
            Class<?> controller = load(className);
            RequestMapping mapping = controller.getAnnotation(RequestMapping.class);

            assertThat(mapping)
                    .as("%s has no @RequestMapping", controller.getSimpleName())
                    .isNotNull();
            assertThat(paths(mapping))
                    .as("%s must be mapped under /api/v1 — nothing adds the prefix for it",
                            controller.getSimpleName())
                    .isNotEmpty()
                    .allSatisfy(path -> assertThat(path).startsWith("/api/v1/"));
        }
    }

    @Test
    @DisplayName("the calendar is where the contract says it is")
    void calendarIsMountedWhereTheContractPutsIt() {
        assertThat(paths(CalendarController.class.getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    @Test
    @DisplayName("the role master is where the contract says it is")
    void roleMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.roles.RoleController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    /**
     * B-016 · and it is <b>not</b> under {@code /masters}.
     *
     * <p>The contract has served projects at the top level since D-001 and five
     * screens already call them there — the project switcher, the ticket list,
     * the create-ticket form and both resource screens. Moving the controller to
     * sit tidily beside the other master screens would break all five to make a
     * URL prettier, so this asserts the path the clients use rather than the one
     * the package name suggests.
     */
    @Test
    @DisplayName("the project master is at /api/v1/projects, not under /masters")
    void projectMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.projects.ProjectController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/projects");
    }

    /** {@code value} and {@code path} are aliases; either may carry the mapping. */
    private static String[] paths(RequestMapping mapping) {
        return mapping.value().length > 0 ? mapping.value() : mapping.path();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(
                    className + " is listed here but does not exist. If it was renamed or moved, "
                            + "update this list — the prefix rule still applies to it.", e);
        }
    }
}
