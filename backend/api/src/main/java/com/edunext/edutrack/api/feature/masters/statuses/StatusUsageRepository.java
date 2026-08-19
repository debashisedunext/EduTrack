package com.edunext.edutrack.api.feature.masters.statuses;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * B-039 · how much a status is used — the two figures that make retiring one an
 * informed decision rather than one whose size is discovered afterwards.
 *
 * <p>Plain SQL through {@link JdbcClient} for the reason
 * {@code PriorityUsageRepository} and {@code TaskTypeUsageRepository} give one
 * package over each: {@code tickets} is far too large to count in memory, and a
 * grouped projection is not what a {@code JpaRepository} is for.
 *
 * <p><b>Both counts key on the status <em>code</em>, never on
 * {@code statuses.id}.</b> Neither table holds the id: {@code tickets.status} and
 * {@code workflow_transitions.from_status}/{@code to_status} are all
 * {@code VARCHAR} columns holding the code, deliberately not foreign keys, which
 * is exactly what lets a status be retired without orphaning anything. A join on
 * {@code id} would compile, run, and return zero for every row — and no unit test
 * with a mocked repository could tell.
 *
 * <p><b>This is not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is
 * about dashboards — read constantly, with pre-aggregated summary tables built
 * for them. This is an eight-row Admin screen somebody opens twice a year, and a
 * summary table for it would be a second source of truth to maintain.
 *
 * <p>Its own class rather than two methods on the service, so the service's
 * decisions can be unit-tested without deep-stubbing a fluent builder — the shape
 * {@code SlaMatrixService}, {@code TaskTypeUsageRepository} and
 * {@code PriorityUsageRepository} already take.
 */
@Repository
class StatusUsageRepository {

    private final JdbcClient jdbc;

    StatusUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Both counts for every status, in two grouped statements rather than two per
     * row.
     *
     * <p>Eight rows is exactly the size at which an N+1 is invisible, which is how
     * an N+1 survives to a table that is not eight rows.
     *
     * <p><b>The transition count is a {@code UNION ALL} over both ends, not a
     * count of {@code to_status}.</b> A retire deactivates rows on either side —
     * the moves *into* the status and the moves *out of* it — so counting one end
     * would quote the admin a number smaller than what the button then does. The
     * on-create rows carry a null {@code from_status} and are counted once, at
     * their {@code to_status} end, which is where they belong: they are moves into
     * NEW, and nothing else.
     */
    Counts all() {
        Map<String, Long> tickets = groupedLongs(
                "SELECT status AS code, COUNT(*) AS n FROM tickets "
                        + "WHERE status IS NOT NULL GROUP BY status");

        Map<String, Integer> transitions = groupedInts(
                "SELECT code, SUM(n) AS n FROM ("
                        + "  SELECT from_status AS code, COUNT(*) AS n FROM workflow_transitions "
                        + "   WHERE is_active = 1 AND from_status IS NOT NULL GROUP BY from_status"
                        + "  UNION ALL "
                        + "  SELECT to_status AS code, COUNT(*) AS n FROM workflow_transitions "
                        + "   WHERE is_active = 1 GROUP BY to_status"
                        + ") ends GROUP BY code");

        return new Counts(tickets, transitions);
    }

    /**
     * The single-status form, for the detail read and the two writes.
     *
     * <p>Two statements against two indexes rather than a reuse of {@link #all()}:
     * the grid needs every status and a write needs exactly one, and filtering the
     * whole map in memory would read the ticket table's entire status histogram to
     * answer a question about one row.
     */
    Counts.Row forCode(String code) {
        long tickets = jdbc.sql("SELECT COUNT(*) FROM tickets WHERE status = ?")
                .param(code).query(Long.class).single();

        int transitions = jdbc.sql(
                        "SELECT COUNT(*) FROM workflow_transitions "
                                + "WHERE is_active = 1 AND (from_status = ? OR to_status = ?)")
                .params(code, code).query(Integer.class).single();

        return new Counts.Row(tickets, transitions);
    }

    // ------------------------------------------------------------------

    /** Both helpers alias the key column to {@code code}, so neither knows which table it read. */
    private Map<String, Long> groupedLongs(String sql) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql(sql).query().listOfRows()
                .forEach(row -> out.put((String) row.get("code"),
                        ((Number) row.get("n")).longValue()));
        return out;
    }

    private Map<String, Integer> groupedInts(String sql) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql(sql).query().listOfRows()
                .forEach(row -> out.put((String) row.get("code"),
                        ((Number) row.get("n")).intValue()));
        return out;
    }

    /**
     * Statuses nothing references are absent from the maps rather than present as
     * zero; {@link #of} defaults them. That is not laziness — a {@code GROUP BY}
     * cannot emit a row for a key with no rows, and materialising the missing ones
     * would mean the repository knowing the status list, which is the service's
     * job.
     */
    record Counts(Map<String, Long> tickets, Map<String, Integer> transitions) {

        Row of(String code) {
            return new Row(tickets.getOrDefault(code, 0L), transitions.getOrDefault(code, 0));
        }

        record Row(long tickets, int transitions) {
        }
    }
}
