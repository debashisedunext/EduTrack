package com.edunext.edutrack.api.feature.masters.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private ResourceService service;
    private ResourceExportWriter exporter;
    private ResourceController controller;

    @BeforeEach
    void setUp() {
        service = mock(ResourceService.class);
        exporter = mock(ResourceExportWriter.class);
        controller = new ResourceController(service, exporter);

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
        @DisplayName("an unknown role is a 400 naming the six, not an empty grid")
        void unknownRoleIsRejected(String role) {
            // SUPPORT_DESK is in the list on purpose: it is the pre-V20260807_1030
            // spelling, so it is the wrong code somebody is most likely to send.
            assertThatThrownBy(() -> controller.list(null, null, null, role, null, null, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
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

        @Test
        @DisplayName("xlsx is offered as an attachment with the right extension")
        void xlsxAttachment() throws IOException {
            controller.export("xlsx", null, null, null, null, null, response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .startsWith("attachment; filename=\"resources-")
                    .endsWith(".xlsx\"");
            verify(exporter).writeXlsx(any(), any());
        }

        @Test
        @DisplayName("csv goes to the csv writer, not the spreadsheet one")
        void csvGoesToTheCsvWriter() throws IOException {
            controller.export("CSV", null, null, null, null, null, response);

            verify(exporter).writeCsv(any(), any());
            verify(exporter, never()).writeXlsx(any(), any());
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

            var captor = forClass(ResourceFilter.class);
            verify(exporter).writeCsv(captor.capture(), any());
            assertThat(captor.getValue()).isEqualTo(new ResourceFilter("ravi", "QA", 7L, 9L, true));
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
}
