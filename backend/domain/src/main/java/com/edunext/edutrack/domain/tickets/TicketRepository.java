package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} is here for A-034: the {@code ScopeResolver}
 * produces a {@code Specification} per role — Admin unrestricted, PM and
 * Support {@code project_id IN (my projects)}, Developer/QA/Deployment
 * {@code assigned_to = me} — which is composed into every ticket query in one
 * central place (PLAN.md §7).
 *
 * <p><b>No finder here applies a scope of its own.</b> Row scoping is composed
 * by the guard, server-side, and an out-of-scope id must come back as 404 and
 * never 403 — a 403 confirms the ticket exists, which is the existence leak the
 * whole design avoids. A convenience finder that quietly filtered by assignee
 * would become a second, divergent scope rule; that is the top risk in
 * blueprint §17.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** The dominant search by volume — resolves on {@code uq_tickets_code}. */
    Optional<Ticket> findByTicketCode(String ticketCode);

    List<Ticket> findByProjectIdAndStatus(Long projectId, String status);

    List<Ticket> findByAssignedToAndStatus(Long assignedTo, String status);

    /**
     * Take the per-ticket write lock before appending to any of the three
     * hash-chained tables.
     *
     * <p>PLAN.md §3.7: the chain is per-ticket, and two concurrent appends that
     * both read the same tail produce a fork — two rows sharing a
     * {@code prev_hash}, which the nightly verifier reports as tampering when
     * it was really our own race. Serialising on the parent row is what makes
     * the chain linear.
     *
     * <p>All three tables chain off <b>this one</b> lock, so a handoff writing
     * history, effort and a transition together holds one lock rather than
     * three and cannot deadlock against itself.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);
}
