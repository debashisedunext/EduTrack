package com.edunext.edutrack.api.feature.tickets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * C-011 · allocates the next per-project ticket number. PLAN.md §3.2.
 *
 * <p>The blueprint's §8.2 snippet uses {@code UPDATE … RETURNING}, which MySQL
 * does not have. The MySQL idiom is the connection-local {@code LAST_INSERT_ID(expr)}
 * form: the {@code UPDATE} both increments the counter and stashes the new value
 * on <i>this connection</i>, and the follow-up {@code SELECT} reads it back
 * unaffected by any other session. No {@code SELECT … FOR UPDATE}, no
 * read-then-write race, no application-held lock.
 *
 * <h2>Why not {@code COUNT(*)}</h2>
 *
 * <p>{@code SELECT COUNT(*) + 1 FROM tickets WHERE project_id = ?} is the
 * obvious implementation and it is wrong in two independent ways. Two concurrent
 * creates read the same count and mint the same ID. And once a ticket is ever
 * removed, or a project's tickets are archived, the count walks backwards over
 * IDs that were already issued. Both failures are invisible in single-threaded
 * tests, which is precisely why CLAUDE.md, PLAN.md §3.2 and the contract all say
 * it separately. {@code TicketIdGenerationIT} runs the concurrent case.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than JPA: {@code LAST_INSERT_ID}
 * is a statement-level, connection-scoped side effect that an ORM's flush
 * ordering and first-level cache exist to abstract away. The same reasoning as
 * {@code AuthUserRepository} applies too — this feature owns its own read of the
 * table, so Stream B's Project Master and Stream C's creation path can evolve
 * without editing one shared file.
 *
 * <h2>Why a JPA flush cannot undo this</h2>
 *
 * <p>This class moves {@code ticket_seq} <b>behind Hibernate's back</b> — raw SQL
 * on the transaction's connection, which triggers no flush and which the
 * persistence context never learns about. B-005's {@code domain.identity.Project}
 * maps the same column. So a transaction that loads a Project, dirties any field
 * on it, and allocates a code would, at commit, flush the whole row including the
 * {@code ticket_seq} it read <i>before</i> the increment — reverting the
 * allocation with no error, and making the next ticket reuse the ID.
 *
 * <p>That is the natural way to write C-010, so it is closed at the mapping
 * rather than left to discipline: {@code Project.ticketSeq} is
 * {@code @Column(updatable = false)}, which takes the column out of every UPDATE
 * Hibernate generates. Note that having no setter would <b>not</b> have been
 * enough — Hibernate writes every updatable column of a dirty entity, not only
 * the ones that were touched.
 *
 * <p>Two tests hold it: {@code ProjectTicketSeqMappingTest} fails in seconds if
 * the mapping is reverted, and
 * {@code TicketIdGenerationIT.jpaFlushCannotRevertTheAllocation} reproduces
 * C-010's exact transaction against a real database.
 *
 * <p>The one live consequence for callers: after an allocation, a managed
 * {@code Project} holds a stale {@code ticketSeq}. Read the counter here, never
 * from the entity.
 */
@Repository
class TicketSequenceRepository {

    /**
     * Atomic. InnoDB takes the row's exclusive lock for the duration of the
     * statement, so two sessions arriving together are serialised by the engine
     * and each leaves with a different value — the second waits, then increments
     * whatever the first committed.
     */
    private static final String ALLOCATE = """
            UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?
            """;

    /**
     * Connection-local. This returns what <i>this</i> session's last
     * {@code LAST_INSERT_ID(expr)} stored, never another session's — which is
     * the entire reason the idiom needs no lock. It is also the reason both
     * statements must share one connection; see
     * {@link TicketCodeGenerator#nextTicketCode(long)}.
     */
    private static final String READ_BACK = """
            SELECT LAST_INSERT_ID()
            """;

    private static final String FIND_PROJECT_CODE = """
            SELECT project_code FROM projects WHERE id = ?
            """;

    private final JdbcClient jdbc;

    TicketSequenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Increments {@code projects.ticket_seq} and returns the new value.
     *
     * <p>Rolls back with the caller's transaction, so a ticket insert that fails
     * gives the number back rather than burning it. Gaps are harmless either
     * way — the sequence guarantees uniqueness, not density.
     *
     * @throws UnknownProjectException if no such project row exists. This check
     *                                 is load-bearing, not defensive tidiness:
     *                                 an {@code UPDATE} matching zero rows leaves
     *                                 {@code LAST_INSERT_ID()} holding whatever
     *                                 this connection set previously, so without
     *                                 it a bad project id would silently reissue
     *                                 the number a different project just took.
     */
    long allocateNextSequence(long projectId) {
        int rowsUpdated = jdbc.sql(ALLOCATE).param(projectId).update();
        if (rowsUpdated == 0) {
            throw new UnknownProjectException(projectId);
        }
        return jdbc.sql(READ_BACK).query(Long.class).single();
    }

    /** The ticket-code prefix. Immutable once the project has any ticket — PLAN.md §3.2. */
    Optional<String> findProjectCode(long projectId) {
        return jdbc.sql(FIND_PROJECT_CODE).param(projectId).query(String.class).optional();
    }
}
