package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B-062 · every download in the product leaves through here.
 *
 * <h2>What this is, and why it is not {@code ReportExportService}</h2>
 *
 * <p>A-064 solved the whole problem — pick the exporter, name the file, set the
 * headers, stream onto the response, fail without delivering a truncated file —
 * and solved it inside a package-private service whose one method takes a
 * {@code ReportService.Rendered}. So a feature that had rows and columns but not
 * a rendered <em>report</em> could not call it.
 *
 * <p>Both features that tried went their own way. {@code AuditExportService}
 * reused the writers and rewrote the delivery. {@code ResourceExportWriter}
 * rewrote both, and its 262 lines carried a third copy of the SXSSF window, the
 * byte-order mark, the RFC-4180 quoting and — the one that matters — the
 * spreadsheet formula-injection guard. Three copies of a security control is the
 * arrangement where fixing it once leaves two live, and nothing fails to say so.
 *
 * <p>So the seam is a file's worth of columns and rows rather than a report:
 * that is all any of the three ever had.
 *
 * <h2>Writing onto the response, not returning a body</h2>
 *
 * <p>Carried over from A-064 unchanged, including the reason.
 * {@code ResponseEntity<StreamingResponseBody>} was the first version and it
 * returned <b>500 "Failed to write request"</b> for all three formats while JSON
 * on the same route kept working: a handler that answers both a JSON body and a
 * file declares {@code ResponseEntity<?>}, and Spring selects the streaming
 * handler from the <em>declared</em> type, which with the argument erased never
 * matched. It fell through to the JSON converter and tried to serialise the
 * lambda.
 *
 * <p>Writing directly sidesteps the question and keeps the property that
 * mattered: the exporter is handed the container's own {@code OutputStream}, so
 * nothing larger than the row window is ever resident.
 *
 * <p><b>No {@code Content-Length}.</b> It is not knowable without writing the
 * file first, which is the thing being avoided; the response is chunked and a
 * browser shows an indeterminate progress bar. Worth naming, because "the
 * download has no size" reads as a bug to whoever notices it.
 */
@Service
public class ExportDelivery {

    private final Map<ReportExporter.Format, ReportExporter> exporters;

    /**
     * Public so a feature in another package can construct one in a test against
     * the real exporters. The exporters themselves stay package-private
     * {@code @Component}s — {@link ReportExporter} is the published surface, and
     * that is the whole reason this class can be shared at all.
     */
    public ExportDelivery(List<ReportExporter> exporters) {
        this.exporters = exporters.stream()
                .collect(Collectors.toMap(ReportExporter::format, Function.identity()));
    }

    /**
     * One file, onto the response.
     *
     * @param filenameStem the {@code resources} in {@code resources-2026-08-19.xlsx}.
     *                     A key or a slug, never a title — see {@link #filenameFor}.
     * @param title        the document's own heading and the sheet name. A person
     *                     opening the file a week later needs it; a machine does not.
     * @param appliedScope what the server narrowed the rows to, in words, written
     *                     into the file. A spreadsheet outlives the screen it came
     *                     from, and "your projects" is not recoverable from the
     *                     rows once it has been forwarded.
     */
    public void writeTo(HttpServletResponse response, ReportExporter.Format format,
                        String filenameStem, String title, String appliedScope,
                        List<ReportDtos.Column> columns, ExportRows rows) throws IOException {

        ReportExporter exporter = exporterFor(format);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(format.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filenameFor(filenameStem, format)).build().toString());
        // An export is a snapshot of rows that move. A cached one redelivered
        // tomorrow would be a file dated today holding yesterday's figures,
        // with nothing on it to say so.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        write(exporter, response.getOutputStream(), format, filenameStem, title,
                appliedScope, columns, rows);
        response.flushBuffer();
    }

    /**
     * The same file, in memory, for a run nobody is waiting on.
     *
     * <h2>Buffered here and streamed above, on purpose</h2>
     *
     * <p>{@link #writeTo} goes to lengths to avoid a {@code byte[]}, because an
     * interactive export is a request somebody fires twice when the first feels
     * slow, and a heap spike per concurrent export is the failure A-073 is
     * measured on. None of that applies to a scheduled run: it happens once per
     * schedule per day, on a worker thread with no client attached, and the
     * bytes have to exist as a whole anyway — an object store {@code PutObject}
     * needs a length, and the alternative is a multipart upload for a
     * spreadsheet.
     *
     * <p><b>B-062 · A-065 wrote this reasoning and its own copy of the four
     * lines under it.</b> Looking up the exporter, writing, and rethrowing a
     * non-{@code IOException} as one existed three times by then — here, on the
     * response path beside it, and in {@code feature/audit}. What matters is
     * unchanged and is the reason both paths live in one class: it is the
     * <em>same</em> exporter over the <em>same</em> columns and rows. A second
     * writer for the mail path would be a second place for the applied-scope
     * header to be written differently, and the file people keep would be the
     * one nobody re-checked.
     */
    public byte[] toBytes(ReportExporter.Format format, String subject, String title,
                          String appliedScope, List<ReportDtos.Column> columns,
                          ExportRows rows) throws IOException {

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        write(exporterFor(format), buffer, format, subject, title, appliedScope, columns, rows);
        return buffer.toByteArray();
    }

    /**
     * The exporter for a format, or a wiring error.
     *
     * <p>{@code Format.of()} already rejected anything outside the enum, so an
     * absent bean is not a caller fault and must not read as a bad request.
     */
    private ReportExporter exporterFor(ReportExporter.Format format) {
        ReportExporter exporter = exporters.get(format);
        if (exporter == null) {
            throw new IllegalStateException("No exporter is registered for " + format);
        }
        return exporter;
    }

    /**
     * The write and its one translation, in the single place both paths reach.
     *
     * @param subject what the file is of, for the failure message — a report key
     *                or an export's stem. Only ever read by a human reading a log.
     */
    private static void write(ReportExporter exporter, java.io.OutputStream out,
                              ReportExporter.Format format, String subject, String title,
                              String appliedScope, List<ReportDtos.Column> columns,
                              ExportRows rows) throws IOException {
        try {
            exporter.write(out, title, appliedScope, columns, rows);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // On the response path the status line has already gone, so this
            // cannot become a 500 the client can read — it sees a truncated
            // file. Rethrowing at least aborts the response and logs, rather
            // than delivering a silently short spreadsheet that looks complete.
            throw new IOException("Failed writing " + format + " export of " + subject, e);
        }
    }

    /**
     * {@code date-wise-2026-08-17.xlsx}.
     *
     * <p>Dated because these accumulate in a downloads folder, and three files
     * called {@code date-wise.xlsx} are indistinguishable a week later —
     * {@code (2)} in a filename tells you the order they arrived and nothing
     * about what is in them.
     *
     * <p>A stem rather than the title: a title contains spaces and, in
     * "Delayed / SLA Breach", a slash. {@code ContentDisposition} would quote it
     * correctly, and it would still be a filename that breaks a shell script
     * somebody points at the folder.
     *
     * <p><b>UTC</b>, matching every other date this product writes down.
     * {@code LocalDate.now()} — which is what the report path used and the
     * resource path did not — reads the server's zone, so the same export names
     * itself differently depending on which host answered.
     */
    public static String filenameFor(String stem, ReportExporter.Format format) {
        return stem + "-" + LocalDate.now(ZoneOffset.UTC) + "." + format.wire();
    }
}
