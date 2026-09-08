package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * B-125 · "may this caller see this client at all" — A-112's row-scope rule,
 * asked once and answered in SQL.
 *
 * <p><b>The predicate is {@link ObClientScope}'s own, composed rather than
 * respelled.</b> CLAUDE.md's rule is that scope is decided server-side by one
 * resolver and never re-implemented; B-102 widened that record's visibility
 * precisely so a sibling feature could reuse it. A second reading of §3 here
 * would drift the first time Sales' rule changed, and it would drift silently
 * because both would look correct in isolation.
 *
 * <p>Out of scope answers the same as absent — the caller gets 404 either
 * way, so no request can distinguish "no such client" from "not yours".
 * That is blueprint §2's no-existence-leak rule, and it is why this returns a
 * boolean rather than an enum a caller could turn into a 403.
 */
@Repository
class ObPrereqClientVisibility {

    private final JdbcClient jdbc;

    ObPrereqClientVisibility(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isVisible(ObClientScope scope, long obClientId) {
        // Short-circuits the query for the roles the predicate would answer
        // `1 = 0` for — a caller with no onboarding standing at all should
        // not cost a round trip to be told nothing.
        if (scope.deniesEverything()) {
            return false;
        }

        var spec = jdbc.sql("""
                        SELECT 1
                          FROM ob_clients c
                         WHERE c.id = :id
                           AND %s
                         LIMIT 1
                        """.formatted(scope.predicate("c")))
                .param("id", obClientId);

        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(Integer.class).optional().isPresent();
    }
}
