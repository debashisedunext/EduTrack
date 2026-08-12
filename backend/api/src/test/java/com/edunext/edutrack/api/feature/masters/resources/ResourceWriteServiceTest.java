package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-011 · what the write service decides on its own.
 *
 * <p>Against mocked repositories, so these run without Docker. The three things
 * a mock cannot prove — that the SQL is valid, that the {@code CHECK}
 * constraints hold, and that a partial update really leaves the other columns
 * alone in the database — are {@code ResourceFormIT}'s job.
 */
class ResourceWriteServiceTest {

    private static final long USER_ID = 42L;
    private static final int DEVELOPER_ROLE_ID = 3;

    private ResourceWriteRepository writes;
    private ResourceRepository reads;
    private ResourceWriteService service;

    @BeforeEach
    void setUp() {
        writes = mock(ResourceWriteRepository.class);
        reads = mock(ResourceRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenAnswer(call -> "hashed:" + call.getArgument(0));

        service = new ResourceWriteService(writes, reads, encoder);

        when(writes.findRoleId("DEVELOPER")).thenReturn(Optional.of(DEVELOPER_ROLE_ID));
        when(writes.findConflicts(any(), any(), any(), any()))
                .thenReturn(new ResourceWriteRepository.Conflicts(false, false, false));
        // Everybody reports to nobody unless a test says otherwise. The chain
        // starts at the resource asked about, so a one-element list is "this
        // person exists and is at the top" — and an empty one is "no such
        // person", which is how the service tells the two apart.
        when(writes.managerChain(anyLong(), anyInt()))
                .thenAnswer(call -> List.of((Long) call.getArgument(0)));
        // Null-safe, because re-stubbing with `when(mock.method(any()))` calls
        // the method for real to capture the matcher — with null arguments,
        // through whatever answer is already registered. A `Set.copyOf(null)`
        // here fails the test that is trying to replace this stub.
        when(writes.existingProjectIds(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids == null ? Set.of() : Set.copyOf(ids);
        });
        when(writes.insert(any())).thenReturn(USER_ID);
        // `Objects.toString`, not `String.valueOf` — the latter resolves to the
        // `char[]` overload for an unbound generic argument and throws
        // ClassCastException on a List.
        when(writes.writeJson(any())).thenAnswer(call -> Objects.toString(call.getArgument(0), null));
        when(reads.openTicketCounts(any())).thenReturn(Map.of());
        when(writes.findDetail(anyLong(), anyInt())).thenReturn(Optional.of(detail(true, 0)));
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("generates a password, hashes it, and returns the plaintext exactly once")
        void generatesAndHashesAPassword() {
            ResourceWriteService.Created created = service.create(minimalRequest());

            ArgumentCaptor<ResourceWriteRepository.NewResource> row =
                    ArgumentCaptor.forClass(ResourceWriteRepository.NewResource.class);
            verify(writes).insert(row.capture());

            // The plaintext is returned to the caller and the hash is what is
            // stored. If these were ever the same value the column would hold a
            // password in the clear.
            assertThat(created.temporaryPassword()).isNotBlank();
            assertThat(row.getValue().passwordHash())
                    .isEqualTo("hashed:" + created.temporaryPassword())
                    .isNotEqualTo(created.temporaryPassword());
        }

        @Test
        @DisplayName("force-change-on-first-login is set, and is not something the caller can turn off")
        void forcesPasswordChange() {
            service.create(minimalRequest());

            ArgumentCaptor<ResourceWriteRepository.NewResource> row =
                    ArgumentCaptor.forClass(ResourceWriteRepository.NewResource.class);
            verify(writes).insert(row.capture());

            assertThat(row.getValue().mustChangePassword()).isTrue();
        }

        @Test
        @DisplayName("S-08's defaults apply when the form leaves them out — 8 hours, Asia/Kolkata, active")
        void appliesS08Defaults() {
            service.create(minimalRequest());

            ArgumentCaptor<ResourceWriteRepository.NewResource> row =
                    ArgumentCaptor.forClass(ResourceWriteRepository.NewResource.class);
            verify(writes).insert(row.capture());

            assertThat(row.getValue().dailyCapacityHrs()).isEqualByComparingTo("8.00");
            assertThat(row.getValue().timezone()).isEqualTo("Asia/Kolkata");
            assertThat(row.getValue().isActive()).isTrue();
        }

        /**
         * The bug this pins: {@code getProjects()} is null when the key is
         * absent, and {@code .orElse(null)} on it is an NPE on the ordinary case
         * of creating somebody before their projects are decided.
         */
        @Test
        @DisplayName("a create with no projects key does not blow up, and writes no memberships")
        void createWithoutProjectsKey() {
            service.create(minimalRequest());

            verify(writes, never()).replaceMemberships(anyLong(), any());
        }

        @Test
        @DisplayName("a duplicate username, email and employee code are all named at once")
        void reportsEveryConflictTogether() {
            when(writes.findConflicts(any(), any(), any(), any()))
                    .thenReturn(new ResourceWriteRepository.Conflicts(true, true, true));

            assertThatThrownBy(() -> service.create(minimalRequest()))
                    .isInstanceOf(ResourceWriteService.DuplicateResourceException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.DuplicateResourceException) e).fieldErrors())
                            .containsOnlyKeys("username", "email", "employeeCode"));

            // Nothing is written when the check refuses. Worth a line: the
            // check runs before the insert precisely so a rejected create leaves
            // no half-row behind.
            verify(writes, never()).insert(any());
        }

        @Test
        @DisplayName("an unknown role code is a 400 naming the field, not a foreign-key error")
        void unknownRoleIsAValidationError() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setRole("WIZARD");

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ResourceWriteService.ResourceValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.ResourceValidationException) e).field())
                            .isEqualTo("role"));
        }

        @Test
        @DisplayName("a project id that does not exist is a 400 naming the id")
        void unknownProjectIsAValidationError() {
            when(writes.existingProjectIds(any())).thenReturn(Set.of(7L));

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(7L, "DEVELOPER"),
                    new ResourceDtos.ProjectAssignment(99L, "QA"))));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ResourceWriteService.ResourceValidationException.class)
                    .hasMessageContaining("99");
        }
    }

    // ------------------------------------------------------------------
    // update — the three states of a key
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update: absent, null and a value are three different things")
    class PartialUpdate {

        @Test
        @DisplayName("an absent key does not appear in the UPDATE at all")
        void absentKeyIsNotWritten() {
            service.update(detail(true, 0), minimalRequest());

            assertThat(changeSet()).doesNotContainKeys(
                    "mobile", "avatar_url", "date_of_joining", "department", "designation",
                    "location", "reporting_manager_id", "timezone", "daily_capacity_hrs",
                    "weekly_off", "skills", "is_active");
        }

        @Test
        @DisplayName("an explicit null clears the column")
        void explicitNullClears() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setDepartment(Optional.empty());
            request.setReportingManagerId(Optional.empty());

            service.update(detail(true, 0), request);

            assertThat(changeSet()).containsEntry("department", null);
            assertThat(changeSet()).containsEntry("reporting_manager_id", null);
        }

        @Test
        @DisplayName("a value is written, trimmed")
        void valueIsWrittenTrimmed() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setDepartment(Optional.of("  Engineering  "));

            service.update(detail(true, 0), request);

            assertThat(changeSet()).containsEntry("department", "Engineering");
        }

        /**
         * The one place where "clear it" and "empty list" are genuinely
         * different values rather than a distinction nobody uses.
         */
        @Test
        @DisplayName("weeklyOff null means inherit the org week; [] means no weekly off at all")
        void weeklyOffNullAndEmptyDiffer() {
            ResourceDtos.ResourceWriteRequest inherit = minimalRequest();
            inherit.setWeeklyOff(Optional.empty());
            service.update(detail(true, 0), inherit);
            assertThat(changeSet()).containsEntry("weekly_off", null);

            ResourceDtos.ResourceWriteRequest none = minimalRequest();
            none.setWeeklyOff(Optional.of(List.of()));
            service.update(detail(true, 0), none);
            assertThat(changeSet().get("weekly_off")).isNotNull();
        }

        @Test
        @DisplayName("weeklyOff is sorted and de-duplicated, so [7,6,6] and [6,7] are one value")
        void weeklyOffIsNormalised() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setWeeklyOff(Optional.of(List.of(7, 6, 6)));

            service.update(detail(true, 0), request);

            verify(writes).writeJson(List.of(6, 7));
        }

        @Test
        @DisplayName("blank skills are dropped and duplicates collapse")
        void skillsAreNormalised() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setSkills(Optional.of(List.of("Java", "  ", "Java", " React ")));

            service.update(detail(true, 0), request);

            verify(writes).writeJson(List.of("Java", "React"));
        }
    }

    // ------------------------------------------------------------------
    // update — the guards
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("update: guards")
    class Guards {

        /**
         * The form must not be a way round the status route's refusal. It is the
         * more discoverable of the three paths that can deactivate somebody, so
         * an unguarded one here would be the one people actually used.
         */
        @Test
        @DisplayName("deactivating somebody holding open tickets is refused, exactly as the status route refuses it")
        void deactivationWithOpenTicketsIsRefused() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setIsActive(Optional.of(false));

            assertThatThrownBy(() -> service.update(detail(true, 3), request))
                    .isInstanceOf(ResourceWriteService.OpenTicketsException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.OpenTicketsException) e).openTicketCount()).isEqualTo(3));

            verify(writes, never()).update(anyLong(), any());
        }

        /**
         * The ordering {@code ResourceService.apply} already establishes: the
         * already-in-that-state check comes first, or somebody deactivated while
         * still holding tickets — what a half-finished reassignment leaves
         * behind — can never have any other field edited again.
         */
        @Test
        @DisplayName("editing an already-inactive resource who still holds tickets is allowed")
        void editingAnAlreadyInactiveResourceIsAllowed() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setIsActive(Optional.of(false));
            request.setDepartment(Optional.of("Engineering"));

            service.update(detail(false, 3), request);

            assertThat(changeSet()).containsEntry("department", "Engineering");
        }

        @Test
        @DisplayName("activating somebody is never blocked — bringing them back cannot orphan anything")
        void activationIsNeverBlocked() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setIsActive(Optional.of(true));

            service.update(detail(false, 9), request);

            assertThat(changeSet()).containsEntry("is_active", true);
        }

        /**
         * Self-reference is a cycle of length one and raises the same exception
         * the deeper ones do — <b>it does not reach the walk</b>, because the
         * answer is knowable without a query and a 409 that costs a round trip
         * to produce is a round trip spent on nothing.
         */
        @Test
        @DisplayName("a resource cannot be made their own reporting manager, and it costs no query")
        void selfReferenceIsRefused() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(USER_ID));

            assertThatThrownBy(() -> service.update(detail(true, 0), request))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);

            verify(writes, never()).managerChain(anyLong(), anyInt());
        }

        /**
         * <b>B-012.</b> This test previously asserted the opposite — that the
         * cycle was accepted — and inverting it is the change this task is.
         */
        @Test
        @DisplayName("B-012: A→B→C→A is refused, and the resource is never written")
        void deeperCyclesAreRefused() {
            // C reports to B reports to A, and A is the resource being edited.
            // Making C A's manager closes the loop.
            long b = USER_ID + 1;
            long c = USER_ID + 2;
            when(writes.managerChain(eq(c), anyInt())).thenReturn(List.of(c, b, USER_ID));

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(c));

            assertThatThrownBy(() -> service.update(detail(true, 0), request))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);

            // Refused before the UPDATE, not rolled back after it. The guard
            // runs while `changes` is still being assembled, so a cycle never
            // reaches the statement at all.
            verify(writes, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("a manager several levels up who does not lead back here is accepted")
        void aDeepChainThatTerminatesIsFine() {
            long candidate = 900L;
            when(writes.managerChain(eq(candidate), anyInt()))
                    .thenReturn(List.of(candidate, 901L, 902L, 903L));

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(candidate));

            service.update(detail(true, 0), request);

            assertThat(changeSet()).containsEntry("reporting_manager_id", candidate);
        }

        /**
         * The case that only exists because this guard is not the only thing
         * that has ever written the column. A chain the walk had to truncate
         * cannot be read as "no cycle found" — it is "no cycle found <i>yet</i>",
         * on precisely the data most likely to have one.
         */
        @Test
        @DisplayName("a chain that fills the depth cap is refused rather than assumed clean")
        void anUnterminatedChainIsRefused() {
            long candidate = 900L;
            List<Long> full = java.util.stream.LongStream
                    .range(0, ResourceWriteService.MAX_MANAGER_CHAIN)
                    .boxed().toList();
            when(writes.managerChain(eq(candidate), anyInt())).thenReturn(full);

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(candidate));

            assertThatThrownBy(() -> service.update(detail(true, 0), request))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);
        }

        /**
         * A create has no id yet, so it cannot be on anybody's chain — but the
         * walk still runs, because the subtree it is joining can already be
         * broken and the manager can already not exist.
         */
        @Test
        @DisplayName("a create walks the chain too, and joining a broken subtree is refused")
        void createRefusesABrokenSubtree() {
            long candidate = 900L;
            when(writes.managerChain(eq(candidate), anyInt())).thenReturn(
                    java.util.stream.LongStream.range(0, ResourceWriteService.MAX_MANAGER_CHAIN)
                            .boxed().toList());

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(candidate));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(ResourceWriteService.ManagerCycleException.class);

            verify(writes, never()).insert(any());
        }

        @Test
        @DisplayName("a reporting manager who does not exist is a 400, not a foreign-key error")
        void unknownManagerIsAValidationError() {
            when(writes.managerChain(eq(77L), anyInt())).thenReturn(List.of());

            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.of(77L));

            assertThatThrownBy(() -> service.update(detail(true, 0), request))
                    .isInstanceOf(ResourceWriteService.ResourceValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ResourceWriteService.ResourceValidationException) e).field())
                            .isEqualTo("reportingManagerId"));
        }

        @Test
        @DisplayName("clearing the manager walks nothing — there is no chain to a null")
        void clearingTheManagerRunsNoWalk() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setReportingManagerId(Optional.empty());

            service.update(detail(true, 0), request);

            assertThat(changeSet()).containsEntry("reporting_manager_id", null);
            verify(writes, never()).managerChain(anyLong(), anyInt());
        }
    }

    // ------------------------------------------------------------------
    // memberships
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("project memberships")
    class Memberships {

        @Test
        @DisplayName("an absent projects key leaves memberships untouched")
        void absentProjectsKeyLeavesMembershipsAlone() {
            service.update(detail(true, 0), minimalRequest());

            verify(writes, never()).replaceMemberships(anyLong(), any());
        }

        @Test
        @DisplayName("an empty projects list removes every membership")
        void emptyListRemovesEveryMembership() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setProjects(Optional.of(List.of()));

            service.update(detail(true, 0), request);

            verify(writes).replaceMemberships(USER_ID, List.of());
        }

        @Test
        @DisplayName("the same project twice keeps the last role, rather than racing itself in statement order")
        void duplicateProjectCollapses() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(5L, "DEVELOPER"),
                    new ResourceDtos.ProjectAssignment(5L, "QA"))));

            service.update(detail(true, 0), request);

            verify(writes).replaceMemberships(USER_ID,
                    List.of(new ResourceDtos.ProjectAssignment(5L, "QA")));
        }

        @Test
        @DisplayName("a project role is upper-cased, so 'qa' from a form matches the CHECK constraint")
        void projectRoleIsUpperCased() {
            ResourceDtos.ResourceWriteRequest request = minimalRequest();
            request.setProjects(Optional.of(List.of(
                    new ResourceDtos.ProjectAssignment(5L, "qa"))));

            service.update(detail(true, 0), request);

            verify(writes).replaceMemberships(USER_ID,
                    List.of(new ResourceDtos.ProjectAssignment(5L, "QA")));
        }
    }

    // ------------------------------------------------------------------
    // the ETag
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the ETag")
    class Etag {

        @Test
        @DisplayName("changes when an editable field changes")
        void movesWithContent() {
            ResourceDtos.ResourceDetail before = detail(true, 0);
            ResourceDtos.ResourceDetail after = withDepartment(before, "Platform");

            assertThat(after.etag()).isNotEqualTo(before.etag());
        }

        /**
         * The three components that move without anybody editing this resource.
         * Any of them inside the tag would invalidate an admin's open form and
         * produce a 412 naming a conflict that does not exist — and the login
         * case is the likeliest of all, because creating somebody and then
         * editing them is exactly when they are being told to log in.
         */
        @Test
        @DisplayName("does not change when only the open-ticket count moves")
        void ignoresOpenTicketCount() {
            assertThat(detail(true, 7).etag()).isEqualTo(detail(true, 0).etag());
        }

        @Test
        @DisplayName("does not change when the resource logs in")
        void ignoresLogin() {
            ResourceDtos.ResourceDetail before = detail(true, 0);
            ResourceDtos.ResourceDetail afterLogin = afterFirstLogin(before);

            assertThat(afterLogin.etag()).isEqualTo(before.etag());
        }

        @Test
        @DisplayName("does not change for a capacity of 8 written as 8.00")
        void ignoresDecimalScale() {
            ResourceDtos.ResourceDetail eight = withCapacity(detail(true, 0), new BigDecimal("8"));
            ResourceDtos.ResourceDetail eightPointZeroZero = withCapacity(detail(true, 0), new BigDecimal("8.00"));

            assertThat(eight.etag()).isEqualTo(eightPointZeroZero.etag());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The change map the last {@code update} actually sent to the repository. */
    private Map<String, Object> changeSet() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LinkedHashMap<String, Object>> captor =
                ArgumentCaptor.forClass((Class<LinkedHashMap<String, Object>>) (Class<?>) LinkedHashMap.class);
        verify(writes, atLeastOnce()).update(eq(USER_ID), captor.capture());
        return captor.getValue();
    }

    /** Only the five required fields — everything else absent, as a real partial PATCH is. */
    private static ResourceDtos.ResourceWriteRequest minimalRequest() {
        ResourceDtos.ResourceWriteRequest request = new ResourceDtos.ResourceWriteRequest();
        request.setDisplayName("Ravi Kumar");
        request.setUsername("ravi.kumar");
        request.setEmail("ravi.kumar@edunext.test");
        request.setEmployeeCode("EMP-0042");
        request.setRole("DEVELOPER");
        return request;
    }

    private static ResourceDtos.ResourceDetail detail(boolean isActive, int openTickets) {
        return new ResourceDtos.ResourceDetail(
                USER_ID, "Ravi Kumar", "DEVELOPER", "ravi.kumar", "ravi.kumar@edunext.test",
                "EMP-0042", "Engineering", "Developer", null, List.of(), List.of(),
                isActive, openTickets, null, null,
                null, null, null, null, "Asia/Kolkata", new BigDecimal("8.00"),
                null, List.of(), List.of(), true);
    }

    private static ResourceDtos.ResourceDetail withDepartment(ResourceDtos.ResourceDetail base, String department) {
        return new ResourceDtos.ResourceDetail(
                base.id(), base.displayName(), base.role(), base.username(), base.email(),
                base.employeeCode(), department, base.designation(), base.reportingManager(),
                base.projectIds(), base.projects(), base.isActive(), base.openTicketCount(),
                base.lastLoginAt(), base.createdAt(), base.mobile(), base.avatarUrl(),
                base.dateOfJoining(), base.location(), base.timezone(), base.dailyCapacityHrs(),
                base.weeklyOff(), base.skills(), base.projectAssignments(), base.mustChangePassword());
    }

    /** What a first login changes, and nothing else. */
    private static ResourceDtos.ResourceDetail afterFirstLogin(ResourceDtos.ResourceDetail base) {
        return new ResourceDtos.ResourceDetail(
                base.id(), base.displayName(), base.role(), base.username(), base.email(),
                base.employeeCode(), base.department(), base.designation(), base.reportingManager(),
                base.projectIds(), base.projects(), base.isActive(), base.openTicketCount(),
                java.time.Instant.parse("2026-08-11T09:00:00Z"), base.createdAt(),
                base.mobile(), base.avatarUrl(), base.dateOfJoining(), base.location(),
                base.timezone(), base.dailyCapacityHrs(), base.weeklyOff(), base.skills(),
                base.projectAssignments(), false);
    }

    private static ResourceDtos.ResourceDetail withCapacity(ResourceDtos.ResourceDetail base, BigDecimal capacity) {
        return new ResourceDtos.ResourceDetail(
                base.id(), base.displayName(), base.role(), base.username(), base.email(),
                base.employeeCode(), base.department(), base.designation(), base.reportingManager(),
                base.projectIds(), base.projects(), base.isActive(), base.openTicketCount(),
                base.lastLoginAt(), base.createdAt(), base.mobile(), base.avatarUrl(),
                base.dateOfJoining(), base.location(), base.timezone(), capacity,
                base.weeklyOff(), base.skills(), base.projectAssignments(), base.mustChangePassword());
    }
}
