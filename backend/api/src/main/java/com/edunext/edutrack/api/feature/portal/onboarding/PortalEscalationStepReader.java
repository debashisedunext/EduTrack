package com.edunext.edutrack.api.feature.portal.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * C-126 · resolves and validates one step for the portal's escalation raise
 * route, entirely within the calling client's own scope.
 *
 * <h2>Scoped by a {@code JOIN}, not by a predicate applied afterwards</h2>
 *
 * <p>{@code j.ob_client_id = :obClientId} is in the {@code WHERE} clause
 * alongside the step id, so a step id belonging to another client's journey
 * simply matches no row — {@code requireOwnTask}'s own idiom one class over,
 * and the same reason: a caller who may not read another client's step must
 * not be able to tell "no such step" from "not yours" from the shape of the
 * answer.
 *
 * <p>Unscoped in the sense {@code PortalPrimaryContactReader} already is:
 * {@code obClientId} always comes from the verified {@code ClientPrincipal}
 * on the caller's own token, never from a path or query parameter, so there
 * is nothing here for a row-scope predicate to stop a caller widening.
 */
@Repository
class PortalEscalationStepReader {

    private final JdbcClient jdbc;

    PortalEscalationStepReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StepContext> stepContextFor(long obClientId, long stepId) {
        return jdbc.sql("""
                        SELECT s.id             AS stepId,
                               s.journey_id     AS journeyId,
                               s.status         AS status,
                               s.owner_user_id  AS ownerUserId,
                               s.name           AS stepName,
                               p.name           AS productName,
                               c.name           AS clientName
                          FROM ob_journey_steps s
                          JOIN ob_journeys j ON j.id = s.journey_id
                          JOIN ob_products p ON p.id = j.product_id
                          JOIN ob_clients  c ON c.id = j.ob_client_id
                         WHERE s.id = :stepId
                           AND j.ob_client_id = :obClientId
                        """)
                .param("stepId", stepId)
                .param("obClientId", obClientId)
                .query((rs, rowNum) -> new StepContext(
                        rs.getLong("stepId"), rs.getLong("journeyId"), rs.getString("status"),
                        rs.getObject("ownerUserId") == null ? null : rs.getLong("ownerUserId"),
                        rs.getString("stepName"), rs.getString("productName"), rs.getString("clientName")))
                .optional();
    }

    record StepContext(long stepId, long journeyId, String status, Long ownerUserId,
                       String stepName, String productName, String clientName) {
    }
}
