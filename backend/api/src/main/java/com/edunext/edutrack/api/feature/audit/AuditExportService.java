package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.feature.reports.ReportDtos;
import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A-071 · "Export only" — the export half.
 *
 * <h2>A-064's writers, not new ones</h2>
 *
 * <p>{@code XlsxReportExporter} and {@code CsvReportExporter} are Spring beans
 * behind the public {@link ReportExporter} interface, and they already solve
 * the two things that are easy to get wrong and invisible when you do: POI's
 * streaming workbook, so a large sheet does not build in the heap, and the
 * formula-injection guard that prefixes a leading {@code =}, {@code +},
 * {@code -} or {@code @}. That guard matters more here than on any report — an
 * audit row carries a User-Agent string supplied by whoever made the request,
 * so this is the one export in the product where a cell's contents are
 * attacker-chosen.
 *
 * <p>Reaching into {@code feature/reports} from {@code feature/audit} is a
 * cross-feature dependency and worth naming: both packages are Stream A's, it
 * is on the published interface rather than on an internal, and the alternative
 * is a second XLSX writer — at which point the injection guard exists twice and
 * gets fixed once. {@code ReportExportService} itself could not be reused; it
 * takes a {@code ReportService.Rendered}, which is a report and not a page of
 * audit rows.
 *
 * <h2>PDF is refused, and the contract says so first</h2>
 *
 * <p>{@code /audit-logs} declares {@code export: [xlsx, csv]} while
 * {@code /reports/{key}} declares three. A PDF of an audit extract is a
 * paginated document somebody would reasonably treat as a signed record, and it
 * is nothing of the kind — so the format that invites that reading is the one
 * not offered. The 400 names the two that work.
 */
@Service
class AuditExportService {

    /** Deliberately not {@code Format.values()} — see the class javadoc. */
    private static final List<ReportExporter.Format> OFFERED =
            List.of(ReportExporter.Format.XLSX, ReportExporter.Format.CSV);

    private static final String TITLE = "Audit log";

    /**
     * The login outcomes reachable without a session, where a null actor means
     * "nobody" rather than "a scanner". Mirrors {@code isUnauthenticatedAttempt}
     * in the frontend's auditVocabulary.ts — two copies, in two languages, of a
     * four-element list that only grows when a login outcome is added.
     */
    private static final Set<String> UNAUTHENTICATED = Set.of(
            "LOGIN_FAILED", "LOGIN_THROTTLED", "LOGIN_LOCKED_OUT", "LOGIN_2FA_FAILED");

    /** The sheet's columns, in the order S-16 lists them. */
    private static final List<ReportDtos.Column> COLUMNS = List.of(
            new ReportDtos.Column("createdAt", "When (UTC)", ReportDtos.ColumnType.DATE),
            new ReportDtos.Column("actor", "Who", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("role", "Role", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("action", "Action", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("entityType", "Module", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("entityId", "Record", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("detail", "Detail", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("ipAddress", "IP address", ReportDtos.ColumnType.STRING),
            new ReportDtos.Column("userAgent", "User agent", ReportDtos.ColumnType.STRING));

    private final Map<ReportExporter.Format, ReportExporter> exporters =
            new EnumMap<>(ReportExporter.Format.class);

    AuditExportService(List<ReportExporter> exporters) {
        exporters.forEach(exporter -> this.exporters.put(exporter.format(), exporter));
    }

    /** @return the requested format if this route offers it, empty otherwise. */
    static Optional<ReportExporter.Format> formatOf(String requested) {
        return ReportExporter.Format.of(requested).filter(OFFERED::contains);
    }

    /**
     * Write the file onto the response.
     *
     * <p>Onto the response rather than returned, for the reason
     * {@code ReportController} records at length: a handler that answers both
     * JSON and a file has to declare {@code ResponseEntity<?>}, and Spring
     * picks its streaming writer from the declared type — which, erased, never
     * matches, and produces a 500 on every export while JSON on the same route
     * keeps working.
     */
    void writeTo(HttpServletResponse response, ReportExporter.Format format,
                 List<AuditDtos.Entry> entries, String appliedFilters) throws IOException {
        ReportExporter exporter = exporters.get(format);
        if (exporter == null) {
            // formatOf already rejected anything outside the two offered, so an
            // absent bean is a wiring fault, not a caller fault, and must not
            // read as a bad request.
            throw new IllegalStateException("No exporter is registered for " + format);
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(format.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("audit-log-" + LocalDate.now() + "." + format.wire())
                .build().toString());
        // An export is a snapshot of a table that is being written to while it
        // is read. A cached copy redelivered tomorrow is a file dated today
        // holding yesterday's rows, with nothing on it to say so.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        try {
            exporter.write(response.getOutputStream(), TITLE, appliedFilters, COLUMNS,
                    entries.stream().map(AuditExportService::asRow).toList());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // The status line has already gone, so this cannot become a 500 the
            // client can read — it sees a truncated file either way. Rethrowing
            // at least aborts the response and logs, rather than delivering a
            // short spreadsheet that looks complete.
            throw new IOException("Failed writing the " + format + " audit export", e);
        }
        response.flushBuffer();
    }

    /**
     * The line the exporters put under the title, where a report writes its
     * scope. Here it states the filters and the cap, because a file of exactly
     * {@link AuditService#EXPORT_MAX} rows is otherwise indistinguishable from
     * a complete extract — and somebody will quote it as one.
     */
    static String describe(AuditService.Filters filters, int rowCount) {
        StringBuilder description = new StringBuilder();
        append(description, "actor", filters.actorId() == null ? null : String.valueOf(filters.actorId()));
        append(description, "action", filters.action());
        append(description, "module", filters.entityType());
        append(description, "from", filters.from() == null ? null : filters.from().toString());
        append(description, "to", filters.to() == null ? null : filters.to().toString());
        if (description.isEmpty()) {
            description.append("no filters");
        }
        description.append(" · ").append(rowCount).append(" rows");
        if (rowCount >= AuditService.EXPORT_MAX) {
            description.append(" — TRUNCATED at the ").append(AuditService.EXPORT_MAX)
                    .append("-row export cap. Narrow the date range for the rest.");
        }
        return description.toString();
    }

    private static void append(StringBuilder target, String label, String value) {
        if (value == null) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(label).append(' ').append(value);
    }

    /**
     * One entry as the flat map the exporters take.
     *
     * <p>{@code LinkedHashMap} rather than {@code Map.of} because the values are
     * nullable — most rows have no detail and many have no subject — and
     * {@code Map.of} rejects a null value with an NPE at the first such row,
     * which is every export.
     */
    /**
     * The Who column, and the reason it is not simply the actor's name.
     *
     * <p>A null actor means two different things and the sheet must not conflate
     * them: on a scanner's row it is SYSTEM, and on a failed sign-in it is
     * nobody — the server deliberately never resolved what was typed, so the
     * identifier lives in {@code detail.new} and nowhere else. Writing "System"
     * against a failed login would put a spreadsheet in circulation saying the
     * mail engine tried to sign in as somebody. The screen makes the same
     * distinction, in {@code AuditLogTable}; an export that disagreed with the
     * table above it is the failure the reports README already names.
     */
    static String whoFor(AuditDtos.Entry entry) {
        if (entry.actor() != null) {
            return entry.actor().displayName();
        }
        Object attempted = entry.detail() == null ? null : entry.detail().get("new");
        if (UNAUTHENTICATED.contains(entry.action()) && attempted instanceof String identifier
                && !identifier.isBlank()) {
            return identifier + " (not signed in)";
        }
        return "System";
    }

    private static Map<String, Object> asRow(AuditDtos.Entry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("createdAt", entry.createdAt().toString());
        row.put("actor", whoFor(entry));
        row.put("role", entry.actor() == null ? null : entry.actor().role());
        row.put("action", entry.action());
        row.put("entityType", entry.entityType());
        row.put("entityId", entry.entityId());
        row.put("detail", entry.detail() == null ? null : entry.detail().toString());
        row.put("ipAddress", entry.ipAddress());
        row.put("userAgent", entry.userAgent());
        return row;
    }
}
