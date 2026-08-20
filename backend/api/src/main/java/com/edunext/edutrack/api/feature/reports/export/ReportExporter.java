package com.edunext.edutrack.api.feature.reports.export;

import com.edunext.edutrack.api.feature.reports.ReportDtos;

import java.io.OutputStream;
import java.util.List;

/**
 * A-064 · one report, one file. §7.8: "export to Excel/CSV/PDF".
 *
 * <h2>Written once for all eighteen reports</h2>
 *
 * <p>A-063 made a report "labelled columns over rows" precisely so this could
 * exist once. An exporter never knows which report it is writing — it is handed
 * the same {@code columns} and {@code rows} the JSON response carries, so a
 * report added by A-066 is exportable the day it lands without touching this
 * package.
 *
 * <p>That also means the exported file and the screen cannot disagree. They are
 * the same query through the same scope: {@code ?export=} is handled by calling
 * the runner exactly as a JSON request does, and only the writing differs. An
 * export path that assembled its own query would be a second place for row
 * scoping to be applied, and the one nobody re-checked would be the one that
 * leaked.
 *
 * <h2>Writing to a stream, not returning bytes</h2>
 *
 * <p>Every implementation writes into an {@link OutputStream} rather than
 * returning a {@code byte[]}. A-073's target is 50,000 tickets and a report over
 * a year of them is the largest thing this product hands anybody; buffering it
 * whole is a heap spike per concurrent export, and the two formats that matter
 * for size — CSV and xlsx — can both be written a row at a time.
 *
 * <p><b>B-062 · that is now true of the input as well.</b> Rows arrive as an
 * {@link ExportRows} source rather than a materialised {@code List}, so a caller
 * that pages — {@code /users/export} walks a keyset cursor in batches of 500 —
 * can use this engine instead of writing a second one. It could not before, and
 * the second one it wrote is the reason the formula-injection guard existed in
 * three places.
 */
public interface ReportExporter {

    /** The {@code ?export=} value this writes. */
    Format format();

    /**
     * @param reportTitle for the sheet name, the PDF heading and the filename.
     *                    The key would be enough for a machine; a person opening
     *                    the file a week later needs the title.
     * @param appliedScope what the server narrowed the rows to, in words. Written
     *                     into the file itself, because a spreadsheet outlives the
     *                     screen it came from and "your projects" is not
     *                     recoverable from the rows once it has been emailed on.
     * @param rows         walked once, in order. An implementation must not
     *                     assume it can count them first — see {@link ExportRows}.
     */
    void write(OutputStream out, String reportTitle, String appliedScope,
               List<ReportDtos.Column> columns, ExportRows rows) throws Exception;

    enum Format {

        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        CSV("csv", "text/csv"),
        PDF("pdf", "application/pdf");

        private final String wire;
        private final String contentType;

        Format(String wire, String contentType) {
            this.wire = wire;
            this.contentType = contentType;
        }

        /** The {@code ?export=} value, which is also the file extension. */
        public String wire() {
            return wire;
        }

        /**
         * The real media type, not {@code application/octet-stream}.
         *
         * <p>The contract declares the response as octet-stream, which is
         * correct as a schema — the body is binary and its shape is not
         * describable in OpenAPI. It is the wrong thing to actually send: a
         * browser handed octet-stream cannot offer "open with Excel", and a
         * mail client attaching a forwarded export shows it as an unknown file.
         * The {@code Content-Disposition} filename carries the extension either
         * way, so this costs nothing and is what every consumer reads first.
         */
        public String contentType() {
            return contentType;
        }

        /** Resolves {@code ?export=}, or empty for a value outside the enum. */
        public static java.util.Optional<Format> of(String requested) {
            if (requested == null || requested.isBlank()) {
                return java.util.Optional.empty();
            }
            for (Format f : values()) {
                if (f.wire.equalsIgnoreCase(requested)) {
                    return java.util.Optional.of(f);
                }
            }
            return java.util.Optional.empty();
        }
    }
}
