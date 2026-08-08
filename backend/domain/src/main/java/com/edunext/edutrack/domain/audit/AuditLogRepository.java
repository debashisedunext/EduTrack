package com.edunext.edutrack.domain.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** "What happened to this record" — served by {@code ix_audit_logs_entity}. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /** "What did this user do" — served by {@code ix_audit_logs_actor}. */
    List<AuditLog> findByActorIdOrderByCreatedAtDesc(Long actorId);
}
