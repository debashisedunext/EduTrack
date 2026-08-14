package com.edunext.edutrack.worker.journal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A-044 · reads and moves the {@code chain_anchors} row for one chain.
 *
 * <p>{@code JdbcClient} rather than a JPA entity, following {@code SlaRepository}
 * — this table belongs to the worker's own bookkeeping and putting it in the
 * domain object model would make Stream A the author of an entity nobody else
 * has a use for.
 *
 * <p><b>An anchor is only ever moved after a clean verify.</b> Recording one
 * over a chain that just reported a break would file the corruption as the new
 * known-good state, and the next run would compare against it and find nothing
 * wrong. That rule lives in {@link ChainVerifier}, where the verdict is; this
 * class only does what it is told, and the database refuses the rest —
 * {@code trg_chain_anchor_monotonic} rejects a count that decreases, so even a
 * caller that ignored the rule could not lower one.
 */
@Repository
class ChainAnchorRepository {

    private final JdbcClient jdbc;

    ChainAnchorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Anchor(long ticketId, String table, long rowCount, long headRowId, String headRowHash) {
    }

    Optional<Anchor> find(long ticketId, String table) {
        return jdbc.sql("""
                        SELECT ticket_id, table_name, row_count, head_row_id, head_row_hash
                        FROM chain_anchors WHERE ticket_id = ? AND table_name = ?
                        """)
                .params(ticketId, table)
                .query((rs, n) -> new Anchor(
                        rs.getLong("ticket_id"),
                        rs.getString("table_name"),
                        rs.getLong("row_count"),
                        rs.getLong("head_row_id"),
                        rs.getString("head_row_hash")))
                .optional();
    }

    /** Every anchor for one ticket, so a run reads three rows rather than three queries. */
    List<Anchor> findAllFor(long ticketId) {
        return jdbc.sql("""
                        SELECT ticket_id, table_name, row_count, head_row_id, head_row_hash
                        FROM chain_anchors WHERE ticket_id = ?
                        """)
                .param(ticketId)
                .query((rs, n) -> new Anchor(
                        rs.getLong("ticket_id"),
                        rs.getString("table_name"),
                        rs.getLong("row_count"),
                        rs.getLong("head_row_id"),
                        rs.getString("head_row_hash")))
                .list();
    }

    /**
     * Insert the anchor, or move it forward.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} rather than a read-then-branch: two
     * runs never overlap ({@code @SchedulerLock} sees to that), but a statement
     * that cannot race is cheaper to reason about than one that merely does not
     * today. The {@code UPDATE} half is what
     * {@code trg_chain_anchor_monotonic} inspects.
     */
    void anchor(long ticketId, String table, long rowCount, long headRowId,
                String headRowHash, Instant verifiedAt) {
        jdbc.sql("""
                        INSERT INTO chain_anchors
                            (ticket_id, table_name, row_count, head_row_id, head_row_hash, verified_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            row_count     = VALUES(row_count),
                            head_row_id   = VALUES(head_row_id),
                            head_row_hash = VALUES(head_row_hash),
                            verified_at   = VALUES(verified_at)
                        """)
                .params(ticketId, table, rowCount, headRowId, headRowHash, verifiedAt)
                .update();
    }
}
