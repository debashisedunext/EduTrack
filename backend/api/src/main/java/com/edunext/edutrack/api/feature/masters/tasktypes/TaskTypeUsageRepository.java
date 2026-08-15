package com.edunext.edutrack.api.feature.masters.tasktypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * B-020 · how much a task type is used — the figure that makes retiring one an
 * informed decision rather than one whose size is discovered afterwards.
 *
 * <p>Plain SQL through {@link JdbcClient} for the reason {@code RoleService}
 * gives for counting role holders the same way, and {@code SlaMatrixRepository}
 * gives one package over: {@code tickets} is far too large to count in memory,
 * and a grouped projection is not what a {@code JpaRepository} is for.
 *
 * <p><b>This is not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is
 * about dashboards — read constantly, and with pre-aggregated summary tables
 * built for them. This is an eleven-row Admin screen, and a summary table for it
 * would be a second source of truth maintained for a page somebody opens twice a
 * year. Both statements read {@code ix_tickets_task_type}.
 *
 * <p>Its own class rather than two methods on the service, so the service's
 * decisions can be unit-tested without deep-stubbing a fluent builder — the
 * shape {@code SlaMatrixService} and {@code SlaMatrixRepository} already take.
 */
@Repository
class TaskTypeUsageRepository {

    private final JdbcClient jdbc;

    TaskTypeUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One grouped statement for the whole grid rather than one per row — eleven
     * task types is exactly the size at which an N+1 is invisible, which is how
     * an N+1 survives to a table that is not eleven rows.
     *
     * <p>Types nothing has been raised against are absent from the map rather
     * than present as zero; the caller defaults them.
     */
    Map<Integer, Long> ticketCountsByTaskType() {
        Map<Integer, Long> counts = new HashMap<>();
        jdbc.sql("SELECT task_type_id, COUNT(*) AS raised FROM tickets "
                        + "WHERE task_type_id IS NOT NULL GROUP BY task_type_id")
                .query((rs, row) -> counts.put(rs.getInt("task_type_id"), rs.getLong("raised")))
                .list();
        return counts;
    }

    /** The single-row form, for the detail read and the two writes. */
    long ticketCount(int taskTypeId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM tickets WHERE task_type_id = ?")
                .param(taskTypeId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }
}
