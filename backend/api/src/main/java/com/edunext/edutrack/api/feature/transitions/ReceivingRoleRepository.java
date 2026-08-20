package com.edunext.edutrack.api.feature.transitions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * C-050 · blueprint §4A.6 — "if the receiving role has no member on the
 * project, the handoff falls to a project-level queue."
 *
 * <p>Plain SQL through {@link JdbcClient} rather than
 * {@code ProjectMemberRepository}: the JPA repository only ever answers "list
 * this project's members", and turning that into an existence check in Java
 * would fetch every membership row on a project — some with dozens — just to
 * throw almost all of them away for a question with a one-row answer.
 */
@Repository
class ReceivingRoleRepository {

    /**
     * {@code COALESCE(pm.role_in_project, r.code)} — A-003's rule that a
     * per-project role override wins where one is set. The same query D-026's
     * Support Desk lookup and chat's "may ask a status" check already run,
     * for the identical reason: a Developer mapped as QA on one project must
     * be found under QA here, not under their global role.
     */
    private static final String HAS_ACTIVE_MEMBER = """
            SELECT EXISTS (
              SELECT 1
                FROM project_members pm
                JOIN users u ON u.id = pm.user_id
                JOIN roles r ON r.id = u.role_id
               WHERE pm.project_id = :projectId
                 AND pm.is_active  = 1
                 AND u.is_active   = 1
                 AND COALESCE(pm.role_in_project, r.code) = :roleCode)
            """;

    private final JdbcClient jdbc;

    ReceivingRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean hasActiveMember(long projectId, String roleCode) {
        return Boolean.TRUE.equals(jdbc.sql(HAS_ACTIVE_MEMBER)
                .param("projectId", projectId)
                .param("roleCode", roleCode)
                .query(Boolean.class)
                .single());
    }
}
