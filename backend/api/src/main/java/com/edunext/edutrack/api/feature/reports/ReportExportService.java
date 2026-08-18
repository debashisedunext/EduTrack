package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A-064 · turns a rendered report into a downloadable file.
 *
 * <p>Separate from {@link ReportService} because that owns "what are the rows"
 * and this owns "how are they written and named" — and because A-065 will hand
 * the same rendered report to the mail engine rather than to a servlet
 * response, so the writing must not be entangled with HTTP.
 */
@Service
class ReportExportService {

    private final Map<ReportExporter.Format, ReportExporter> exporters;

    ReportExportService(List<ReportExporter> exporters) {
        this.exporters = exporters.stream()
                .collect(Collectors.toMap(ReportExporter::format, Function.identity()));
    }

    /**
     * Writes the file straight onto the servlet response.
     *
     * <h2>Why not {@code ResponseEntity<StreamingResponseBody>}</h2>
     *
     * <p>That was the first version and it returned <b>500 "Failed to write
     * request"</b> for all three formats while JSON on the same route kept
     * working. The handler has to answer both a JSON body and a file, so its
     * declared return type is {@code ResponseEntity<?>} — and Spring selects
     * {@code StreamingResponseBodyReturnValueHandler} by inspecting that
     * declared type, not the runtime value. With the type argument erased to
     * {@code ?} it never matched, so the framework fell through to the JSON
     * converter and tried to serialise the lambda.
     *
     * <p>Writing to the response directly sidesteps the question. It also
     * keeps the streaming property that mattered: the exporter is handed the
     * container's own {@code OutputStream}, so nothing larger than SXSSF's row
     * window is ever resident. Buffering into a {@code byte[]} would be a heap
     * spike per concurrent export at A-073's 50,000 tickets — and exports are
     * exactly the request people fire twice when the first feels slow.
     *
     * <p><b>No {@code Content-Length}.</b> It is not knowable without writing
     * the file first, which is the thing being avoided; the response is chunked
     * and a browser shows an indeterminate progress bar. Worth naming, because
     * "the download has no size" reads as a bug to whoever notices it.
     */
    void writeTo(HttpServletResponse response, ReportExporter.Format format,
                 String reportKey, ReportService.Rendered rendered) throws IOException {

        ReportExporter exporter = exporters.get(format);
        if (exporter == null) {
            // Format.of() already rejected anything outside the enum, so this is
            // an enum value with no registered bean — a wiring error, not a
            // caller error, and it must not read as a bad request.
            throw new IllegalStateException("No exporter is registered for " + format);
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(format.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filenameFor(reportKey, format)).build().toString());
        // An export is a snapshot of rows that move. A cached one redelivered
        // tomorrow would be a file dated today holding yesterday's figures,
        // with nothing on it to say so.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        try {
            exporter.write(response.getOutputStream(),
                    titleFor(reportKey),
                    rendered.meta().appliedScope(),
                    rendered.report().columns(),
                    rendered.report().rows());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // Once the status line is sent there is no way to turn a failure
            // here into a 500 the client can read — it sees a truncated file.
            // Rethrowing at least aborts the response and logs, rather than
            // delivering a silently short spreadsheet that looks complete.
            throw new IOException("Failed writing " + format + " export of " + reportKey, e);
        }
        response.flushBuffer();
    }

    /**
     * {@code date-wise-2026-08-17.xlsx}.
     *
     * <p>Dated because these accumulate in a downloads folder, and three files
     * called {@code date-wise.xlsx} are indistinguishable a week later —
     * {@code (2)} in a filename tells you the order they arrived and nothing
     * about what is in them.
     *
     * <p>The key rather than the title: a title contains spaces and, in
     * "Delayed / SLA Breach", a slash. {@code ContentDisposition} would quote
     * it correctly, and it would still be a filename that breaks a shell script
     * somebody points at the folder.
     */
    static String filenameFor(String reportKey, ReportExporter.Format format) {
        return reportKey + "-" + LocalDate.now() + "." + format.wire();
    }

    /**
     * The human title for the file's own heading.
     *
     * <p>Read from the catalogue, so the document says "Date-wise Report"
     * rather than "date-wise". Falls back to the key if the descriptor has gone
     * — the file is still worth producing.
     */
    private static String titleFor(String reportKey) {
        return ReportCatalogue.declared().stream()
                .filter(d -> d.key().equals(reportKey))
                .map(ReportDtos.Descriptor::title)
                .findFirst()
                .orElseGet(() -> reportKey.toUpperCase(Locale.ROOT));
    }
}
