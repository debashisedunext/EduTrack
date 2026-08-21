package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.domain.journal.ChainDigest;
import com.edunext.edutrack.domain.journal.ChainPayloads;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.reports.ReportDtos.ColumnType.STRING;

/**
 * A-068 · §7.8 report 16, Audit &amp; Compliance — "full immutable trail export
 * for a ticket or date range, including every handoff".
 *
 * <h2>The verdict column is the reason this report is not the audit screen</h2>
 *
 * <p>S-16 (A-071) already lists who did what. What a compliance export adds is
 * the claim that the list has not been altered — and that claim has to be
 * <em>computed</em>, not asserted, or the export is a prettier version of the
 * same trust. Every row here carries the result of recomputing its own digest.
 *
 * <p>The recomputation uses {@link ChainDigest} over {@link ChainPayloads} —
 * the same two classes {@code TicketJournal} used to write the row and the
 * nightly {@code ChainVerifier} uses to check it. Not a reimplementation: a
 * second copy of "which columns are covered" would eventually cover a different
 * set, and the failure mode is a green verdict for a row the digest never
 * actually protected.
 *
 * <h2>What this verdict does and does not catch, stated exactly</h2>
 *
 * <p><b>Catches:</b> any change to a hashed column of a row that is present.
 * Rewrite an {@code old_value}, reattribute an {@code actor_id}, flip
 * {@code is_correction} — the stored {@code row_hash} no longer matches and the
 * row says so.
 *
 * <p><b>Does not catch: a deleted row.</b> This is the property A-042 handed to
 * A-047 and it is worth repeating here rather than leaving to be rediscovered.
 * Each row's digest covers its own fields and its stored {@code prev_hash}; it
 * says nothing about how many rows there should be. A date-ranged export sees a
 * <em>slice</em>, so it cannot even check that consecutive rows link, because
 * the row a slice's first entry points back to usually sits outside the range.
 * Truncation is detected by {@code chain_anchors} and its two triggers, on a
 * whole chain, by the worker — not here.
 *
 * <p>Which is why the verdict is worded as a property of the entry rather than
 * of the trail. {@code VERIFIED} means this entry is unaltered. It does not mean
 * the trail is complete, and a column that implied it would be the single most
 * misleading thing this report could print.
 *
 * <h2>Unhashed rows are {@code NOT CHAINED}, never {@code VERIFIED}</h2>
 *
 * <p>{@code prev_hash} and {@code row_hash} are nullable columns — A-040 added
 * the chain after the table existed, so a row predating it carries neither.
 * Treating a null {@code row_hash} as a pass would mark exactly the oldest and
 * least verifiable rows as the safest ones. They are reported as what they are.
 *
 * <h2>Bounded, and it says so</h2>
 *
 * <p>A year of a busy project is more history than a report can return, and an
 * export silently truncated at some limit reads as the complete trail — which,
 * for the one report whose whole purpose is completeness, is the worst possible
 * failure. The row cap is applied in SQL, and when it is reached the runner says
 * so in a final row rather than stopping quietly. CLAUDE.md's rule about silent
 * caps, on the report where it matters most.
 */
@Component
class AuditComplianceRunner implements ReportRunner {

    static final String KEY = "audit-compliance";

    /**
     * The most entries one run returns.
     *
     * <p>Chosen to be larger than any plausible single-ticket trail and smaller
     * than a year of an organisation, so the ordinary compliance question —
     * "show me everything that happened to this ticket" — is never truncated,
     * while "show me everything, ever" is bounded and told that it was.
     */
    static final int MAX_ENTRIES = 5_000;

    static final String VERIFIED = "Verified";
    static final String ALTERED = "ALTERED";
    static final String NOT_CHAINED = "Not chained";

    private final ComplianceReportRepository compliance;

    AuditComplianceRunner(ComplianceReportRepository compliance) {
        this.compliance = compliance;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ReportScope scope, LocalDate from, LocalDate to, List<Long> projectIds,
                      Long resourceSubject, ReportFilters filters) {
        List<ReportDtos.Column> columns = List.of(
                ReportDtos.Column.linking("ticket", "Ticket", STRING,
                        ReportEntityKind.TICKET, "ticketId"),
                new ReportDtos.Column("project", "Project", STRING),
                new ReportDtos.Column("at", "When", DATE),
                new ReportDtos.Column("actor", "Actor", STRING),
                new ReportDtos.Column("event", "Event", STRING),
                new ReportDtos.Column("field", "Field", STRING),
                new ReportDtos.Column("from", "From", STRING),
                new ReportDtos.Column("to", "To", STRING),
                new ReportDtos.Column("correction", "Correction", STRING),
                new ReportDtos.Column("integrity", "Integrity", STRING));

        List<ComplianceReportRepository.TrailRow> trail = compliance.auditTrail(
                from, to, projectIds, scope.ownWorkOnly(), scope.userId(),
                resourceSubject, MAX_ENTRIES + 1);

        boolean truncated = trail.size() > MAX_ENTRIES;
        if (truncated) {
            trail = trail.subList(0, MAX_ENTRIES);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ComplianceReportRepository.TrailRow r : trail) {
            TicketHistory e = r.entry();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket", r.ticketCode());
            row.put("ticketId", r.ticketId());
            row.put("project", r.projectName());
            row.put("at", r.createdAt());
            row.put("actor", r.actorName());
            row.put("event", e.getEventType());
            row.put("field", e.getFieldName());
            row.put("from", e.getOldValue());
            row.put("to", e.getNewValue());
            // Words rather than a boolean: this column is read by an auditor in
            // an exported spreadsheet, where TRUE in a column headed
            // "Correction" is ambiguous about which way it points.
            row.put("correction", e.isCorrection() ? "Yes" : "");
            row.put("integrity", verdictFor(e));
            rows.add(row);
        }

        if (truncated) {
            rows.add(truncationNotice(columns));
        }

        // No asOf: this is read live from an append-only table, not from
        // anything recomputed on a schedule. A-063's ETag treats null as "do not
        // claim freshness", which is the honest answer for a trail that gains a
        // row the moment somebody touches a ticket.
        return new Result(columns, rows, null);
    }

    /**
     * Recompute this entry's digest and compare it with what is stored.
     *
     * <p>{@code ChainDigest.rowHash(prevHash, payload)} is given the row's own
     * stored {@code prev_hash}, which is what makes a single row checkable in
     * isolation — and is also precisely the limit described in the class note:
     * it proves the row's <em>contents</em> are as written, taking its position
     * in the chain as given.
     */
    private static String verdictFor(TicketHistory entry) {
        String stored = entry.getRowHash();
        if (stored == null || stored.isBlank()) {
            return NOT_CHAINED;
        }
        String recomputed = ChainDigest.rowHash(entry.getPrevHash(), ChainPayloads.of(entry));
        return Objects.equals(stored, recomputed) ? VERIFIED : ALTERED;
    }

    /**
     * The last row when the cap was hit — a visible statement, not a silent stop.
     *
     * <p>Built from the column list rather than from a literal key set so that
     * adding a column later cannot leave this row a field short and render as a
     * ragged final line.
     */
    private static Map<String, Object> truncationNotice(List<ReportDtos.Column> columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ReportDtos.Column c : columns) {
            row.put(c.key(), null);
        }
        row.put("event", "— truncated —");
        row.put("field", "The first " + MAX_ENTRIES + " entries are shown. Narrow the date range "
                + "or filter to one project for a complete trail.");
        row.put("integrity", NOT_CHAINED);
        return row;
    }
}
