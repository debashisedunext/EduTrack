package com.edunext.edutrack.api.feature.masters.resources;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.edunext.edutrack.api.feature.reports.export.ExportRows;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B-062 · the S-07 directory as columns and rows, for the export engine.
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code ResourceExportWriter} — 262 lines that wrote xlsx and CSV directly,
 * with its own SXSSF window, its own byte-order mark, its own RFC-4180 quoting
 * and its own spreadsheet formula guard. Every one of those already existed in
 * {@code feature/reports/export}, correct and tested, and the two copies had
 * already drifted:
 *
 * <ul>
 *   <li><b>Open Tickets was a number in the xlsx and a string in the CSV.</b>
 *       The xlsx path called {@code setCellValue(int)} and the CSV path called
 *       {@code String.valueOf}, and nothing said the two files should agree.
 *       Declared here as {@code NUMBER} once, so both formats read the same
 *       declaration — which is what the column {@code type} on the contract is
 *       for.</li>
 *   <li><b>The CSV quoted every field; the engine quotes only fields that need
 *       it.</b> Both are valid RFC 4180 and the engine's is the readable one.</li>
 *   <li><b>The filename was stamped {@code LocalDate.now(UTC)} here and
 *       {@code LocalDate.now()} — the server's zone — on the report path.</b>
 *       {@link com.edunext.edutrack.api.feature.reports.export.ExportDelivery}
 *       settles on UTC, matching everything else this product writes down.</li>
 * </ul>
 *
 * <p>B-010's reason for writing its own was real and is now gone: the engine
 * demanded a materialised {@code List} and this export streams a keyset cursor
 * in batches of 500. B-062 gave the engine {@link ExportRows}, so it does not.
 *
 * <h2>The export is still every matching row, still streamed</h2>
 *
 * <p>{@link #of} hands the engine a source that drives
 * {@link ResourceService#streamAll} — the same batched cursor as before, with
 * the same {@code ResourceFilter} the grid builds. Nothing is materialised: a
 * five-thousand-person directory costs one batch of rows, not five thousand.
 *
 * <h2>Why a {@code LinkedHashMap} per row</h2>
 *
 * <p>The engine reads a row by column key, so the map's order is not what
 * decides the column order — {@link #COLUMNS} is. It is linked anyway so that a
 * row printed in a test failure reads in the order the file does; an unordered
 * dump of twelve keys is the kind of diff nobody reads twice.
 */
final class ResourceExportRows {

    /**
     * Column order matches the S-07 grid left to right, so the file reads like
     * the screen it came from. Username and designation are included although
     * the grid hides them: an export is what people reconcile against a payroll
     * or directory extract, and both are needed for that and cost nothing here.
     *
     * <p>Carried over verbatim from {@code ResourceExportWriter.HEADERS}, labels
     * included — the file people already reconcile against must not change its
     * headings because the writer behind it did.
     */
    static final List<ReportDtos.Column> COLUMNS = List.of(
            new ReportDtos.Column("employeeCode", "Emp Code", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("displayName", "Name", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("username", "Username", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("email", "Email", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("role", "Role", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("department", "Department", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("designation", "Designation", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("reportingManager", "Reporting Manager", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("projects", "Projects", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("status", "Status", ReportDtos.ColumnType.STRING),
            // NUMBER, and the same NUMBER for both formats. See the class note:
            // this is the column the two hand-written writers disagreed about.
            new ReportDtos.Column("openTicketCount", "Open Tickets", ReportDtos.ColumnType.NUMBER),
            // The ISO instant as a STRING rather than a DATE, deliberately. The
            // xlsx exporter writes DATE cells as their ISO text too — a real
            // Excel serial needs a cell format to be legible and shows as 46251
            // without one — so this states what is actually written.
            new ReportDtos.Column("lastLoginAt", "Last Login (UTC)", ReportDtos.ColumnType.STRING));

    /** The document's own heading, and the sheet name inside the workbook. */
    static final String TITLE = "Resources";

    /** The {@code resources} in {@code resources-2026-08-19.xlsx}. */
    static final String FILENAME_STEM = "resources";

    private ResourceExportRows() {
    }

    /**
     * The filters that produced the file, in words, for the line the engine
     * writes under the title.
     *
     * <p>B-010's writer had nowhere to put this and the export carried none of
     * it: a directory filtered to one project and one manager was a file
     * indistinguishable from the whole organisation, and the reader who received
     * it by email would take 14 rows for the headcount. That is the same failure
     * {@code meta.appliedScope} exists to prevent on the report path, and the
     * reason the engine writes a provenance block at all.
     *
     * <p>Ids rather than names for project and manager. Resolving them means two
     * more queries on a path whose whole point is one streaming pass, and the
     * person who exported it chose them a moment ago from a picker that showed
     * the names. "Project 7" is recoverable; a missing line is not.
     */
    static String describe(ResourceFilter filter) {
        if (filter == null) {
            return "every resource";
        }
        List<String> parts = new java.util.ArrayList<>();
        if (filter.q() != null) {
            parts.add("matching \"" + filter.q() + "\"");
        }
        if (filter.role() != null) {
            parts.add("role " + filter.role());
        }
        if (filter.projectId() != null) {
            parts.add("project " + filter.projectId());
        }
        if (filter.managerId() != null) {
            parts.add("reporting to user " + filter.managerId());
        }
        if (filter.isActive() != null) {
            parts.add(filter.isActive() ? "active only" : "inactive only");
        }
        return parts.isEmpty() ? "every resource" : String.join(", ", parts);
    }

    /** A streaming source over every resource matching {@code filter}. */
    static ExportRows of(ResourceService resources, ResourceFilter filter) {
        return ExportRows.batched(sink ->
                resources.streamAll(filter, batch -> sink.accept(batch.stream()
                        .map(ResourceExportRows::asRow)
                        .toList())));
    }

    /**
     * One resource as a row.
     *
     * <p><b>No formula neutralising here.</b> That is the engine's job and it
     * does it for every cell of every export — doing it again on the way in
     * would double the guard on a department typed {@code -Ops}, which reaches
     * the file as {@code ''-Ops}. The value written down is the value the form
     * holds; rendering it inert belongs to the format that would execute it.
     */
    static Map<String, Object> asRow(ResourceDtos.Resource resource) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employeeCode", resource.employeeCode());
        row.put("displayName", resource.displayName());
        row.put("username", resource.username());
        row.put("email", resource.email());
        row.put("role", resource.role());
        row.put("department", resource.department());
        row.put("designation", resource.designation());
        row.put("reportingManager", managerName(resource));
        row.put("projects", projectNames(resource));
        row.put("status", status(resource));
        // Zero rather than null: "no open tickets" is a measurement this query
        // makes on every row, and an em dash would claim it was not taken.
        row.put("openTicketCount", resource.openTicketCount() == null ? 0 : resource.openTicketCount());
        row.put("lastLoginAt", lastLogin(resource));
        return row;
    }

    private static String managerName(ResourceDtos.Resource resource) {
        return resource.reportingManager() == null ? null : resource.reportingManager().displayName();
    }

    private static String projectNames(ResourceDtos.Resource resource) {
        if (resource.projects() == null || resource.projects().isEmpty()) {
            return null;
        }
        return resource.projects().stream()
                .map(ResourceDtos.ProjectRef::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * The word, not the boolean. A column of {@code TRUE}/{@code FALSE} is
     * ambiguous the moment the header scrolls off — active what?
     */
    private static String status(ResourceDtos.Resource resource) {
        return Boolean.TRUE.equals(resource.isActive()) ? "Active" : "Inactive";
    }

    /**
     * Null rather than an empty string for somebody who has never signed in.
     *
     * <p>The engine leaves a null cell genuinely blank and writes an empty
     * string as an empty string, which in a spreadsheet look identical — but
     * {@code ISBLANK} and a filter's "(Blanks)" entry tell them apart, and "has
     * never logged in" is exactly the row somebody filters for on this sheet.
     */
    private static String lastLogin(ResourceDtos.Resource resource) {
        Instant lastLoginAt = resource.lastLoginAt();
        return lastLoginAt == null ? null : DateTimeFormatter.ISO_INSTANT.format(lastLoginAt);
    }
}
