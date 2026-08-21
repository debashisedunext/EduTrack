package com.edunext.edutrack.api.feature.masters.templates;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B-041 · the figures tab 3 needs about rows it does not own — how much each
 * template is used, and what the two ends of a routing rule are called.
 *
 * <p>Plain SQL through {@link JdbcClient}, the call {@code StageUsageRepository}
 * and {@code StatusUsageRepository} both made and for the same two reasons:
 * {@code tickets} is far too large to count in memory, and a grouped projection
 * across four tables is not what a {@code JpaRepository} is for.
 *
 * <p><b>Not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is about
 * dashboards, which are read constantly and have pre-aggregated summary tables
 * built for them. This is an Admin screen opened a handful of times a year, and a
 * summary table for it would be a second source of truth to keep current.
 */
@Repository
class TemplateUsageRepository {

    private final JdbcClient jdbc;

    TemplateUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * How many stages each template has — one statement rather than one per
     * template.
     *
     * <p>Every stage, deprecated included. The count is the length of the ribbon
     * a historical ticket renders, and a retired stage still renders on every
     * ribbon it is already on (B-042). A "live stages" count would be a different
     * number answering a different question, and tab 2 is where that question is
     * asked.
     */
    Map<Long, Integer> stageCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT template_id AS id, COUNT(*) AS n FROM workflow_stages GROUP BY template_id")
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getInt("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /**
     * How many routing rules point at each template.
     *
     * <p>The number behind {@code isDeactivatable}. A template nothing routes to
     * may be switched off freely; one that three rules name cannot be, because
     * the next ticket on any of those three pairs would resolve to a template the
     * master says is not in use.
     */
    Map<Long, Long> mappingCounts() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT template_id AS id, COUNT(*) AS n
                          FROM workflow_template_mappings
                         GROUP BY template_id
                        """)
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /**
     * How many tickets ever started on each template.
     *
     * <p>The delete's entire guard. <b>Every ticket, not only open ones</b>, and
     * the distinction is the whole point: a closed ticket still renders its
     * ribbon on its detail page and in C-055's Journey grid, and deleting the
     * template would cascade away the {@code workflow_stages} rows those segments
     * resolve their names, icons and owner roles through. Counting only open
     * tickets would offer a delete on a template with four years of history behind
     * it.
     *
     * <p>{@code tickets.workflow_template_id} is a real foreign key, unlike the
     * stage codes B-042 had to defend by hand — so the database would refuse this
     * delete anyway. The count is here so the refusal arrives as a sentence with a
     * number in it rather than as a constraint violation, and so the screen can
     * decline to offer the button at all.
     */
    Map<Long, Long> ticketCounts() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT workflow_template_id AS id, COUNT(*) AS n
                          FROM tickets
                         WHERE workflow_template_id IS NOT NULL
                         GROUP BY workflow_template_id
                        """)
                .query((rs, i) -> Map.entry(rs.getLong("id"), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /**
     * One template's routing rules with both ends named, in the order the screen
     * shows them.
     *
     * <p><b>{@code LEFT JOIN} on both sides, which is not defensive.</b> A rule
     * with {@code project_id IS NULL} means "any project" and has no row to join
     * to — an inner join would silently drop exactly the wildcard rules that make
     * the ladder worth having, and the screen would render a set of rules the
     * server was resolving against but not showing.
     *
     * <p>Sorted most-specific first, matching the order the resolver evaluates
     * them in. A list showing the wildcards above the exact rules reads as though
     * the wildcards win.
     */
    List<TemplateDtos.TemplateMapping> mappingsFor(long templateId) {
        List<TemplateDtos.TemplateMapping> out = new ArrayList<>();
        jdbc.sql("""
                        SELECT m.id            AS id,
                               m.project_id    AS project_id,
                               p.project_code  AS project_code,
                               p.name          AS project_name,
                               m.task_type_id  AS task_type_id,
                               tt.code         AS task_type_code,
                               tt.name         AS task_type_name
                          FROM workflow_template_mappings m
                          LEFT JOIN projects   p  ON p.id  = m.project_id
                          LEFT JOIN task_types tt ON tt.id = m.task_type_id
                         WHERE m.template_id = :templateId
                         ORDER BY (m.project_key > 0) + (m.task_type_key > 0) DESC,
                                  p.project_code ASC, tt.seq ASC, m.id ASC
                        """)
                .param("templateId", templateId)
                .query((rs, i) -> {
                    long projectId = rs.getLong("project_id");
                    Long project = rs.wasNull() ? null : projectId;
                    int taskTypeId = rs.getInt("task_type_id");
                    Integer taskType = rs.wasNull() ? null : taskTypeId;
                    return new TemplateDtos.TemplateMapping(
                            rs.getLong("id"),
                            project,
                            rs.getString("project_code"),
                            rs.getString("project_name"),
                            taskType,
                            rs.getString("task_type_code"),
                            rs.getString("task_type_name"),
                            (project != null ? 1 : 0) + (taskType != null ? 1 : 0));
                })
                .list()
                .forEach(out::add);
        return out;
    }
}
