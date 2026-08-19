package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * B-010 · what the controller decides before delegating — how a query string
 * becomes a filter, and what a download looks like on the wire.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}: everything asserted
 * here is method-level, and {@code MasterRoutesTest} covers the one thing plain
 * construction cannot see, which is where the class is mounted.
 */
class ResourceControllerTest {

    /** B-001's six, as the role master returns them. */
    private static final List<String> SEEDED_ROLE_CODES =
            List.of("ADMIN", "PM", "SUPPORT", "DEVELOPER", "QA", "DEPLOYMENT");

    private ResourceService service;
    private ExportDelivery exports;
    private ResourceWriteService writes;
    private ResourceController controller;

    @BeforeEach
    void setUp() {
        service = mock(ResourceService.class);
        exports = mock(ExportDelivery.class);
        writes = mock(ResourceWriteService.class);
        controller = new ResourceController(service, exports, writes);

        // B-015 replaced the hardcoded Set.of(...) here with the roles table, so
        // the stub stands in for it. The codes are B-001's six because that is
        // what the seed puts there — a seventh an Admin adds is now accepted
        // without touching this class, which is the point of the change.
        //
        // A-037 moved the read behind ResourceService: the controller no longer
        // holds RoleRepository, so the stub moves with it. Same two questions,
        // asked of the service instead of the table.
        when(service.roleExists(any())).thenAnswer(
                call -> SEEDED_ROLE_CODES.contains(call.getArgument(0, String.class)));
        when(service.knownRoleCodes()).thenReturn(SEEDED_ROLE_CODES.stream().sorted().toList());

        when(service.list(any(), any(), any())).thenReturn(
                new ResourceDtos.ResourceListResponse(List.of(),
                        new ResourceDtos.Meta(null, false, 0L)));
    }

    // ------------------------------------------------------------------
    // filters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("query string to filter")
    class Filters {

        @Test
        @DisplayName("every S-07 filter reaches the service")
        void passesEveryFilter() {
            controller.list(null, null, "ravi", "developer", 7L, 9L, false);

            assertThat(capturedFilter()).isEqualTo(
                    new ResourceFilter("ravi", "DEVELOPER", 7L, 9L, false));
        }

        @Test
        @DisplayName("an unset status filter means both, not active")
        void unsetStatusMeansBoth() {
            // Defaulting to active would hide every deactivated resource from
            // the screen whose job includes reactivating them.
            controller.list(null, null, null, null, null, null, null);

            assertThat(capturedFilter().isActive()).isNull();
        }

        @Test
        @DisplayName("a blank search box is not a search for the empty string")
        void blankSearchIsNoSearch() {
            controller.list(null, null, "   ", null, null, null, null);

            assertThat(capturedFilter().q()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"admin", "Admin", "  pm  ", "DEPLOYMENT"})
        @DisplayName("role codes are accepted in any case and normalised upward")
        void roleIsCaseInsensitive(String role) {
            controller.list(null, null, null, role, null, null, null);

            assertThat(capturedFilter().role()).isEqualTo(role.trim().toUpperCase(java.util.Locale.ROOT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"DEV", "SUPPORT_DESK", "administrator"})
        @DisplayName("an unknown role is a 400 naming the known codes, not an empty grid")
        void unknownRoleIsRejected(String role) {
            // SUPPORT_DESK is in the list on purpose: it is the pre-V20260807_1030
            // spelling, so it is the wrong code somebody is most likely to send.
            assertThatThrownBy(() -> controller.list(null, null, null, role, null, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("a role an Admin added through S-09 filters like any other")
        void acceptsARoleAddedAfterTheSeed() {
            // The reason B-015 replaced the compiled Set.of(...): with it, the
            // first custom role would have 400'd on a grid filter and the
            // failure would have looked like a bug in the Role Master.
            when(service.roleExists("AUDITOR")).thenReturn(true);

            controller.list(null, null, null, "auditor", null, null, null);

            assertThat(capturedFilter().role()).isEqualTo("AUDITOR");
        }

        private ResourceFilter capturedFilter() {
            var captor = forClass(ResourceFilter.class);
            verify(service).list(captor.capture(), any(), any());
            return captor.getValue();
        }
    }

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("export")
    class Export {

        private final MockHttpServletResponse response = new MockHttpServletResponse();

        /*
          B-062 · these assert what the controller *asks for*, not what comes out
          of the file. The writing is ExportDelivery's now and is tested where it
          lives, in ReportExporterTest and ExportDeliveryTest; the mapping from a
          resource to a row is ResourceExportRowsTest. Re-asserting the bytes
          here would be a third copy of a guarantee the deleted writer already
          made twice.
        */

        @Test
        @DisplayName("xlsx is delivered under the resources stem, so the filename keeps its shape")
        void xlsxAttachment() throws IOException {
            controller.export("xlsx", null, null, null, null, null, response);

            verify(exports).writeTo(eq(response), eq(ReportExporter.Format.XLSX),
                    eq("resources"), eq("Resources"), any(), eq(ResourceExportRows.COLUMNS), any());
            // The name itself, from the one place that builds it — this is the
            // header B-010's callers already depend on.
            assertThat(ExportDelivery.filenameFor("resources", ReportExporter.Format.XLSX))
                    .startsWith("resources-")
                    .endsWith(".xlsx");
        }

        @Test
        @DisplayName("csv asks for the csv writer, and the format parameter is case-insensitive")
        void csvGoesToTheCsvWriter() throws IOException {
            controller.export("CSV", null, null, null, null, null, response);

            verify(exports).writeTo(any(), eq(ReportExporter.Format.CSV),
                    any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("pdf is refused although the engine can write one, because the contract offers two")
        void pdfIsNotOffered() throws IOException {
            // The engine gained a PDF writer at A-064 and this route has always
            // declared `format: [xlsx, csv]`. Routing through the engine must not
            // quietly widen what the route answers — a format no schema lists is
            // a behaviour no client can discover.
            assertThatThrownBy(() -> controller.export("pdf", null, null, null, null, null, response))
                    .isInstanceOf(ResponseStatusException.class);

            verify(exports, never()).writeTo(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the export is its own route, so listUsers stays a JSON-only operation")
        void exportIsASeparateOperation() throws Exception {
            // Declaring a binary response alongside the JSON one on `listUsers`
            // generates `Blob | UserListResponse`, and every existing caller
            // stops compiling. The split is what keeps that from happening.
            assertThat(ResourceController.class
                    .getDeclaredMethod("export", String.class, String.class, String.class,
                            Long.class, Long.class, Boolean.class,
                            jakarta.servlet.http.HttpServletResponse.class)
                    .getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)
                    .value())
                    .containsExactly("/export");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "pdf", "xls", "json"})
        @DisplayName("an unsupported format is a 400 before a single byte is written")
        void unsupportedFormat(String format) {
            assertThatThrownBy(() -> controller.export(format, null, null, null, null, null, response))
                    .isInstanceOf(ResponseStatusException.class);

            assertThat(response.getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("the export applies the same filters as the grid")
        void exportHonoursFilters() throws IOException {
            controller.export("csv", "ravi", "QA", 7L, 9L, true, response);

            // B-062 · the filter now travels inside the row source rather than
            // as an argument to a writer, so the assertion drains the source and
            // catches it where it is actually used. Verifying the source was
            // *passed* would pass just as well if it queried the whole directory.
            var rows = forClass(ExportRows.class);
            verify(exports).writeTo(any(), any(), any(), any(), any(), any(), rows.capture());
            rows.getValue().forEach(row -> { });

            var captor = forClass(ResourceFilter.class);
            verify(service).streamAll(captor.capture(), any());
            assertThat(captor.getValue()).isEqualTo(new ResourceFilter("ravi", "QA", 7L, 9L, true));
        }

        @Test
        @DisplayName("and states them in the file, which B-010's export could not")
        void exportStatesItsFilters() throws IOException {
            // A directory narrowed to one project and one manager used to be a
            // file indistinguishable from the whole organisation. Whoever it was
            // forwarded to read 14 rows as the headcount.
            controller.export("csv", null, "QA", 7L, null, false, response);

            var scope = forClass(String.class);
            verify(exports).writeTo(any(), any(), any(), any(), scope.capture(), any(), any());
            assertThat(scope.getValue())
                    .contains("role QA")
                    .contains("project 7")
                    .contains("inactive only");
        }

        @Test
        @DisplayName("an unknown role is rejected on the export path too")
        void exportValidatesRole() {
            assertThatThrownBy(() -> controller.export("csv", null, "DEV", null, null, null, response))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    // ------------------------------------------------------------------
    // bulk status
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the bulk response is wrapped in { data }, per CONVENTIONS.md §2")
    void bulkResponseIsWrapped() {
        ResourceDtos.BulkStatusData data =
                new ResourceDtos.BulkStatusData(List.of(), 0, 0, 0, 0, null);
        when(service.setStatus(any())).thenReturn(data);

        ResourceDtos.BulkStatusResponse response = controller.setStatusBulk(
                new ResourceDtos.BulkStatusRequest(List.of(1L), false, null));

        assertThat(response.data()).isSameAs(data);
    }

    // ------------------------------------------------------------------
    // B-011 · the form's routes, and the precondition on the PATCH
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the S-08 form")
    class Form {

        private ResourceDtos.ResourceDetail resource;

        @BeforeEach
        void stubDetail() {
            resource = detail();
            when(writes.detail(7L)).thenReturn(resource);
        }

        @Test
        @DisplayName("the detail read carries the ETag the PATCH requires")
        void detailCarriesAnEtag() {
            var response = controller.get(7L);

            assertThat(response.getHeaders().getETag())
                    .isNotNull()
                    .contains(resource.etag());
        }

        @Test
        @DisplayName("create answers 201 with the resource and the one-time password")
        void createAnswers201WithThePassword() {
            when(writes.create(any())).thenReturn(
                    new ResourceWriteService.Created(resource, "Xk7#mQpz4Rn2Tv9w"));

            var response = controller.create(null, writeRequest());

            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().meta().temporaryPassword()).isEqualTo("Xk7#mQpz4Rn2Tv9w");
            assertThat(response.getBody().data()).isSameAs(resource);
        }

        /**
         * The reason a missing precondition is 428 and not "allowed through":
         * a guard that only applies to callers who opted in protects the set
         * that needed it least.
         */
        @Test
        @DisplayName("a PATCH with no If-Match is refused with 428, not allowed through")
        void patchWithoutIfMatchIsRefused() {
            assertThatThrownBy(() -> controller.update(7L, null, writeRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(428));

            verify(writes, org.mockito.Mockito.never()).update(any(), any());
        }

        @Test
        @DisplayName("a stale If-Match is 412, and nothing is written")
        void staleIfMatchIs412() {
            assertThatThrownBy(() -> controller.update(7L, "\"deadbeef\"", writeRequest()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                            .isEqualTo(412));

            verify(writes, org.mockito.Mockito.never()).update(any(), any());
        }

        @Test
        @DisplayName("a current If-Match is accepted, quoted or bare, and W/ prefixed")
        void currentIfMatchIsAccepted() {
            when(writes.update(any(), any())).thenReturn(resource);

            assertThat(controller.update(7L, resource.etag(), writeRequest())
                    .getStatusCode().value()).isEqualTo(200);
            assertThat(controller.update(7L, "\"" + resource.etag() + "\"", writeRequest())
                    .getStatusCode().value()).isEqualTo(200);
            // Some proxies add the weak-validator prefix on the way through;
            // refusing the edit because of it would be a 412 nobody can act on.
            assertThat(controller.update(7L, "W/\"" + resource.etag() + "\"", writeRequest())
                    .getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("If-Match: * matches anything, per RFC 9110")
        void wildcardIfMatchIsAccepted() {
            when(writes.update(any(), any())).thenReturn(resource);

            assertThat(controller.update(7L, "*", writeRequest()).getStatusCode().value()).isEqualTo(200);
        }

        /**
         * The precondition and the write must reason about the same snapshot,
         * or the deactivation guard can be checked against a version of the row
         * the tag was never compared to.
         */
        @Test
        @DisplayName("the row the precondition checked is the row handed to the write")
        void updateReasonsAboutTheCheckedSnapshot() {
            when(writes.update(any(), any())).thenReturn(resource);

            controller.update(7L, resource.etag(), writeRequest());

            verify(writes).update(org.mockito.ArgumentMatchers.same(resource), any());
        }

        private ResourceDtos.ResourceWriteRequest writeRequest() {
            ResourceDtos.ResourceWriteRequest request = new ResourceDtos.ResourceWriteRequest();
            request.setDisplayName("Ravi Kumar");
            request.setUsername("ravi.kumar");
            request.setEmail("ravi.kumar@edunext.test");
            request.setEmployeeCode("EMP-0042");
            request.setRole("DEVELOPER");
            return request;
        }

        private ResourceDtos.ResourceDetail detail() {
            return new ResourceDtos.ResourceDetail(
                    7L, "Ravi Kumar", "DEVELOPER", "ravi.kumar", "ravi.kumar@edunext.test",
                    "EMP-0042", "Engineering", "Developer", null, List.of(), List.of(),
                    true, 0, null, null, null, null, null, null, "Asia/Kolkata",
                    new java.math.BigDecimal("8.00"), null, List.of(), List.of(), true);
        }
    }

    // ------------------------------------------------------------------
    // B-014 · the singular status route
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("PATCH /users/{userId}/status")
    class SingularStatus {

        /**
         * The defect this task closed, stated as a test.
         *
         * <p>{@code setUserStatus} was in the contract from the first draft, the
         * MSW mock answered it from B-010, and three javadocs in this package
         * described what it refused. No server ever mounted it — a {@code PATCH}
         * to the path met {@code /{userId}}'s sibling mapping and came back 405.
         * Nothing failed, because the operation had no caller until the
         * deactivation flow needed one.
         *
         * <p>{@code MasterRoutesTest} could not have caught it: it asserts where
         * <i>classes</i> are mounted, and this was a missing <i>method</i>. The
         * path is asserted as a literal rather than derived, so a well-meaning
         * rename to {@code /{userId}/active} fails here instead of silently
         * moving an operation the contract still promises.
         */
        @Test
        @DisplayName("is mounted at the path the contract names")
        void isMountedWhereTheContractSaysItIs() throws NoSuchMethodException {
            assertThat(ResourceController.class
                    .getDeclaredMethod("setStatus", long.class, ResourceDtos.StatusRequest.class)
                    .getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class)
                    .path())
                    .containsExactly("/{userId}/status");
        }

        @Test
        @DisplayName("answers 204 with no body")
        void answersNoContent() {
            ResponseEntity<Void> result = controller.setStatus(7L, new ResourceDtos.StatusRequest(false, null));

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(result.getBody()).isNull();
        }

        @Test
        @DisplayName("the path id and the body flag both reach the service")
        void delegatesBothArguments() {
            controller.setStatus(7L, new ResourceDtos.StatusRequest(true, "back from sabbatical"));

            verify(service).setStatus(7L, true);
        }

        /**
         * Not a placeholder — a boundary somebody will otherwise cross by
         * accident.
         *
         * <p>{@code reason} is on the contract and is not persisted anywhere
         * yet: {@code audit_logs} has no writer in this repository and
         * {@code actor_id} wants a principal {@code dev-noauth} does not supply.
         * The risk is that a reader assumes the field is recorded because it is
         * accepted. This asserts the service is never told about it, so the day
         * somebody wires it up they change a test that says why it was not.
         */
        @Test
        @DisplayName("reason is accepted and goes nowhere — deliberately, until A-016")
        void reasonIsNotYetRecorded() {
            controller.setStatus(7L, new ResourceDtos.StatusRequest(false, "left the organisation"));

            verify(service).setStatus(7L, false);
            verifyNoMoreInteractions(service);
        }
    }
}
