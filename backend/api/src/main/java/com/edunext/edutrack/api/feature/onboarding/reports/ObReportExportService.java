package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.edunext.edutrack.api.feature.reports.export.ExportDelivery;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * B-122 · OB-10's exports, written by the engine the ticketing hub already
 * uses.
 *
 * <h2>The one thing the two modules genuinely share</h2>
 *
 * <p>Plan §10 says "exports via existing infra", and {@code runObReport} spells
 * out why that is not the usual cross-module coupling: "it is the one piece of
 * the reports stack the two modules genuinely share, because a workbook writer
 * has no domain in it." Plan §2's boundary is about tables, packages, routes
 * and permission strings; SXSSF's row window and RFC 4180 quoting are none of
 * those.
 *
 * <p>The alternative is what {@code ExportDelivery}'s own javadoc records
 * happening twice already: two features that had rows and columns but not a
 * {@code ReportService.Rendered} went their own way, and the result was three
 * copies of the spreadsheet formula-injection guard — a security control where
 * fixing one copy leaves the others live and nothing says so. A third module
 * writing a fourth would be the same mistake with the excuse already used up.
 *
 * <h2>This class is the translation and nothing else</h2>
 *
 * <p>{@code ExportDelivery} takes {@code List<ReportDtos.Column>} and this
 * module's columns are {@link ObReportDtos.Column}. The mapping is
 * {@link #translate}, and it is the reason the two DTO families can stay apart:
 * a shared column type would have put {@code RAG} into the ticketing contract
 * and {@code TREND} into this one, and each would then be a documented value
 * that no report in that module can produce.
 */
@Service
class ObReportExportService {

    private final ExportDelivery delivery;

    ObReportExportService(ExportDelivery delivery) {
        this.delivery = delivery;
    }

    /**
     * Writes a rendered report onto the response.
     *
     * <p>Called <b>after</b> the runner has produced the rows, on the identical
     * call a JSON request makes. That ordering is the point rather than an
     * implementation detail: an export path that assembled its own query would
     * be a second place for {@link ObReportScope} to be applied, and the one
     * nobody re-checked would be the one that leaked. Here it cannot be
     * skipped, because there is only one {@code run()}.
     */
    void writeTo(HttpServletResponse response, ReportExporter.Format format,
                 String reportKey, ObReportService.Rendered rendered) throws IOException {

        delivery.writeTo(response, format, reportKey,
                ObReportCatalogue.titleFor(reportKey),
                rendered.meta().appliedScope(),
                translate(rendered.report().columns()),
                ExportRows.of(rendered.report().rows()));
    }

    /**
     * This module's columns as the engine's.
     *
     * <p>{@code RAG} becomes {@code STRING}, which is the only mapping that is
     * not one-for-one and is the right one: a health chip is a coloured
     * rendering of a word, the word is what a spreadsheet cell holds, and there
     * is no {@code RAG} in the engine's vocabulary to hold anything else. The
     * value in the row is already {@code "AMBER"}, so the exported file says
     * AMBER and the screen shows an amber chip over the same string.
     *
     * <p>The remaining five names match by construction, and
     * {@code ObReportExportServiceTest} pins that: an added
     * {@link ObReportDtos.ColumnType} with no counterpart fails the test rather
     * than reaching a caller as an exception mid-download, where the status
     * line has already gone and the user gets a truncated file.
     */
    static List<ReportDtos.Column> translate(List<ObReportDtos.Column> columns) {
        return columns.stream()
                .map(column -> new ReportDtos.Column(
                        column.key(), column.label(), engineType(column.type())))
                .toList();
    }

    private static ReportDtos.ColumnType engineType(ObReportDtos.ColumnType type) {
        return switch (type) {
            case STRING, RAG -> ReportDtos.ColumnType.STRING;
            case NUMBER -> ReportDtos.ColumnType.NUMBER;
            case PERCENT -> ReportDtos.ColumnType.PERCENT;
            case DURATION -> ReportDtos.ColumnType.DURATION;
            case DATE -> ReportDtos.ColumnType.DATE;
        };
    }
}
