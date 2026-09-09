package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * C-121 · CP-03's read-only journey accordions.
 *
 * <p>{@code ObClientReadRepository.journeysOf}/{@code stepDotsOf}'s own two
 * queries, read again here rather than reused: that repository and {@code
 * ObClientDtos}' records are package-private in {@code
 * feature.onboarding.clients}, a staff-owned package this task was not asked
 * to widen. {@link ObStepRag} — the RAG colour math itself — <b>is</b> reused;
 * it is public in the parent {@code feature.onboarding} package precisely so
 * more than one reader can agree on what colour a step is, and duplicating
 * that arithmetic here (rather than the query shape around it) is exactly
 * the drift CLAUDE.md's row-scoping rule warns about one layer over.
 *
 * <p><b>Deliberately narrower than the staff read.</b> No {@code
 * totalTatDays}/{@code utilizedHours} column and no owner join — plan §11's
 * never-visible list names "TAT internals" and "owners" for the client
 * explicitly, so the query never selects them rather than selecting and
 * trimming them in Java, on {@code ObPortalTicketReadRepository}'s own
 * "is_client_visible applied in the query, not the serializer" argument.
 *
 * <p>Percent-complete is computed the same way {@code ObClientService}
 * computes it — settled (DONE or SKIPPED) over total, floored, 0% for an
 * empty journey — restated here as one small static method rather than
 * imported, for the same package-visibility reason as the query above.
 */
@Repository
class PortalJourneyReader {

    private final JdbcClient jdbc;

    PortalJourneyReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<PortalOnboardingDtos.PortalJourneyStrip> journeysOf(long obClientId) {
        List<JourneyRow> journeys = jdbc.sql("""
                        SELECT j.id                 AS id,
                               j.gate_status        AS gateStatus,
                               j.held_by_journey_id AS heldByJourneyId,
                               p.id                 AS productId,
                               p.code               AS productCode,
                               p.name               AS productName,
                               (SELECT COUNT(*) FROM ob_journey_steps ts WHERE ts.journey_id = j.id) AS stepCount,
                               (SELECT COUNT(*) FROM ob_journey_steps ds WHERE ds.journey_id = j.id
                                 AND ds.status IN ('DONE', 'SKIPPED')) AS stepsSettled,
                               (SELECT %s FROM ob_journey_steps rs WHERE rs.journey_id = j.id) AS rag
                          FROM ob_journeys j
                          JOIN ob_products p ON p.id = j.product_id
                         WHERE j.ob_client_id = :id
                           AND j.archived_at IS NULL
                         ORDER BY p.name, j.id
                        """.formatted(ObStepRag.worstOverSteps("rs")))
                .param("id", obClientId)
                .query((rs, n) -> new JourneyRow(
                        rs.getLong("id"), rs.getString("gateStatus"),
                        rs.getObject("heldByJourneyId") == null ? null : rs.getLong("heldByJourneyId"),
                        rs.getLong("productId"), rs.getString("productCode"), rs.getString("productName"),
                        rs.getInt("stepCount"), rs.getInt("stepsSettled"), rs.getString("rag")))
                .list();

        if (journeys.isEmpty()) {
            return List.of();
        }

        Map<Long, PortalOnboardingDtos.PortalOpenEscalation> openEscalations = openEscalationsOf(obClientId);

        Map<Long, List<PortalOnboardingDtos.PortalStepDot>> dots = new LinkedHashMap<>();
        for (StepRow step : stepDotsOf(obClientId)) {
            dots.computeIfAbsent(step.journeyId(), key -> new ArrayList<>())
                    .add(PortalOnboardingDtos.PortalStepDot.of(
                            step.id(), step.sequence(), step.name(), step.status(),
                            step.rag(), step.dependsOnStepId(), openEscalations.get(step.id())));
        }

        List<PortalOnboardingDtos.PortalJourneyStrip> strips = new ArrayList<>(journeys.size());
        for (JourneyRow journey : journeys) {
            strips.add(new PortalOnboardingDtos.PortalJourneyStrip(
                    journey.id(),
                    new PortalOnboardingDtos.PortalProductRef(
                            journey.productId(), journey.productCode(), journey.productName()),
                    journey.gateStatus(), journey.rag(),
                    percentComplete(journey.stepsSettled(), journey.stepCount()),
                    journey.heldByJourneyId(),
                    dots.getOrDefault(journey.id(), List.of())));
        }
        return strips;
    }

    private List<StepRow> stepDotsOf(long obClientId) {
        return jdbc.sql("""
                        SELECT s.id                 AS id,
                               s.journey_id         AS journeyId,
                               s.sequence           AS sequence,
                               s.name               AS name,
                               s.status             AS status,
                               s.depends_on_step_id AS dependsOnStepId,
                               %s                   AS rag
                          FROM ob_journey_steps s
                          JOIN ob_journeys j ON j.id = s.journey_id
                         WHERE j.ob_client_id = :id
                           AND j.archived_at IS NULL
                         ORDER BY s.journey_id, s.sequence, s.id
                        """.formatted(ObStepRag.colourOfStep("s")))
                .param("id", obClientId)
                .query((rs, n) -> new StepRow(
                        rs.getLong("id"), rs.getLong("journeyId"), rs.getInt("sequence"),
                        rs.getString("name"), rs.getString("status"),
                        rs.getObject("dependsOnStepId") == null ? null : rs.getLong("dependsOnStepId"),
                        rs.getString("rag")))
                .list();
    }

    /**
     * C-126 · this client's own open escalations, keyed by {@code stepId} —
     * {@link PortalOnboardingDtos.PortalStepDot#openEscalation()}'s slot,
     * filled. Reads {@code ob_client_escalations} directly rather than
     * through {@code ObClientEscalationController}'s staff-scoped repository:
     * there is no module role to apply here, only "this client's own", which
     * {@code obClientId} already is by the time this method is called.
     */
    private Map<Long, PortalOnboardingDtos.PortalOpenEscalation> openEscalationsOf(long obClientId) {
        return jdbc.sql("""
                        SELECT e.step_id AS stepId, e.id AS id, e.comment AS comment, e.raised_at AS raisedAt
                          FROM ob_client_escalations e
                         WHERE e.ob_client_id = :obClientId
                           AND e.resolved_at IS NULL
                        """)
                .param("obClientId", obClientId)
                .query((rs, n) -> Map.entry(
                        rs.getLong("stepId"),
                        new PortalOnboardingDtos.PortalOpenEscalation(
                                rs.getLong("id"), rs.getString("comment"), rs.getTimestamp("raisedAt").toInstant())))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static int percentComplete(int settled, int total) {
        return total == 0 ? 0 : (int) Math.floor(settled * 100.0 / total);
    }

    private record JourneyRow(long id, String gateStatus, Long heldByJourneyId, long productId,
                              String productCode, String productName, int stepCount, int stepsSettled,
                              String rag) {
    }

    private record StepRow(long id, long journeyId, int sequence, String name, String status,
                           Long dependsOnStepId, String rag) {
    }
}
