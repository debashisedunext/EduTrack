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
            "com.edunext.edutrack.api.feature.masters.priorities.PriorityController",
            "com.edunext.edutrack.api.feature.masters.statuses.StatusController",
            "com.edunext.edutrack.api.feature.masters.stages.StageController",
            "com.edunext.edutrack.api.feature.masters.tasktypes.TaskTypeController",
            "com.edunext.edutrack.api.feature.masters.modules.ModuleController",
            "com.edunext.edutrack.api.feature.masters.notificationtemplates"
                    + ".NotificationTemplateController",
            "com.edunext.edutrack.api.feature.masters.projects.ProjectController",
            "com.edunext.edutrack.api.feature.masters.projects.ProjectMemberController",
            "com.edunext.edutrack.api.feature.masters.projects.SlaPolicyController",
            "com.edunext.edutrack.api.feature.masters.projects.ProjectSettingsController");

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
     * B-021 · and this is the <b>fifth</b> instance of the gap this file exists
     * for — the second in a row where shipped screens already call the route.
     *
     * <p>{@code listPriorities} has been in the contract, in the MSW mock and in
     * the generated TypeScript client since D-001 with <b>no controller anywhere
     * in the backend</b> — after B-023's nine calendar operations, B-014's
     * {@code PATCH /users/{userId}/status}, B-018's two SLA operations and
     * B-020's {@code listTaskTypes}. Nothing failed, because the two screens
     * that call it have only ever run against the mock:
     * {@code CreateTicketPage} builds its {@code LevelPicker} from this route
     * and {@code TicketListPage} builds its level filter from it. Both would
     * have 404'd on first contact with a real backend.
     */
    @Test
    @DisplayName("the priority master is where the contract says it is")
    void priorityMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.priorities.PriorityController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    /**
     * B-020 · and this is the <b>fourth</b> instance of the gap this file exists
     * for — the one the B-021 note above counts from.
     *
     * <p>{@code listTaskTypes} has been in the contract, in the MSW mock and in
     * the generated TypeScript client since D-001 with no server behind it —
     * after B-023's nine calendar operations, B-014's
     * {@code PATCH /users/{userId}/status} and B-018's two SLA operations.
     * Nothing failed, because the three screens that call it (the ticket list,
     * the ticket detail page and the create form) have only ever run against the
     * mock. Every one of them would have 404'd on first contact with a real
     * backend.
     *
     * <p>B-021 landed first and B-020 second, so the ordinals here are discovery
     * order rather than merge order. That is the useful reading: five operations
     * in this contract have now been found declared, mocked and unmounted, and
     * nothing systematically looks for the sixth.
     */
    @Test
    @DisplayName("the task type master is where the contract says it is")
    void taskTypeMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.tasktypes.TaskTypeController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    /**
     * B-064 · §7.3's module master, and the pattern this file was opened for
     * has now produced its <b>seventh</b> — the one with the most already built
     * on top of it.
     *
     * <p>{@code listModules} has been in the contract, in the MSW mock and in
     * the generated TypeScript client since D-060 with no controller anywhere
     * in the backend, while three of Stream C's screens shipped against it:
     * {@code CreateTicketPage}'s module picker (C-068), S-20's "Where it
     * happened" group and its inline editor (C-069), and S-17's module filter
     * and grid column (C-070).
     *
     * <p>What is worth recording is how the failure would have presented. The
     * other six 404'd loudly on their first real request. This one degrades
     * quietly: {@code moduleName()} returns {@code undefined} when the master
     * has not loaded, the cell renders an em dash, and every ticket in the
     * product reads as "no module was recorded" — which is a plausible state a
     * ticket can genuinely be in. A route nobody mounted would have looked like
     * data nobody filled in.
     */
    @Test
    @DisplayName("the module master is where the contract says it is")
    void moduleMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.modules.ModuleController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    /**
     * B-022 · the sixth the note above says nothing systematically looks for —
     * and the one where nothing had even been declared.
     *
     * <p>{@code notification_templates} has existed since A-007, the entity and
     * repository since B-005, and {@code email_log.template_id} has pointed at it
     * since {@code V20260805_1530} — with <b>no controller, no contract operation
     * and no caller anywhere in the codebase</b>. That is a different shape from
     * the five above: those were declared and mocked and never mounted, this was
     * modelled and never reached. The outcome was the same, and it is why the
     * assertion goes in on the day the route does.
     */
    @Test
    @DisplayName("the notification template master is where the contract says it is")
    void notificationTemplateMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.notificationtemplates"
                + ".NotificationTemplateController").getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/masters");
    }

    /**
     * B-040 · S-13 tab 2, and the seventh instance of the pattern above with a
     * twist the other six did not have.
     *
     * <p>{@code listWorkflowTemplates} was declared, mocked and never mounted like
     * five of them — and the shape it was declared with had also drifted from
     * A-005's table, so B-040 corrected the contract as well as serving it. That
     * makes this mount point the one place where the path and the shape were both
     * unverified until the day the route landed.
     *
     * <p>The stage routes are nested beneath it rather than sitting at
     * {@code /masters/stages}, because {@code workflow_stages.template_id} is
     * {@code NOT NULL}: there is no stage outside a template to address.
     */
    @Test
    @DisplayName("the stage master is mounted under the workflow templates it belongs to")
    void stageMasterIsMountedWhereTheContractPutsIt() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.stages.StageController")
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

    /**
     * B-017 · the Team tab hangs off the project, not off {@code /masters}.
     *
     * <p>Asserted separately from the prefix rule because the interesting part
     * is the whole path: the contract nests the roster under the project id, and
     * a controller mapped at {@code /api/v1/project-members} would satisfy the
     * prefix check and answer nowhere any client calls. That is the shape of the
     * mistake B-023 shipped, and B-014 found the same class of gap again when
     * {@code PATCH /users/{userId}/status} turned out to have been declared,
     * mocked and never mounted.
     */
    @Test
    @DisplayName("the team tab is nested under the project it belongs to")
    void projectMembersAreMountedWhereTheContractPutsThem() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.projects.ProjectMemberController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/projects/{projectId}/members");
    }

    /**
     * B-018 · and this one is the case the whole file exists for.
     *
     * <p>{@code getSlaPolicies} and {@code replaceSlaPolicies} have been in the
     * contract, in the mock and in the generated TypeScript client since D-001,
     * <b>with no server behind either of them</b>. Nothing failed, because
     * nothing called them. That is the third instance of the same gap — B-023's
     * nine calendar operations, B-014's {@code PATCH /users/{userId}/status},
     * and now this — and it is the reason a route's mount point is asserted
     * rather than assumed to follow from the class being written.
     */
    @Test
    @DisplayName("the SLA tab is nested under the project it belongs to")
    void slaPoliciesAreMountedWhereTheContractPutsThem() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.projects.SlaPolicyController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/projects/{projectId}/sla-policies");
    }

    /**
     * B-019 · the fourth tab, asserted on the same day it was written.
     *
     * <p>Nothing had declared these two operations before this task, so this is
     * not the "declared, mocked, never mounted" case the three assertions above
     * document — which is exactly why it is here. The gap those found was never
     * that somebody wrote a controller and misplaced it; it was that the mount
     * point was the one part of an operation nothing checked. A route added
     * with its assertion cannot become the fifth instance.
     */
    @Test
    @DisplayName("the settings tab is nested under the project it belongs to")
    void projectSettingsAreMountedWhereTheContractPutsThem() {
        assertThat(paths(load("com.edunext.edutrack.api.feature.masters.projects.ProjectSettingsController")
                .getAnnotation(RequestMapping.class)))
                .containsExactly("/api/v1/projects/{projectId}/settings");
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
