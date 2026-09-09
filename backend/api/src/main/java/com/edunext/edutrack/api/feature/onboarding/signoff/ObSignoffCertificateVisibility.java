package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * B-116 · "may this caller see this sign-off's client at all" — A-112's
 * row-scope rule, asked once and answered in SQL.
 *
 * <p>{@code ObPrereqClientVisibility}'s pattern (B-125), one onboarding
 * package over, on that class's own reasoning: the predicate is
 * {@link ObClientScope}'s own, composed rather than respelled, and
 * {@code ObClientScope} was widened to public in B-125 precisely so a
 * sibling onboarding feature could reuse it without a second reading of §3
 * that would drift the first time Sales' rule changed.
 *
 * <p>Out of scope answers the same as absent — 404 either way, so no request
 * can distinguish "no such sign-off" from "not yours". Blueprint §2's
 * no-existence-leak rule, and the reason this returns a boolean rather than
 * an enum a caller could turn into a 403.
 */
@Repository
class ObSignoffCertificateVisibility {

    private final JdbcClient jdbc;

    ObSignoffCertificateVisibility(JdbcClient jdbc) {
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
