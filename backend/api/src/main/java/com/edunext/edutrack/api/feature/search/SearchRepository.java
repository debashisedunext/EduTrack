package com.edunext.edutrack.api.feature.search;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A-072 · the three reads behind global search.
 *
 * <p>Blueprint §7.2 asks the top bar for "ticket ID / keyword / person", and
 * those are three different questions with three different right answers — an
 * exact index lookup, a full-text match, and a name prefix. They are separate
 * queries rather than one clever union: a union would have to invent a common
 * relevance across incomparable things, and the exact lookup would lose the
 * property PLAN.md §3.8 insists on, which is that it is instant.
 */
@Repository
class SearchRepository {

    /**
     * InnoDB's {@code innodb_ft_min_token_size}, which defaults to 3.
     *
     * <p>Shorter words are never indexed and therefore can never be found —
     * "QA" and "UI" among them. {@code ChatSearch} mirrors the same default for
     * the same reason and says so; lowering it is a server variable plus an
     * index rebuild, which is an infrastructure decision rather than a
     * migration.
     */
    static final int MIN_TERM_LENGTH = 3;

    private final JdbcClient jdbc;

    SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 1 · the deep link ────────────────────────────────────────────────────

    /**
     * One ticket by its code, under scope.
     *
     * <p>The dominant search, and the one PLAN.md §3.8 requires to be "exact and
     * instant": {@code ticket_code} is unique, so this is a single index seek
     * whatever the table grows to.
     *
     * <p><b>Scoped, and empty rather than refused.</b> A code for somebody
     * else's project finds nothing — the caller cannot tell "no such ticket"
     * from "not yours", which is §2's rule and the reason A-035 answers 404
     * rather than 403. Search is a place that rule matters more than most: a
     * global box that said "that exists but you may not see it" would let
     * anybody confirm a ticket's existence by pasting codes.
     */
    Optional<Hit> exactTicket(String ticketCode, SearchScope scope) {
        var spec = jdbc.sql("""
                        SELECT t.ticket_code, t.title, t.level, t.status
                          FROM tickets t
                         WHERE t.ticket_code = :code
                           AND (%s)
                        """.formatted(scope.sql()))
                .param("code", ticketCode);
        spec = bindScope(spec, scope);
        return spec.query((rs, n) -> new Hit(
                        rs.getString("ticket_code"), rs.getString("title"),
                        rs.getString("level"), rs.getString("status")))
                .optional();
    }

    // ── 2 · the keyword ──────────────────────────────────────────────────────

    /**
     * Tickets matching {@code terms}, most relevant first, under scope.
     *
     * <p>This is the native query {@code TicketListSpecs} anticipated and could
     * not write — it kept {@code LIKE} rather than lose the scope guard, and
     * left the A-009 index for "when this becomes a native query that keeps
     * scope". {@link SearchScope} is that guard, restated for SQL.
     *
     * <p><b>Boolean mode, with the score ordered explicitly.</b> Natural-language
     * mode applies a 50% threshold — a word appearing in more than half the rows
     * is treated as noise and matches nothing — which on a ticket table where
     * most titles contain "login" or "error" silently returns an empty result
     * for the most obvious search somebody could run. Boolean mode has no such
     * rule.
     *
     * <p>{@code ticket_code} is deliberately <b>not</b> matched here. It is not
     * in the index (A-009 covers title and description), and a partial code is
     * not a search anybody performs — they paste a whole one, which
     * {@link #exactTicket} answers.
     */
    List<Hit> ticketsMatching(String booleanQuery, SearchScope scope, int limit) {
        var spec = jdbc.sql("""
                        SELECT t.ticket_code, t.title, t.level, t.status
                          FROM tickets t
                         WHERE MATCH(t.title, t.description) AGAINST (:q IN BOOLEAN MODE)
                           AND (%s)
                         ORDER BY MATCH(t.title, t.description) AGAINST (:q IN BOOLEAN MODE) DESC,
                                  t.date_reported DESC
                         LIMIT :limit
                        """.formatted(scope.sql()))
                .param("q", booleanQuery)
                .param("limit", limit);
        spec = bindScope(spec, scope);
        return spec.query((rs, n) -> new Hit(
                        rs.getString("ticket_code"), rs.getString("title"),
                        rs.getString("level"), rs.getString("status")))
                .list();
    }

    // ── 3 · the person ───────────────────────────────────────────────────────

    /**
     * Active users whose name, username or employee code starts with the term.
     *
     * <p><b>Not scoped by project, and that is deliberate rather than an
     * omission.</b> The staff directory is already readable by every role —
     * {@code GET /users} is {@code everyRole} in the permission matrix, and the
     * reports filter bar populates a Resource dropdown from it for anybody. A
     * narrower rule here would be a new access policy invented inside a search
     * box, and it would contradict the screen next door.
     *
     * <p>What is withheld is anything beyond identity: no ticket counts, no
     * workload, no last-login. Finding a colleague is not the same as reading
     * their performance, which §2 keeps for their manager.
     *
     * <p><b>Prefix match, not {@code %term%}.</b> A leading wildcard cannot use
     * an index, and a name search that scans the user table is a slow query that
     * also matches the middle of unrelated words — "ann" finding "Johanna". A
     * person searching for somebody types the start of their name.
     *
     * <p>Inactive users are excluded: this is a jump-to control, and a leaver's
     * profile is not somewhere anybody is trying to go.
     */
    List<Person> peopleMatching(String term, int limit) {
        String prefix = term.toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("""
                        SELECT u.id, u.full_name, u.username, u.email, r.code AS role_code
                          FROM users u
                          JOIN roles r ON r.id = u.role_id
                         WHERE u.is_active = 1
                           AND (LOWER(u.full_name) LIKE :prefix
                                OR LOWER(u.username) LIKE :prefix
                                OR LOWER(u.emp_code) LIKE :prefix)
                         ORDER BY u.full_name
                         LIMIT :limit
                        """)
                .param("prefix", prefix)
                .param("limit", limit)
                .query((rs, n) -> new Person(
                        rs.getLong("id"), rs.getString("full_name"), rs.getString("username"),
                        rs.getString("email"), rs.getString("role_code")))
                .list();
    }

    /**
     * Binds only what the predicate actually references.
     *
     * <p>{@code JdbcClient} rejects a parameter the SQL does not use, so Admin's
     * {@code 1 = 1} and the deny-all {@code 1 = 0} must not be handed a project
     * list. Asking the scope which bindings it needs keeps that decision beside
     * the SQL that creates it rather than duplicated at every call site.
     */
    private JdbcClient.StatementSpec bindScope(JdbcClient.StatementSpec spec, SearchScope scope) {
        if (scope.bindsProjects()) {
            spec = spec.param("projectIds", scope.projectIds());
        }
        if (scope.bindsUser()) {
            spec = spec.param("scopeUserId", scope.userId());
        }
        return spec;
    }

    record Hit(String ticketCode, String title, String level, String status) {
    }

    record Person(long id, String fullName, String username, String email, String roleCode) {
    }
}
