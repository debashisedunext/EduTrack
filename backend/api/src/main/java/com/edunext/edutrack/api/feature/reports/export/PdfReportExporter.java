package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * A-064 · PDF. A document, not a data file.
 *
 * <h2>Why this one truncates and the other two do not</h2>
 *
 * <p>xlsx and CSV are data: every row belongs in them, and a reader who wants
 * the whole set opens one of those. A PDF is what somebody attaches to an email
 * or puts in front of a client, and a faithful paper copy of five thousand rows
 * is two hundred pages nobody reads.
 *
 * <p>So it carries the heading, the filters that produced it, a chart, and the
 * first {@value #MAX_ROWS} rows — and <b>says so on the page</b> when it has
 * truncated. A silently shortened table is a document that looks complete and
 * is not, which is worse than either alternative: a reader quoting a total from
 * it would be quoting the total of an arbitrary prefix.
 *
 * <h2>The chart is drawn here, because the server has never seen one</h2>
 *
 * <p>On screen the chart is Recharts, drawn in the browser from the same rows.
 * The server has no image of it and no headless renderer, so a chart in this
 * document has to be drawn from the data — which is what {@link PdfChart} does,
 * with the identical derivation the frontend uses: the first non-numeric column
 * is the category axis and every numeric column is a series.
 *
 * <p>That is a deliberate second implementation of one rule, so it is worth
 * naming: the two can drift, and if they do, the PDF and the screen show
 * different pictures of the same query. The alternative — the client posting a
 * rendered image back for the server to embed — makes an export depend on a
 * browser having been open, which breaks A-065's scheduled emails entirely.
 */
@Component
class PdfReportExporter implements ReportExporter {

    /**
     * Enough to see the shape of the data and to check a few figures; short
     * enough to stay a document. A reader who needs every row has xlsx.
     */
    static final int MAX_ROWS = 100;

    private static final Color INK = new Color(0x11, 0x18, 0x27);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color RULE = new Color(0xE5, 0xE8, 0xF0);
    private static final Color HEADER_FILL = new Color(0xF1, 0xF3, 0xF9);

    @Override
    public Format format() {
        return Format.PDF;
    }

    @Override
    public void write(OutputStream out, String reportTitle, String appliedScope,
                      List<ReportDtos.Column> columns, List<Map<String, Object>> rows) throws Exception {

        // Landscape: a report is wider than it is tall, and portrait would
        // squeeze a five-column table into half the page it needs.
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            document.add(heading(reportTitle));
            document.add(subheading("Scope: " + (appliedScope == null ? "not stated" : appliedScope)));
            document.add(subheading(rows.size() + (rows.size() == 1 ? " row" : " rows")));

            if (rows.isEmpty()) {
                // An empty report is still a document worth producing — it is
                // evidence that the question was asked and had no answer, which
                // is often exactly what somebody needs to attach.
                document.add(spacer());
                document.add(subheading("No data was recorded for this filter and date range."));
                return;
            }

            document.add(spacer());
            PdfChart.draw(writer, document, columns, rows);

            document.add(spacer());
            document.add(table(columns, rows));

            if (rows.size() > MAX_ROWS) {
                // Stated on the page, not only in the row count above. A reader
                // who scrolls to the end of the table must not be able to
                // mistake it for the whole set.
                document.add(subheading("Showing the first " + MAX_ROWS + " of " + rows.size()
                        + " rows. Export to Excel or CSV for every row."));
            }
        } finally {
            document.close();
        }
    }

    private static PdfPTable table(List<ReportDtos.Column> columns, List<Map<String, Object>> rows) {
        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(100);
        // The header repeats on every page. Without it, page four of a table is
        // a grid of unlabelled numbers.
        table.setHeaderRows(1);

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);
        for (ReportDtos.Column column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), headerFont));
            cell.setBackgroundColor(HEADER_FILL);
            cell.setBorderColor(RULE);
            cell.setPadding(5);
            cell.setHorizontalAlignment(alignment(column.type()));
            table.addCell(cell);
        }

        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 9, INK);
        for (Map<String, Object> row : rows.stream().limit(MAX_ROWS).toList()) {
            for (ReportDtos.Column column : columns) {
                Object value = row.get(column.key());
                // An em dash rather than an empty cell: in a column of figures
                // a blank reads as zero, and "not recorded" is a different claim.
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "—" : String.valueOf(value), bodyFont));
                cell.setBorderColor(RULE);
                cell.setPadding(5);
                cell.setHorizontalAlignment(alignment(column.type()));
                table.addCell(cell);
            }
        }
        return table;
    }

    /** Numbers right, everything else left — the same rule the on-screen table follows. */
    private static int alignment(ReportDtos.ColumnType type) {
        return switch (type) {
            case NUMBER, PERCENT, DURATION -> Element.ALIGN_RIGHT;
            default -> Element.ALIGN_LEFT;
        };
    }

    private static Paragraph heading(String text) {
        return new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, INK));
    }

    private static Paragraph subheading(String text) {
        return new Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED));
    }

    private static Paragraph spacer() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(6);
        return p;
    }
}
