package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.domain.masters.SlaPolicy;

import java.math.BigDecimal;

/**
 * C-012 · which SLA target a ticket is measured against, and where it came from.
 *
 * <p>{@code source} is carried all the way onto the wire deliberately. A planned
 * close date with no explanation is a date the user cannot argue with — "16
 * working hours, this project's Production Bug × High policy" is checkable,
 * "05 Sep 14:30" is not. It is also the only way the person configuring the SLA
 * matrix (S-13) can see that their new row is the one being applied.
 *
 * @param source        which rung of the ladder answered
 * @param slaPolicyId   the {@code sla_policies} row, or null where the answer
 *                      came from a master's default rather than a policy row
 * @param responseHrs   working hours to first response, or null where the rung
 *                      that answered only carries a resolution target
 * @param resolutionHrs working hours to resolution, or null when nothing at all
 *                      matched — see {@link #hasTarget()}
 */
public record SlaResolution(Source source, Long slaPolicyId, BigDecimal responseHrs, BigDecimal resolutionHrs) {

    /**
     * The resolution ladder, most specific first.
     *
     * <p>The first three rungs are §6's, implemented by
     * {@link com.edunext.edutrack.domain.masters.SlaPolicyRepository} and shared
     * with Stream D's escalation scanner. The two after them are this class's
     * addition and the reasoning is worth recording, because the repository's
     * own javadoc stops at rung 3 with "missing all three means the ticket has
     * no SLA":
     *
     * <p>That is the right answer for a <em>scanner</em>, which should leave a
     * ticket alone rather than invent a breach. It is the wrong answer for a
     * <em>planned close date</em>. A project whose SLA matrix has not been
     * configured yet — every project, on its first day — would give every ticket
     * no planned close date at all, and {@code plannedCloseDate IS NULL} takes a
     * ticket out of {@code SlaRepository}'s breach sweep, out of the pre-breach
     * warning, out of the delayed KPI and out of the Due Today saved view. The
     * ticket stops being tracked, silently, and nothing on the screen says so.
     *
     * <p>So the ladder keeps falling back while any master can still answer.
     * {@link #PRIORITY_DEFAULT} comes before {@link #TASK_TYPE_DEFAULT} because
     * the first three rungs are all keyed by level and the priority master's
     * default is the last figure that still varies with it — a type default
     * would hand Critical and Low the same date, which is precisely the
     * behaviour §4B.1 wants the level dropdown to remove.
     */
    public enum Source {
        /** Rung 1 — this project's policy for this task type and level. */
        PROJECT_TASK_TYPE,
        /** Rung 2 — this project's default for the level, any task type. */
        PROJECT_LEVEL,
        /** Rung 3 — the org-wide default for the level. */
        ORG_DEFAULT,
        /** Rung 4 — the priority master's {@code default_sla_hours} (S-12). */
        PRIORITY_DEFAULT,
        /** Rung 5 — the task type master's {@code default_sla_hours} (S-11). */
        TASK_TYPE_DEFAULT,
        /** Nothing answered. The ticket genuinely has no planned close date. */
        NONE,
    }

    static SlaResolution none() {
        return new SlaResolution(Source.NONE, null, null, null);
    }

    static SlaResolution of(Source source, SlaPolicy policy) {
        return new SlaResolution(source, policy.getId(), policy.getResponseHrs(), policy.getResolutionHrs());
    }

    /**
     * A resolution-hours default carries no response target: the two masters
     * hold one figure each, and inventing a response target as a fraction of it
     * would put a number on the screen that no administrator ever typed.
     */
    static SlaResolution fromDefault(Source source, BigDecimal resolutionHrs) {
        return new SlaResolution(source, null, null, resolutionHrs);
    }

    /**
     * Whether a date can be computed at all.
     *
     * <p>A non-positive figure counts as no target rather than "due at the
     * instant it was raised": {@code WorkingHoursService.addWorkingHours}
     * returns {@code start} unchanged for it, so honouring a zero would give
     * every such ticket a planned close date already in the past and hand
     * Stream D's scanner an immediate breach on a ticket nobody has read yet.
     */
    boolean hasTarget() {
        return resolutionHrs != null && resolutionHrs.signum() > 0;
    }
}
