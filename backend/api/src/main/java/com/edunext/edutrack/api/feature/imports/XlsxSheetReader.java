package com.edunext.edutrack.api.feature.imports;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * B-032 · {@code .xlsx}, read through POI's event API.
 *
 * <h2>Why not {@code WorkbookFactory.create(…)}</h2>
 *
 * <p>Because PLAN.md §2.2 and this task's own line say not to, and the reason is
 * concurrency rather than any single file. {@code XSSFWorkbook} materialises
 * every row, cell, style and string of a workbook as objects and holds them for
 * as long as it is open — tens of megabytes for a 5,000-row sheet whose source
 * file is under one. That is survivable once and is not survivable per
 * concurrent import, which is exactly the load a bulk-import screen produces:
 * several administrators, at the same time, at the start of a month.
 *
 * <p>{@link XSSFReader} plus {@link XSSFSheetXMLHandler} streams the sheet XML
 * through SAX instead, so what is retained is what this class chooses to keep —
 * the rows, capped — and nothing else.
 *
 * <h2>Nothing is buffered whole, including the sheet XML</h2>
 *
 * <p>The wanted sheet is parsed <em>from the iterator's own stream</em> rather
 * than read into a byte array first, and the difference is not tidiness. XML
 * compresses by an order of magnitude or more, so a {@code .xlsx} well under the
 * 5 MB byte limit can hold gigabytes of sheet XML — reading it into an array
 * would turn a file that passed every declared check into an out-of-memory
 * error. Streaming it means the ceiling is the row cap, which is enforced while
 * the parse runs.
 *
 * <h2>The row cap stops the parse</h2>
 *
 * <p>{@link ImportLimitExceededException} is thrown from inside the content
 * handler, which unwinds the SAX parse where it stands. Reading to the end and
 * then counting would defeat the point: a sheet with a million rows would be
 * fully parsed, and the refusal would arrive after doing the work the refusal
 * exists to avoid.
 *
 * <h2>Dates come back ISO, whatever the cell is formatted as</h2>
 *
 * <p>See {@code IsoDateFormatter} below. This is the difference between a
 * Contract Start column importing and being rejected row by row.
 */
@Component
class XlsxSheetReader implements SheetReader {

    private final ImportUploadLimits limits;

    XlsxSheetReader(ImportUploadLimits limits) {
        this.limits = limits;
    }

    @Override
    public ParsedSheet read(String fileName, byte[] content, String requestedSheet) {
        OPCPackage pkg = null;
        try {
            pkg = OPCPackage.open(new ByteArrayInputStream(content));
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings strings = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            List<String> sheets = new ArrayList<>();
            String targetName = null;
            Parsed parsed = null;

            // One pass. Every sheet is named for the selector; the wanted one is
            // parsed as it goes past, so its XML is never held.
            XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                try (InputStream sheet = iterator.next()) {
                    String name = iterator.getSheetName();
                    sheets.add(name);

                    boolean wanted = requestedSheet == null
                            ? parsed == null                 // no choice made: the first one
                            : requestedSheet.equals(name);
                    if (wanted && parsed == null) {
                        parsed = parse(sheet, styles, strings, name);
                        targetName = name;
                    }
                }
            }

            if (sheets.isEmpty()) {
                throw UnreadableImportFileException.noSheets();
            }
            if (parsed == null) {
                throw UnreadableImportFileException.unknownSheet(requestedSheet, sheets);
            }
            return new ParsedSheet(sheets, targetName, parsed.headings(), parsed.rows());

        } catch (ImportLimitExceededException | UnreadableImportFileException e) {
            throw e;
        } catch (Exception e) {
            // Anything else means the bytes are not a workbook we can open: a
            // truncated download, a .pdf renamed, an encrypted book. The parser's
            // own message names internal parts and offsets, so it travels as the
            // cause — into the log — and not to the user.
            throw UnreadableImportFileException.corrupt("xlsx", e);
        } finally {
            if (pkg != null) {
                // revert(), not close(). A package opened from a stream is
                // read/write, and close() would try to write it back: there is
                // nothing to save, and doing it anyway is real work on a large
                // book for no result.
                pkg.revert();
            }
        }
    }

    private Parsed parse(InputStream sheetXml, StylesTable styles, SharedStrings strings, String name)
            throws Exception {
        Collector collector = new Collector(name, limits);
        XMLReader parser = XMLHelper.newXMLReader();
        parser.setContentHandler(new XSSFSheetXMLHandler(
                styles, strings, collector, new IsoDateFormatter(), false));
        try {
            parser.parse(new InputSource(sheetXml));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Some SAX drivers wrap a handler's runtime exception rather than
            // letting it through. Without this the row cap would surface as
            // "unreadable file" — a different answer, and the wrong one.
            if (e.getCause() instanceof RuntimeException wrapped) {
                throw wrapped;
            }
            throw e;
        }
        return collector.finish();
    }

    /** Headings plus rows, so {@link Collector} has one thing to hand back. */
    private record Parsed(List<String> headings, List<StagedRow> rows) {
    }

    /**
     * The SAX side — one row at a time, nothing retained but the result.
     *
     * <p>POI reports row numbers 0-based; everything this produces is 1-based,
     * because {@link StagedRow#number()} is what the dry run quotes back to
     * somebody looking at Excel's own gutter.
     */
    private static final class Collector implements SheetContentsHandler {

        private final String sheetName;
        private final ImportUploadLimits limits;

        /** Sorted, so "the last column seen" is meaningful for the null-reference case. */
        private final TreeMap<Integer, String> current = new TreeMap<>();
        private final List<StagedRow> rows = new ArrayList<>();
        private List<String> headings;

        private Collector(String sheetName, ImportUploadLimits limits) {
            this.sheetName = sheetName;
            this.limits = limits;
        }

        @Override
        public void startRow(int rowNum) {
            current.clear();
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue == null || formattedValue.isBlank()) {
                return;
            }
            // A null reference is legal in the wild — some writers omit the r=
            // attribute. Falling back to "the next column along" keeps such a
            // sheet readable instead of losing a whole file to one absent
            // attribute.
            int column = cellReference == null
                    ? (current.isEmpty() ? 0 : current.lastKey() + 1)
                    : new CellReference(cellReference).getCol();
            current.put(column, formattedValue.trim());
        }

        @Override
        public void endRow(int rowNum) {
            if (current.isEmpty()) {
                // Blank rows are dropped, before and after the heading row alike.
                // Dropping them is exactly why StagedRow carries its own number.
                return;
            }
            if (headings == null) {
                headings = SheetHeadings.from(current, limits);
                return;
            }
            if (rows.size() >= limits.maxRows()) {
                throw ImportLimitExceededException.rows(limits.maxRows());
            }
            Map<String, String> values = SheetHeadings.row(current, headings);
            if (!values.isEmpty()) {
                rows.add(new StagedRow(rowNum + 1, values));
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Page headers and footers are print furniture, not data.
        }

        private Parsed finish() {
            if (headings == null || headings.isEmpty()) {
                throw UnreadableImportFileException.noHeaderRow(sheetName);
            }
            return new Parsed(headings, List.copyOf(rows));
        }
    }

    /**
     * A {@link DataFormatter} that renders date cells as ISO-8601.
     *
     * <p>Without this, a Contract Start column formatted {@code dd/MM/yyyy} — the
     * default across most of the world, and what Excel applies the moment a date
     * is typed — arrives as {@code 01/04/2026}, and every row of the file is then
     * rejected at step 4 by {@code FieldValidators.isoDate()}. The user would be
     * looking at a spreadsheet of perfectly good dates being told each one is
     * invalid, with nothing visibly different between their file and the
     * template's own example row.
     *
     * <p>B-031 writes the template's date column as text for the same reason from
     * the other end. This covers the files that did not come from the template —
     * which, for a bulk import, is most of them.
     *
     * <p>Only the date <em>format</em> is intercepted; everything else falls
     * through to POI, so a number stays a number and a text cell is untouched.
     */
    private static final class IsoDateFormatter extends DataFormatter {

        private IsoDateFormatter() {
            // Pinned rather than left to the JVM default: the formatter's locale
            // decides the decimal separator, and a machine set to a comma locale
            // would turn 1.5 into "1,5" and fail a numeric check nobody touched.
            super(Locale.ROOT);
        }

        @Override
        public String formatRawCellContents(
                double value, int formatIndex, String formatString, boolean use1904Windowing) {
            if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                LocalDateTime moment = DateUtil.getLocalDateTime(value, use1904Windowing);
                // A whole number is a date; a fraction carries a time, and
                // dropping it would silently move an appointment to midnight.
                return value == Math.floor(value)
                        ? moment.toLocalDate().toString()
                        : moment.toString();
            }
            return super.formatRawCellContents(value, formatIndex, formatString, use1904Windowing);
        }
    }
}
