package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

/**
 * A-064 · turns a rendered report into a downloadable file.
 *
 * <p>Separate from {@link ReportService} because that owns "what are the rows"
 * and this owns "how are they written and named" — and because A-065 hands the
 * same rendered report to the mail engine rather than to a servlet response, so
 * the writing must not be entangled with HTTP.
 *
 * <h2>B-062 · what is left here, and what moved</h2>
 *
 * <p>Everything generic — choosing the exporter, the dated filename, the
 * headers, {@code no-store}, buffering for the scheduled path, and failing
 * without delivering a truncated file — is {@link ExportDelivery} now, because
 * two other features needed it and only one of them could reach it. What stays
 * is the single thing that is genuinely about <em>reports</em>: turning a
 * {@code reportKey} into the title the catalogue gives it, and a
 * {@code Rendered} into columns and rows.
 *
 * <p>That adapter is why the class survives rather than being deleted for its
 * two callers' sake — {@link ReportController} and {@code ScheduledReportRunner}
 * both hold a {@code Rendered}, and neither should have to know what a
 * {@code ReportCatalogue} descriptor is in order to write a file.
 */
@Service
class ReportExportService {

    private final ExportDelivery delivery;

    ReportExportService(ExportDelivery delivery) {
        this.delivery = delivery;
    }

    /** Writes the rendered report onto the response, in {@code format}. */
    void writeTo(HttpServletResponse response, ReportExporter.Format format,
                 String reportKey, ReportService.Rendered rendered) throws IOException {

        delivery.writeTo(response, format, reportKey, titleFor(reportKey),
                rendered.meta().appliedScope(), rendered.report().columns(), rowsOf(rendered));
    }

    /**
     * A-065 · the same file, in memory, for a run nobody is waiting on.
     *
     * <p>The interactive path streams and this one buffers, deliberately — the
     * reasoning is on {@link ExportDelivery#toBytes}, along with why both live
     * in one class rather than as two writers that agree today.
     */
    byte[] toBytes(ReportExporter.Format format, String reportKey, ReportService.Rendered rendered)
            throws IOException {

        return delivery.toBytes(format, reportKey, titleFor(reportKey),
                rendered.meta().appliedScope(), rendered.report().columns(), rowsOf(rendered));
    }

    /**
     * A rendered report's rows as a source.
     *
     * <p>Every runner materialises its rows — a report is bounded by a date
     * range and a scope, and the thirteen that run aggregate in SQL and return
     * tens of rows. The streaming source exists for the callers that page, not
     * to make these pretend to.
     */
    private static ExportRows rowsOf(ReportService.Rendered rendered) {
        return ExportRows.of(rendered.report().rows());
    }

    /** @see ExportDelivery#filenameFor */
    static String filenameFor(String reportKey, ReportExporter.Format format) {
        return ExportDelivery.filenameFor(reportKey, format);
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
