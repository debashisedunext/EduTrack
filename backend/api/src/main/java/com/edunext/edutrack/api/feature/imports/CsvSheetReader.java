package com.edunext.edutrack.api.feature.imports;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B-032 · {@code .csv}, which blueprint §4B.3 accepts alongside the workbook.
 *
 * <p><b>Hand-rolled rather than a new dependency.</b> RFC 4180 is one state
 * machine and it is below — quoted fields, doubled quotes inside them, embedded
 * newlines and commas, and the UTF-8 byte-order mark Excel writes and does not
 * mention. Adding a CSV library to the parent POM to avoid sixty lines is a
 * dependency four streams inherit, and this is the whole of what is needed.
 *
 * <h2>A CSV has one sheet, and it is named after the file</h2>
 *
 * <p>The format has no concept of sheets, but the wizard, the staging record and
 * the contract all do, so it reports exactly one — named for the file, because
 * "Sheet1" would be a label with no counterpart anywhere in what the user
 * uploaded. Asking for any other sheet is the same refusal a workbook gives.
 *
 * <h2>Row numbers count records, not lines</h2>
 *
 * <p>A quoted field may contain newlines, so a record can span several lines of
 * the file. The number reported is the record's — which is the row a spreadsheet
 * application shows when it opens the same file, and therefore the number that
 * means something to somebody who goes and looks.
 */
@Component
class CsvSheetReader implements SheetReader {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';
    private static final String BOM = "﻿";

    private final ImportUploadLimits limits;

    CsvSheetReader(ImportUploadLimits limits) {
        this.limits = limits;
    }

    @Override
    public ParsedSheet read(String fileName, byte[] content, String requestedSheet) {
        String sheetName = baseName(fileName);
        List<String> sheets = List.of(sheetName);
        if (requestedSheet != null && !requestedSheet.equals(sheetName)) {
            throw UnreadableImportFileException.unknownSheet(requestedSheet, sheets);
        }

        // UTF-8, and malformed bytes become U+FFFD rather than an exception —
        // which is a deliberate choice, not the default falling through. A CSV
        // exported from an older system may well be Windows-1252, and the damage
        // that does is one visible mojibake character in a Notes cell. Refusing
        // the whole file for it would cost the user every good row in exchange
        // for a byte they cannot see; the bad cell instead reaches step 4's
        // preview, where they can.
        String text = stripBom(new String(content, StandardCharsets.UTF_8));

        Csv csv = new Csv(text);
        List<String> headings = null;
        List<StagedRow> rows = new ArrayList<>();
        int recordNumber = 0;

        while (csv.hasNext()) {
            List<String> fields = csv.nextRecord();
            recordNumber++;

            Map<Integer, String> cells = new TreeMap<>();
            for (int column = 0; column < fields.size(); column++) {
                String value = fields.get(column);
                if (value != null && !value.isBlank()) {
                    cells.put(column, value.trim());
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            if (headings == null) {
                headings = SheetHeadings.from(cells, limits);
                continue;
            }
            if (rows.size() >= limits.maxRows()) {
                throw ImportLimitExceededException.rows(limits.maxRows());
            }
            Map<String, String> values = SheetHeadings.row(cells, headings);
            if (!values.isEmpty()) {
                rows.add(new StagedRow(recordNumber, values));
            }
        }

        if (headings == null || headings.isEmpty()) {
            throw UnreadableImportFileException.noHeaderRow(sheetName);
        }
        return new ParsedSheet(sheets, sheetName, headings, rows);
    }

    private static String stripBom(String text) {
        return text.startsWith(BOM) ? text.substring(1) : text;
    }

    /** {@code clients-april.csv} → {@code clients-april}. */
    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "Sheet1" : base;
    }

    /**
     * RFC 4180, as a cursor over the text.
     *
     * <p>Written as a cursor rather than a split so that a quoted field
     * containing a newline stays one field. Splitting on {@code \n} first is the
     * implementation everyone reaches for and it silently breaks the exact
     * columns most likely to contain one — an address, or the Notes column this
     * schema declares.
     */
    private static final class Csv {

        private final String text;
        private int at;

        private Csv(String text) {
            this.text = text;
        }

        private boolean hasNext() {
            return at < text.length();
        }

        private List<String> nextRecord() {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;

            while (at < text.length()) {
                char c = text.charAt(at++);

                if (quoted) {
                    if (c != QUOTE) {
                        field.append(c);
                    } else if (at < text.length() && text.charAt(at) == QUOTE) {
                        field.append(QUOTE);   // "" inside a quoted field is one "
                        at++;
                    } else {
                        quoted = false;
                    }
                    continue;
                }

                switch (c) {
                    case QUOTE -> quoted = true;
                    case DELIMITER -> {
                        fields.add(field.toString());
                        field.setLength(0);
                    }
                    case '\r' -> {
                        // CRLF is one terminator, not two. A bare CR ends the
                        // record too — old Mac exports still exist.
                        if (at < text.length() && text.charAt(at) == '\n') {
                            at++;
                        }
                        fields.add(field.toString());
                        return fields;
                    }
                    case '\n' -> {
                        fields.add(field.toString());
                        return fields;
                    }
                    default -> field.append(c);
                }
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
