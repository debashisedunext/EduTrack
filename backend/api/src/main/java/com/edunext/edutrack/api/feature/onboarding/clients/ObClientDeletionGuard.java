package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.security.scope.UnscopedAccess;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What stands between a client and {@code DELETE} — asked before the statement
 * runs, so the answer is a sentence rather than a foreign-key violation.
 *
 * <h2>Why this is a list and not a boolean</h2>
 *
 * <p>"This client cannot be deleted" sends somebody to guess. "This client has
 * 2 projects and a portal login" ends the question, and usually answers the
 * follow-up too — the person almost always wanted to drop the client, not erase
 * it, and cannot tell which they want until they know what is there.
 *
 * <h2>The four questions, and the two kinds of thing they protect</h2>
 *
 * <p><b>Projects</b> are the structural one: a project owns journeys, journeys
 * own steps, and steps are referenced by {@code ob_step_history} — hash-chained
 * and append-only. {@code fk_ob_projects_client} is {@code RESTRICT}, so the
 * database refuses this one even without the check; the check exists to say so
 * in words.
 *
 * <p><b>The prerequisite checklist, attachments and the portal account</b> are
 * the silent ones. Each hangs off {@code ob_client_id} with a cascade, so a
 * delete would take them without a word — including
 * {@code ob_prereq_history}, the module's second hash-chained table. These are
 * the reason this class exists at all.
 *
 * <h2>What is deliberately not checked</h2>
 *
 * <p>Contacts, requirements and purchase rows. All three cascade, all three are
 * the client's own descriptive data with nothing else pointing at them, and all
 * three are exactly what a row typed in wrong five minutes ago might already
 * have. Blocking on them would make the delete button unreachable in the only
 * case it exists for.
 *
 * <h2>One statement, not four</h2>
 *
 * <p>Four {@code EXISTS} subqueries in one row. The alternative is four round
 * trips to answer a question whose common case is "nothing", on a button that
 * is rendered per row.
 */
@Repository
@UnscopedAccess("""
        Counts rows by ob_client_id with no scope predicate, and must: the \
        caller has already passed a scoped read that answered 404, and the \
        question here is whether anything at all depends on the client — a \
        project belonging to another salesperson blocks the delete exactly as \
        firmly as one of the caller's own.""")
class ObClientDeletionGuard {

    private static final String SQL = """
            SELECT EXISTS (SELECT 1 FROM ob_projects       WHERE ob_client_id = :id) AS hasProjects,
                   EXISTS (SELECT 1 FROM ob_client_prereqs WHERE ob_client_id = :id) AS hasPrereqs,
                   EXISTS (SELECT 1 FROM ob_attachments    WHERE ob_client_id = :id) AS hasAttachments,
                   EXISTS (SELECT 1 FROM client_accounts   WHERE ob_client_id = :id) AS hasPortalLogin
            """;

    private final JdbcClient jdbc;

    ObClientDeletionGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The things in the way, phrased for a person. Empty means the client can
     * go.
     *
     * <p>Order is deliberate — projects first, because it is both the most
     * common blocker and the one that explains the other three.
     */
    List<String> blockersFor(long obClientId) {
        Map<String, Object> row = jdbc.sql(SQL).param("id", obClientId).query().singleRow();

        List<String> blockers = new ArrayList<>();
        if (isTrue(row.get("hasProjects"))) {
            blockers.add("projects");
        }
        if (isTrue(row.get("hasPrereqs"))) {
            blockers.add("a prerequisite checklist");
        }
        if (isTrue(row.get("hasAttachments"))) {
            blockers.add("uploaded documents");
        }
        if (isTrue(row.get("hasPortalLogin"))) {
            blockers.add("a client portal login");
        }
        return blockers;
    }

    /**
     * MySQL's {@code EXISTS} is {@code BIGINT} 0/1, and the driver may hand it
     * back as {@code Long}, {@code Integer} or {@code Boolean} depending on the
     * column's inferred type. Read through {@link Number} rather than cast, so
     * a driver upgrade cannot turn "has projects" into a
     * {@link ClassCastException} on a delete path.
     */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() != 0;
    }
}
