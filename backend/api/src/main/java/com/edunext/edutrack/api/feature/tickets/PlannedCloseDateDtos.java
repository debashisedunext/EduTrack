package com.edunext.edutrack.api.feature.tickets;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * C-012 · the wire shape of the planned-close-date preview.
 *
 * <p>Nested records rather than a package of one-field files, matching
 * {@code CalendarDtos} — the DTOs for one endpoint are read together and
 * changing one usually means changing its sibling.
 */
public final class PlannedCloseDateDtos {

    private PlannedCloseDateDtos() {
    }

    /** CONVENTIONS.md §2 — every 2xx body is wrapped in {@code data}. */
    public record PlannedCloseDateResponse(PlannedCloseDatePreview data) {
    }

    /**
     * @param from             the instant the clock starts — echoed back because
     *                         the caller may have omitted it and "computed from
     *                         now" needs a <em>which</em> now to be checkable
     * @param plannedCloseDate null when no rung of the ladder answered; the
     *                         ticket genuinely has no planned close date and the
     *                         form must say so rather than showing a blank
     * @param firstResponseDue null unless the rung that answered carries a
     *                         response target — the two master defaults do not
     * @param resolutionHrs    the target in <b>working</b> hours, not elapsed
     * @param source           which rung answered, so the date is explicable
     * @param slaPolicyId      the {@code sla_policies} row, or null where a
     *                         master default answered instead
     */
    @Schema(description = "SLA resolution and the planned close date it produces, computed against the working calendar.")
    public record PlannedCloseDatePreview(
            Instant from,
            Instant plannedCloseDate,
            Instant firstResponseDue,
            BigDecimal responseHrs,
            BigDecimal resolutionHrs,
            SlaResolution.Source source,
            Long slaPolicyId) {

        static PlannedCloseDatePreview of(PlannedCloseDateService.Preview preview) {
            SlaResolution sla = preview.sla();
            return new PlannedCloseDatePreview(
                    preview.from(),
                    preview.plannedCloseDate(),
                    preview.firstResponseDue(),
                    sla.responseHrs(),
                    sla.resolutionHrs(),
                    sla.source(),
                    sla.slaPolicyId());
        }
    }
}
