package com.edunext.edutrack.api.feature.masters.priorities;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * B-021 · how much a level is used — the three figures that make retiring one an
 * informed decision rather than one whose size is discovered afterwards.
 *
 * <p>Plain SQL through {@link JdbcClient} for the reason {@code RoleService}
 * gives for counting role holders the same way, {@code SlaMatrixRepository}
 * gives one package over, and {@code TaskTypeUsageRepository} gives for the
 * sibling screen: {@code tickets} is far too large to count in memory, and a
 * grouped projection is not what a {@code JpaRepository} is for.
 *
 * <p><b>Every count keys on the level <em>code</em>, never on
 * {@code priorities.id}.</b> That is not a shortcut — none of the three tables
 * holds the id. {@code tickets.level}, {@code task_types.default_level} and
 * {@code sla_policies.level} are all {@code VARCHAR} columns holding the code,
 * deliberately not foreign keys, which is exactly what lets a level be retired
 * without orphaning anything. A join on {@code id} would compile and return
 * zero.
 *
 * <p><b>This is not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is
 * about dashboards — read constantly, and with pre-aggregated summary tables
 * built for them. This is a four-row Admin screen somebody opens twice a year,
 * and a summary table for it would be a second source of truth to maintain.
 *
 * <p>Its own class rather than three methods on the service, so the service's
 * decisions can be unit-tested without deep-stubbing a fluent builder — the
 * shape {@code SlaMatrixService} and {@code TaskTypeUsageRepository} already
 * take.
 */
@Repository
class PriorityUsageRepository {

    private final JdbcClient jdbc;

    PriorityUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * All three counts for every level, in three grouped statements rather than
     * three per row.
     *
     * <p>Four levels is exactly the size at which an N+1 is invisible, which is
     * how an N+1 survives to a table that is not four rows.
     *
     * <p>Levels nothing references are absent from the maps rather than present
     * as zero; {@link Counts#of} defaults them.
     */
    Counts all() {
        return new Counts(
                groupedLongs("SELECT level, COUNT(*) AS n FROM tickets "
                        + "WHERE level IS NOT NULL GROUP BY level"),
                groupedInts("SELECT default_level AS level, COUNT(*) AS n FROM task_types "
                        + "WHERE default_level IS NOT NULL AND is_active = 1 GROUP BY default_level"),
                groupedInts("SELECT level, COUNT(*) AS n FROM sla_policies "
                        + "WHERE level IS NOT NULL GROUP BY level"));
    }

    /**
     * The single-level form, for the detail read and the two writes.
     *
     * <p>Three statements against three indexes rather than a reuse of
     * {@link #all()}: the grid needs every level and a write needs exactly one,
     * and filtering the whole map in memory would read the ticket table's entire
     * level histogram to answer a question about one row.
     */
    Counts.Row forLevel(String level) {
        return new Counts.Row(
                jdbc.sql("SELECT COUNT(*) FROM tickets WHERE level = ?")
                        .param(level).query(Long.class).single(),
                jdbc.sql("SELECT COUNT(*) FROM task_types WHERE default_level = ? AND is_active = 1")
                        .param(level).query(Integer.class).single(),
                jdbc.sql("SELECT COUNT(*) FROM sla_policies WHERE level = ?")
                        .param(level).query(Integer.class).single());
    }

    /**
     * The task types that would be left defaulting to a retired level, by name.
     *
     * <p>Names and not ids, because this list goes straight into the refusal
     * message and "Production Bug, Client Bug" is actionable where "3, 6" is a
     * second lookup the admin has to do by hand. Capped at five with the count
     * carried separately — a message naming eleven task types is one nobody
     * reads to the end of.
     */
    java.util.List<String> activeTaskTypeNamesDefaultingTo(String level) {
        return jdbc.sql("SELECT name FROM task_types WHERE default_level = ? AND is_active = 1 "
                        + "ORDER BY seq ASC, id ASC LIMIT 5")
                .param(level)
                .query(String.class)
                .list();
    }

    private Map<String, Long> groupedLongs(String sql) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql(sql).query((rs, row) -> counts.put(rs.getString("level"), rs.getLong("n"))).list();
        return counts;
    }

    private Map<String, Integer> groupedInts(String sql) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql(sql).query((rs, row) -> counts.put(rs.getString("level"), rs.getInt("n"))).list();
        return counts;
    }

    /** The three histograms, keyed by level code. */
    record Counts(Map<String, Long> tickets,
                  Map<String, Integer> taskTypes,
                  Map<String, Integer> slaPolicies) {

        Row of(String level) {
            return new Row(
                    tickets.getOrDefault(level, 0L),
                    taskTypes.getOrDefault(level, 0),
                    slaPolicies.getOrDefault(level, 0));
        }

        record Row(long tickets, int taskTypes, int slaPolicies) {
        }
    }
}
