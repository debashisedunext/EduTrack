package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * D-024 · the per-project escalation matrix.
 *
 * <p>Blueprint §6: {@code sla_policies(project_id, task_type, level,
 * response_hrs, resolution_hrs, escalate_to_l1, escalate_to_l2)}. The two
 * escalation columns are <strong>flags, not recipients</strong> — the schema
 * names who each level means (L1 the reporting manager, L2 the RM's manager),
 * and the matrix only says whether that level applies to this kind of ticket on
 * this project. So a project can decide that a Low-priority change request does
 * not wake anybody's manager, without also having to decide who that manager
 * is.
 */
@Component
class EscalationPolicies {

    /**
     * Most specific first, exactly as A-007's schema comment states:
     * {@code (project, task_type, level) -> (project, level) -> (NULL, level)}.
     *
     * <p>Expressed as an ordering rather than three queries: the {@code CASE}
     * ranks a matching row by how specific it is and takes the best one, which
     * is one round trip and cannot drift out of step with itself the way three
     * sequential lookups can.
     *
     * <p>{@code is_active = 1} throughout — a deactivated policy is not a
     * silent fall-through to a broader one, it is a policy somebody switched
     * off, and the next most specific active row is the right answer.
     */
    private static final String RESOLVE = """
            SELECT escalate_to_l1 AS l1, escalate_to_l2 AS l2
              FROM sla_policies
             WHERE is_active = 1
               AND level = :level
               AND (project_id = :projectId OR project_id IS NULL)
               AND (task_type_id = :taskTypeId OR task_type_id IS NULL)
             ORDER BY CASE
                        WHEN project_id IS NOT NULL AND task_type_id IS NOT NULL THEN 1
                        WHEN project_id IS NOT NULL                              THEN 2
                        ELSE                                                          3
                      END
             LIMIT 1
            """;

    /**
     * What applies when the matrix says nothing at all.
     *
     * <p>Taken from the column defaults A-007 wrote — {@code escalate_to_l1
     * DEFAULT 1}, {@code escalate_to_l2 DEFAULT 0} — rather than invented here.
     * That means <strong>L2 requires configuration</strong>, which is a real
     * decision and worth stating: waking somebody's manager's manager about
     * every overdue ticket, in an organisation that never asked for it, is how
     * escalation email gets filtered into a folder.
     */
    static final Escalation DEFAULT_POLICY = new Escalation(true, false);

    private final JdbcClient jdbc;

    EscalationPolicies(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Escalation forTicket(long projectId, Integer taskTypeId, String level) {
        return jdbc.sql(RESOLVE)
                .param("projectId", projectId)
                .param("taskTypeId", taskTypeId)
                .param("level", level)
                .query(Escalation.class)
                .optional()
                .orElse(DEFAULT_POLICY);
    }

    /**
     * @param l1 escalate to the assignee's reporting manager at breach
     * @param l2 escalate to that manager's manager 48 working hours later
     */
    record Escalation(boolean l1, boolean l2) {
    }
}
