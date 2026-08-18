package com.edunext.edutrack.domain.audit;

import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Reads over {@code audit_logs}. Finders only.
 *
 * <h2>Why this extends {@code Repository} and not {@code JpaRepository}</h2>
 *
 * <p>A-071 · it used to extend {@code JpaRepository}, which meant it inherited
 * {@code save}, {@code saveAll}, {@code delete}, {@code deleteById},
 * {@code deleteAll} and {@code deleteAllInBatch} on a table the product
 * describes as "export only, never editable". Nothing called them — but the
 * guarantee CLAUDE.md states is "no service method may expose {@code update()}
 * or {@code delete()}", and a bean whose autocomplete offers
 * {@code deleteAllInBatch} does not meet it. The first person to need a fix in a
 * hurry finds the method already there and correctly concludes it is available.
 *
 * <p>{@code Repository} is Spring Data's marker interface: the declared methods
 * below are still derived and implemented, and nothing else is. Writes go
 * through {@link AuditTrail#record}, which is the only insert path and has no
 * counterpart.
 *
 * <p>The viewer does not use this — S-16 filters on five dimensions and pages by
 * keyset, which is {@code feature/audit/AuditQueryRepository} and a hand-written
 * query. These two finders are the "what happened to this record" and "what did
 * this user do" reads named when the table was created, kept because they are
 * the shape a Ticket Activity tab (§4A.7) and a Resource 360 will want.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /** "What happened to this record" — served by {@code ix_audit_logs_entity}. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /** "What did this user do" — served by {@code ix_audit_logs_actor}. */
    List<AuditLog> findByActorIdOrderByCreatedAtDesc(Long actorId);
}
