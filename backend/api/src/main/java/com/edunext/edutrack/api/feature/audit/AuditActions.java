package com.edunext.edutrack.api.feature.audit;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A-071 · the audit vocabulary, and how a route turns into one term of it.
 *
 * <h2>Derived from the route, not written down per route</h2>
 *
 * <p>A-071's line reads "every login, permission change, master change, ticket
 * action" — four modules owned by four different developers. The obvious
 * implementation is a call to {@code AuditTrail.record} at the end of each
 * service method, and it is the wrong one twice over: it is an edit in Streams
 * B, C and D's directories, and it produces a log whose completeness is exactly
 * as good as the last person who remembered. An audit log with holes is worse
 * than no audit log, because the holes are invisible and the log is trusted.
 *
 * <p>So the term is <em>derived</em> from the request, by
 * {@link AuditInterceptor}, and every mutating route is covered the day it is
 * written — including routes that do not exist yet. Nobody has to remember, and
 * nobody outside Stream A has to change a line.
 *
 * <p>What that costs is stated in {@code README.md} and not hidden: a derived
 * term knows <em>that</em> a ticket was updated and not <em>which field</em>,
 * so most rows carry no before-and-after. The alternative buys the diff and
 * loses the coverage.
 *
 * <h2>entityType is the module, not the leaf resource</h2>
 *
 * <p>{@code POST /tickets/{ticketId}/comments} records
 * {@code entityType = tickets}, {@code entityRef = CRM-26-00347},
 * {@code action = COMMENTS_CREATED}. Reading the module off the <em>first</em>
 * static segment rather than the last is what makes S-16's "filter by module" a
 * closed set of about a dozen values instead of an open-ended list of every
 * leaf resource in the product — and it is what makes
 * {@code ix_audit_logs_entity (entity_type, entity_id)} answer "everything that
 * happened to this ticket", which is the question §4A.7's Activity tab asks.
 * The leaf is not lost; it is the first half of the action.
 */
final class AuditActions {

    /** Everything below {@code /api/v1}. */
    static final String API_PREFIX = "/api/v1";

    // --- the terms written by name, where a route cannot say enough ---------

    /** A-020's login succeeded. Recorded by {@code AuthController}, with IP and agent. */
    static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";

    /**
     * A-020's login was refused. Recorded with the submitted identifier in
     * {@code newValue} and no actor — deliberately: the point of A-020's single
     * failure mode is that the server never says whether the name matched a
     * user, and resolving it here to fill {@code actor_id} would write that
     * answer into a table and undo it.
     */
    static final String LOGIN_FAILED = "LOGIN_FAILED";

    /** A-076's rate limiter refused the attempt before the KDF ran. */
    static final String LOGIN_THROTTLED = "LOGIN_THROTTLED";

    /** A-021's lockout, reported to somebody who had just proved the password. */
    static final String LOGIN_LOCKED_OUT = "LOGIN_LOCKED_OUT";

    /**
     * A-029's TOTP check failed, after a correct password.
     *
     * <p>Its own term rather than {@link #LOGIN_FAILED}, and the distinction is
     * the whole reason it exists: this one means somebody <em>has</em> the
     * password and is grinding six digits. Filed as an ordinary failed login it
     * would sit among the typos of everybody who mistyped theirs that morning.
     */
    static final String LOGIN_2FA_FAILED = "LOGIN_2FA_FAILED";

    /** A-024's logout — the refresh token and its descendants were revoked. */
    static final String LOGOUT = "LOGOUT";

    /**
     * A request reached a handler and was refused by {@code @PreAuthorize}.
     *
     * <p>Recorded on reads as well as writes, and the only failure recorded at
     * all. Somebody who is not an Admin asking for {@code GET /audit-logs} is
     * precisely the event this screen exists to show, and it leaves no other
     * trace: nothing changed, so no other row is written.
     */
    static final String ACCESS_DENIED = "ACCESS_DENIED";

    // --- derivation --------------------------------------------------------

    /** The verb half of a derived term. */
    private static final Map<String, String> VERBS = Map.of(
            "POST", "CREATED",
            "PUT", "UPDATED",
            "PATCH", "UPDATED",
            "DELETE", "DELETED");

    private AuditActions() {
    }

    /** True where this method changes something, so a 2xx is worth a row. */
    static boolean isMutating(String httpMethod) {
        return httpMethod != null && VERBS.containsKey(httpMethod.toUpperCase(Locale.ROOT));
    }

    /**
     * Turn a matched route into an audit term.
     *
     * @param httpMethod   {@code POST}, {@code PATCH}, …
     * @param routePattern the mapping pattern with its {@code {placeholders}} —
     *                     never the concrete URI, or every id in the system
     *                     would become part of the vocabulary
     * @return the term, or empty where the pattern names nothing derivable
     */
    static Optional<String> actionFor(String httpMethod, String routePattern) {
        String verb = VERBS.get(httpMethod == null ? "" : httpMethod.toUpperCase(Locale.ROOT));
        if (verb == null) {
            return Optional.empty();
        }
        List<String> statics = staticSegments(routePattern);
        if (statics.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(code(statics.get(statics.size() - 1)) + "_" + verb);
    }

    /**
     * The module a route belongs to — the first static segment.
     *
     * <p>Lower-case with hyphens folded to underscores, so it reads like the
     * table name the column's comment promises: {@code import-batches} becomes
     * {@code import_batches}, {@code masters/roles} becomes {@code masters}.
     */
    static Optional<String> moduleFor(String routePattern) {
        List<String> statics = staticSegments(routePattern);
        return statics.isEmpty()
                ? Optional.empty()
                : Optional.of(statics.get(0).replace('-', '_').toLowerCase(Locale.ROOT));
    }

    /**
     * The name of the path variable identifying the subject: the <b>first</b>
     * placeholder in the pattern.
     *
     * <p>First rather than last, and the difference shows on
     * {@code DELETE /tickets/{ticketId}/comments/{commentId}}. The last records
     * the comment — a row nothing can look up once it is gone and no screen can
     * render. The first records the ticket, which is the subject the module
     * names and the record a reader wants to open. Read out of the pattern
     * rather than out of the variables map, because a map has no order to take
     * the first of.
     */
    static Optional<String> subjectVariable(String routePattern) {
        if (routePattern == null) {
            return Optional.empty();
        }
        int open = routePattern.indexOf('{');
        int close = routePattern.indexOf('}', open + 1);
        if (open < 0 || close < 0) {
            return Optional.empty();
        }
        String name = routePattern.substring(open + 1, close);
        // `{id:[0-9]+}` is legal Spring syntax and is unused in this codebase,
        // but a regex suffix would otherwise become part of the variable name
        // and never match the map.
        int colon = name.indexOf(':');
        return Optional.of(colon < 0 ? name : name.substring(0, colon));
    }

    /** {@code task-types} becomes {@code TASK_TYPES}. */
    private static String code(String segment) {
        return segment.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static List<String> staticSegments(String routePattern) {
        if (routePattern == null || routePattern.isBlank()) {
            return List.of();
        }
        String path = routePattern.startsWith(API_PREFIX)
                ? routePattern.substring(API_PREFIX.length())
                : routePattern;
        return Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isBlank())
                .filter(segment -> segment.indexOf('{') < 0)
                // `*` and `**` are matchers, not resources.
                .filter(segment -> segment.indexOf('*') < 0)
                .toList();
    }
}
