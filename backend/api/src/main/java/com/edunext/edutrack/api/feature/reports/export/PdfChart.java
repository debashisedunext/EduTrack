package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * A-064 · the chart in a PDF export, drawn from the rows.
 *
 * <h2>Why it is drawn rather than captured</h2>
 *
 * <p>On screen the chart is Recharts, in the browser. The server has no image
 * of it and no headless renderer, and asking the client to post a rendered PNG
 * back would make every export depend on a browser having been open — which
 * A-065's scheduled emails, sent at 6am by a worker with no client anywhere,
 * could never satisfy.
 *
 * <p>So this is a second implementation of the frontend's derivation, and the
 * fact is worth stating rather than discovering: <b>the first non-numeric column
 * is the category axis and every numeric column is a series</b>, identical to
 * {@code ReportChart.tsx}. If one changes and the other does not, the document
 * and the screen draw different pictures of the same query.
 *
 * <h2>A line chart, whatever the descriptor asked for</h2>
 *
 * <p>The catalogue names four chart types. This draws one. A line over the
 * category axis is honest for every one of them at this size — a donut of
 * eleven task types in a 180-point band is unreadable, and a stacked bar
 * rendered as separate lines still shows each series' shape. The document is a
 * summary; the screen is where the specified visual lives.
 *
 * <p>Drawn with raw content-stream operators rather than a charting library:
 * adding JFreeChart for one line per series would be a second graphics
 * dependency to render something that is four {@code moveTo}/{@code lineTo}
 * calls and an axis.
 */
final class PdfChart {

    private PdfChart() {
    }

    private static final float HEIGHT = 170f;

    /** Blueprint §12.1's chart palette, in order. Never a colour outside it. */
    private static final Color[] SERIES = {
            new Color(0x4F, 0x46, 0xE5),
            new Color(0x06, 0xB6, 0xD4),
            new Color(0x10, 0xB9, 0x81),
            new Color(0xF5, 0x9E, 0x0B),
            new Color(0xEF, 0x44, 0x44),
            new Color(0x8B, 0x5C, 0xF6),
            new Color(0xEC, 0x48, 0x99),
            new Color(0x14, 0xB8, 0xD6),
    };

    private static final Color AXIS = new Color(0xE5, 0xE8, 0xF0);
    private static final Color LABEL = new Color(0x6B, 0x72, 0x80);

    /**
     * Draws into the space below the cursor, then advances the document past it.
     *
     * <p>Does nothing — rather than drawing an empty frame — when there is no
     * numeric column or fewer than two points. A chart of one point is a dot,
     * and a frame with nothing in it reads as a rendering failure.
     */
    static void draw(PdfWriter writer, Document document,
                     List<ReportDtos.Column> columns, List<Map<String, Object>> rows) {

        // B-061 · TREND is absent from this list on purpose, matching
        // ReportChart on screen: a signed change against the previous window is
        // not a series to plot beside an SLA percentage. The allow-list shape is
        // what makes that free — a deny-list would have had to be remembered.
        List<ReportDtos.Column> series = columns.stream()
                .filter(c -> c.type() == ReportDtos.ColumnType.NUMBER
                        || c.type() == ReportDtos.ColumnType.PERCENT
                        || c.type() == ReportDtos.ColumnType.DURATION)
                .toList();

        if (series.isEmpty() || rows.size() < 2) {
            return;
        }

        float left = document.left();
        float right = document.right();
        float top = writer.getVerticalPosition(true);
        float bottom = top - HEIGHT;

        // A chart that would run off the bottom of the page belongs on the next
        // one — half a chart is worse than a page break.
        if (bottom < document.bottom() + 40) {
            document.newPage();
            top = writer.getVerticalPosition(true);
            bottom = top - HEIGHT;
        }

        double max = 0;
        for (Map<String, Object> row : rows) {
            for (ReportDtos.Column column : series) {
                max = Math.max(max, numeric(row.get(column.key())));
            }
        }
        // A flat all-zero series still gets an axis rather than a division by
        // zero — the shape is genuinely flat and the chart should say so.
        if (max <= 0) {
            max = 1;
        }

        PdfContentByte canvas = writer.getDirectContent();
        canvas.saveState();

        canvas.setColorStroke(AXIS);
        canvas.setLineWidth(0.5f);
        canvas.moveTo(left, bottom);
        canvas.lineTo(right, bottom);
        canvas.stroke();

        for (int s = 0; s < series.size(); s++) {
            canvas.setColorStroke(SERIES[s % SERIES.length]);
            canvas.setLineWidth(1.2f);

            for (int i = 0; i < rows.size(); i++) {
                float x = left + (right - left) * i / (float) (rows.size() - 1);
                float y = bottom + (float) (numeric(rows.get(i).get(series.get(s).key())) / max) * (HEIGHT - 14);
                if (i == 0) {
                    canvas.moveTo(x, y);
                } else {
                    canvas.lineTo(x, y);
                }
            }
            canvas.stroke();
        }

        canvas.restoreState();

        // A legend, because without it a reader has coloured lines and no way to
        // tell which is "Closed" and which is "Delayed" — colour alone is never
        // the signal, the same rule the ribbon and the dashboard follow.
        StringBuilder legend = new StringBuilder();
        for (int s = 0; s < series.size(); s++) {
            legend.append(s > 0 ? "   ·   " : "").append(series.get(s).label());
        }

        com.lowagie.text.Paragraph caption = new com.lowagie.text.Paragraph(
                legend.toString(),
                com.lowagie.text.FontFactory.getFont(com.lowagie.text.FontFactory.HELVETICA, 8, LABEL));
        caption.setSpacingBefore(HEIGHT);
        document.add(caption);
    }

    private static double numeric(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
