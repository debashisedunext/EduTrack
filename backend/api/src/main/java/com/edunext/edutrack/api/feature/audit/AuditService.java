package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * A-071 · S-16's read side.
 *
 * <h2>There is no scope resolver here, and that is not an omission</h2>
 *
 * <p>Every other list in this product is row-scoped — {@code ScopeResolver}
 * over tickets, {@code DashboardScope} over the summary tables,
 * {@code ReportScope} over the runners — and the reports README already calls
 * the third statement of that rule "one more than is comfortable". This is not
 * a fourth. The audit log has exactly one audience: {@code audit.view} is
 * Admin's alone in blueprint §2, and an Admin's scope is everything. A scope
 * object here would have precisely one branch, and the branch nobody exercises
 * is the one that is wrong when a second role is eventually granted the
 * capability.
 *
 * <p>So the guard is the capability, asserted once on the route, and
 * {@code PermissionMatrix} pins all six roles against it. <b>If a second role
 * is ever given {@code audit.view}, this is the class that has to grow a
 * scope</b> — stated here because that change would otherwise look like a
 * one-line edit to a seed migration.
 *
 * <h2>Unparseable filters are ignored, not rejected</h2>
 *
 * <p>A malformed {@code from} produces the unfiltered page rather than a 400.
 * The client posting these back is a filter bar it rendered itself, and the
 * same argument {@code ReportService} makes for {@code ?resourceId=} applies:
 * a 400 in the middle of typing a date is worse than the list not narrowing
 * yet. The blank-to-null folding below is the same decision one level down —
 * {@code ?action=} from an empty select is "no filter", not "action equal to
 * the empty string", which would match nothing and look like an empty table.
 */
@Service
class AuditService {

    private final AuditQueryRepository audit;

    AuditService(AuditQueryRepository audit) {
        this.audit = audit;
    }

    /** The filters S-16 offers, already normalised. */
    record Filters(Long actorId, String action, String entityType, Instant from, Instant to) {

        static Filters of(Long actorId, String action, String entityType, String from, String to) {
            return new Filters(actorId, blankToNull(action), blankToNull(entityType),
                    instantOrNull(from), instantOrNull(to));
        }
    }

    /**
     * One page, newest first.
     *
     * <p>The fetch asks for {@code limit + 1} and {@link CursorPage#of} decides
     * where the page ends — the fetch-one-extra boundary is written in exactly
     * one place in this codebase and this is not a second one. Getting it wrong
     * here would not throw; it would silently drop the last row of every page,
     * which on an audit log is the failure that looks like nothing happened.
     */
    CursorPage<AuditDtos.Entry> page(Filters filters, String encodedCursor, Integer requestedLimit) {
        int limit = PageLimit.clamp(requestedLimit);
        Cursor cursor = Cursor.decode(encodedCursor);

        List<AuditQueryRepository.Row> rows = audit.page(
                filters.actorId(), filters.action(), filters.entityType(),
                filters.from(), filters.to(),
                cursorAt(cursor), cursor == null ? null : cursor.id(),
                PageLimit.fetchSize(limit));

        return CursorPage.of(rows.stream().map(AuditService::present).toList(), limit,
                entry -> new Cursor(entry.createdAt().toString(), entry.id()));
    }

    /**
     * Every matching row, for an export, capped.
     *
     * <p>A spreadsheet of the whole table is not a useful artefact and is a
     * trivial way to exhaust the heap of the process serving it — POI builds
     * the workbook in memory. So an export is the most recent
     * {@link #EXPORT_MAX} rows matching the filters, and
     * {@link AuditExportService} writes that cap onto the sheet itself rather
     * than leaving somebody to conclude from a round number that they have
     * everything. Narrowing the date range is what gets the rest, which is what
     * the filter bar is for.
     */
    List<AuditDtos.Entry> forExport(Filters filters) {
        return audit.page(filters.actorId(), filters.action(), filters.entityType(),
                        filters.from(), filters.to(), null, null, EXPORT_MAX)
                .stream()
                .map(AuditService::present)
                .toList();
    }

    /**
     * 10,000 rows — about 1.5 MB as .xlsx, comfortably under a request timeout,
     * and more than anybody reads. Not configurable: a property here is an
     * invitation to raise it until the export is the thing that takes the
     * process down, and the honest fix for "I need more" is a narrower range.
     */
    static final int EXPORT_MAX = 10_000;

    /**
     * A stored row as the screen shows it.
     *
     * <p>The three states of an actor are all rendered, and none of them is
     * dropped: SYSTEM (no id), a known user (id and name), and an id whose user
     * row no longer exists. The last one is the reason for the fallback string
     * — {@code LEFT JOIN} is what keeps the audit row alive after the account
     * is removed, and returning a null actor for it would make a deleted
     * account indistinguishable from a scanner.
     */
    private static AuditDtos.Entry present(AuditQueryRepository.Row row) {
        AuditDtos.UserRef actor = row.actorId() == null
                ? null
                : new AuditDtos.UserRef(
                        row.actorId(),
                        row.actorName() == null
                                ? "Deleted user #" + row.actorId()
                                : row.actorName(),
                        row.actorRole());

        return new AuditDtos.Entry(
                row.id(),
                actor,
                row.action(),
                row.entityType(),
                // Whichever of the two the row carries; the contract types this
                // as a string precisely so it does not have to care which.
                row.entityRef() != null ? row.entityRef()
                        : row.entityId() == null ? null : String.valueOf(row.entityId()),
                row.ipAddress(),
                row.userAgent(),
                AuditDtos.detailOf(row.oldValue(), row.newValue()),
                row.createdAt());
    }

    private static Instant cursorAt(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            return Instant.parse(cursor.sortKey());
        } catch (DateTimeParseException tampered) {
            // A cursor is ours and opaque, so an unparseable one is either a
            // client that built its own or a truncated copy-paste. Treated as
            // "no cursor" — the first page — rather than as a 400, because the
            // failure it produces is visible (the list restarts) and the
            // alternative is an error nobody can act on.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * ISO-8601 instants, as {@code contracts/openapi.yaml} declares
     * ({@code format: date-time}). A bare date is accepted too — it is what a
     * date input sends, and refusing it would make the two most obvious values
     * a user can type both wrong.
     */
    private static Instant instantOrNull(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return Instant.parse(trimmed.toUpperCase(Locale.ROOT) + "T00:00:00Z");
            } catch (DateTimeParseException notADateEither) {
                return null;
            }
        }
    }
}
