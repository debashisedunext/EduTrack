package com.edunext.edutrack.api.feature.masters.stages;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * B-040 · how much a stage is used — the two figures that decide whether its code
 * may still be renamed, and how loudly a reorder should warn.
 *
 * <p>Plain SQL through {@link JdbcClient} for the reason
 * {@code StatusUsageRepository} gives one package over: {@code tickets} and
 * {@code ticket_stage_transitions} are far too large to count in memory, and a
 * grouped projection is not what a {@code JpaRepository} is for.
 *
 * <h2>Both counts key on the stage <em>code</em>, scoped by template</h2>
 *
 * <p><b>Neither table holds {@code workflow_stages.id}.</b>
 * {@code ticket_stage_transitions.to_stage} and {@code tickets.current_stage} are
 * {@code VARCHAR} columns holding the code — deliberately not foreign keys, per
 * A-005 — so a join on the id would compile, run, and return zero for every row.
 * No unit test with a mocked repository could tell.
 *
 * <p>The template scope is the half that is easy to drop and expensive to lose.
 * {@code DEV} exists on both Standard Dev Flow and Support Fast-Track as two
 * separate rows, so counting by code alone would report Support Fast-Track's
 * traffic against Standard Dev Flow's stage — and freeze a code on a template
 * where nothing had ever entered it. The join back through
 * {@code tickets.workflow_template_id} is what makes the number belong to the row
 * the screen is showing.
 *
 * <p><b>This is not the {@code COUNT(*)} CLAUDE.md forbids.</b> That rule is
 * about dashboards — read constantly, with pre-aggregated summary tables built
 * for them. This is an Admin screen opened a handful of times a year, and a
 * summary table for it would be a second source of truth to maintain.
 */
@Repository
class StageUsageRepository {

    private final JdbcClient jdbc;

    StageUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Both counts for every stage of one template, in two grouped statements
     * rather than two per row.
     *
     * <p>Eight rows is exactly the size at which an N+1 is invisible, which is how
     * an N+1 survives to a table that is not eight rows.
     *
     * <p><b>Transitions count every hop ever made into the stage, including on
     * closed tickets</b>, because that is the population a rename would break.
     * {@code openTickets} counts only where a ticket is standing right now, which
     * is the population a reorder disturbs. They answer different questions and
     * the larger one is not a superset — a stage can hold an open ticket whose
     * first hop predates nothing, and a stage every ticket has left still has
     * history to protect.
     */
    Counts forTemplate(long templateId) {
        Map<String, Long> transitions = new HashMap<>();
        jdbc.sql("""
                        SELECT tr.to_stage AS code, COUNT(*) AS n
                          FROM ticket_stage_transitions tr
                          JOIN tickets t ON t.id = tr.ticket_id
                         WHERE t.workflow_template_id = :templateId
                         GROUP BY tr.to_stage
                        """)
                .param("templateId", templateId)
                .query((rs, i) -> Map.entry(rs.getString("code"), rs.getLong("n")))
                .list()
                .forEach(e -> transitions.put(e.getKey(), e.getValue()));

        Map<String, Long> openTickets = new HashMap<>();
        jdbc.sql("""
                        SELECT t.current_stage AS code, COUNT(*) AS n
                          FROM tickets t
                         WHERE t.workflow_template_id = :templateId
                           AND t.current_stage IS NOT NULL
                         GROUP BY t.current_stage
                        """)
                .param("templateId", templateId)
                .query((rs, i) -> Map.entry(rs.getString("code"), rs.getLong("n")))
                .list()
                .forEach(e -> openTickets.put(e.getKey(), e.getValue()));

        return new Counts(transitions, openTickets);
    }

    /**
     * How many stages each template has, for the selector — one statement rather
     * than one per template.
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
     * Two maps keyed by stage code, each defaulting to zero.
     *
     * <p>Defaulting matters: a stage nothing has ever entered has no row in either
     * result, and that absence is the ordinary case for a stage somebody has just
     * added. Returning zero rather than null is what makes
     * {@code isCodeEditable} true for it without a null check at every call site.
     */
    record Counts(Map<String, Long> transitions, Map<String, Long> openTickets) {

        long transitionsFor(String code) {
            return transitions.getOrDefault(code, 0L);
        }

        long openTicketsFor(String code) {
            return openTickets.getOrDefault(code, 0L);
        }

        static Counts empty() {
            return new Counts(Map.of(), Map.of());
        }

    }
}
