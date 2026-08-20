package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-064 · how an export is handed to the browser.
 *
 * <p>The exporters are tested by opening the files they write. What is left,
 * and is easy to get wrong invisibly, is everything around the bytes: the
 * filename somebody sees in their downloads folder, the media type that decides
 * whether the browser offers to open it, and the caching that decides whether
 * tomorrow's click returns today's figures.
 */
@DisplayName("report export service")
class ReportExportServiceTest {

    private static final List<ReportDtos.Column> COLUMNS =
            List.of(new ReportDtos.Column("date", "Date", ReportDtos.ColumnType.DATE));

    private static ReportService.Rendered rendered() {
        return new ReportService.Rendered(
                new ReportDtos.Report("date-wise", COLUMNS,
                        List.<Map<String, Object>>of(Map.of("date", "2026-08-10"))),
                new ReportDtos.RunMeta("your projects"),
                "abc123");
    }

    /** A minimal exporter, so the assertions are about the service and not about a file format. */
    private static ReportExporter stub(ReportExporter.Format format) {
        return new ReportExporter() {
            @Override
            public Format format() {
                return format;
            }

            @Override
            public void write(java.io.OutputStream out, String reportTitle, String appliedScope,
                              List<ReportDtos.Column> columns, ExportRows rows) throws Exception {
                int[] count = {0};
                rows.forEach(row -> count[0]++);
                out.write((reportTitle + "|" + appliedScope + "|" + count[0]).getBytes());
            }
        };
    }

    /*
      B-062 · a real ExportDelivery over stub exporters, rather than a mock of it.
      What this file is about — the catalogue title, the filename, the headers,
      the bytes actually reaching the response — is the seam between the two, and
      a mocked delivery would assert only that a method was called with arguments
      this test itself supplied.
    */
    private final ReportExportService service = new ReportExportService(new ExportDelivery(
            List.of(stub(ReportExporter.Format.CSV), stub(ReportExporter.Format.XLSX))));

    @Test
    @DisplayName("names the file after the report and the day it was taken")
    void filename() {
        // Three files called date-wise.xlsx are indistinguishable a week later;
        // "(2)" tells you the order they arrived and nothing about the contents.
        // B-062 · UTC. This used to read the server's zone, so the same export
        // named itself differently depending on which host answered — and the
        // resource export, which already stamped UTC, disagreed with it.
        assertThat(ReportExportService.filenameFor("date-wise", ReportExporter.Format.XLSX))
                .isEqualTo("date-wise-" + LocalDate.now(ZoneOffset.UTC) + ".xlsx");
    }

    @Test
    @DisplayName("uses the key, not the title, so a slash cannot reach the filename")
    void filenameUsesKey() {
        // "Delayed / SLA Breach" would be quoted correctly by ContentDisposition
        // and would still break a shell script pointed at the folder.
        assertThat(ReportExportService.filenameFor("sla-breach", ReportExporter.Format.CSV))
                .doesNotContain("/")
                .startsWith("sla-breach-");
    }

    private MockHttpServletResponse write(ReportExporter.Format format) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeTo(response, format, "date-wise", rendered());
        return response;
    }

    @Test
    @DisplayName("sends a downloadable attachment with the real media type")
    void headers() throws Exception {
        MockHttpServletResponse response = write(ReportExporter.Format.CSV);

        assertThat(response.getStatus()).isEqualTo(200);
        // Not application/octet-stream, which a browser cannot offer to open.
        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("date-wise-");
    }

    /**
     * The regression that cost a live 500.
     *
     * <p>The first version returned {@code ResponseEntity<StreamingResponseBody>}
     * from a handler declared {@code ResponseEntity<?>}. Spring picks the
     * streaming return-value handler from the <em>declared</em> type, so with
     * the argument erased it never matched and the JSON converter tried to
     * serialise the lambda — 500 "Failed to write request" on all three formats
     * while JSON on the same route kept working. Writing onto the response is
     * what this asserts, and bytes on the response are what proves it.
     */
    @Test
    @DisplayName("writes bytes onto the response rather than returning a body to serialise")
    void bytesReachTheResponse() throws Exception {
        assertThat(write(ReportExporter.Format.CSV).getContentAsByteArray()).isNotEmpty();
    }

    @Test
    @DisplayName("is never cached — a redelivered export would be today's file with yesterday's figures")
    void noStore() throws Exception {
        assertThat(write(ReportExporter.Format.CSV).getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
    }

    @Test
    @DisplayName("passes the applied scope into the file, not just onto the screen")
    void scopeReachesTheFile() throws Exception {
        // The file outlives the screen it came from. A reader who cannot tell a
        // narrowed export from an organisation-wide one reads the smaller
        // number as the whole truth.
        assertThat(write(ReportExporter.Format.CSV).getContentAsString()).contains("your projects");
    }

    @Test
    @DisplayName("resolves the human title from the catalogue, so the file is not headed 'date-wise'")
    void titleComesFromTheCatalogue() throws Exception {
        assertThat(write(ReportExporter.Format.CSV).getContentAsString()).startsWith("Date-wise Report|");
    }

    @Test
    @DisplayName("a format with no registered exporter is a wiring error, not a bad request")
    void missingExporter() {
        // PDF is deliberately not registered on this instance. The caller asked
        // for something the enum declares, so blaming them with a 400 would send
        // somebody hunting for a typo that is not there.
        assertThatThrownBy(() -> write(ReportExporter.Format.PDF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No exporter is registered");
    }
}
