package com.edunext.edutrack.api.feature.reports;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A-065 · the wire shapes for {@code /reports/schedule} and
 * {@code /reports/schedules}.
 *
 * <p>{@code ReportScheduleRequest} was declared by D-001 and is honoured
 * exactly as written. What this task adds to the contract is everything on the
 * way back: D-001 declared a bare {@code 201} with no body and no way to list
 * or cancel, and a standing instruction to email people that cannot be read
 * back or stopped is not a shippable feature — it is a subscription with no
 * unsubscribe.
 */
final class ReportScheduleDtos {

    private ReportScheduleDtos() {
    }

    /**
     * §7.8's request, unchanged from D-001.
     *
     * @param parameters the viewer's other filters — {@code projectId},
     *                   {@code resourceId}, {@code clientId},
     *                   {@code taskTypeId}, {@code level}. Deliberately
     *                   free-form on the wire and validated on arrival: which
     *                   filters a report honours is the catalogue's statement,
     *                   and duplicating that as five typed fields here would be
     *                   a second copy to keep in step. <b>Any date range in here
     *                   is ignored</b> — the period comes from the cadence, or
     *                   every run would email the same window for ever.
     */
    record ScheduleRequest(
            String reportKey,
            String cadence,
            String format,
            List<String> recipients,
            Map<String, Object> parameters) {
    }

    @Schema(description = "A standing instruction to email a report.")
    record Schedule(
            long id,
            String reportKey,
            @Schema(description = "The catalogue title, so a list does not have to hold the vocabulary.")
            String reportTitle,
            String cadence,
            String format,
            List<String> recipients,
            Map<String, Object> parameters,
            @Schema(description = "Whether it will fire again. Cancelling sets this false rather than deleting.")
            boolean active,
            @Schema(description = "False when the caller is a recipient rather than the owner. "
                    + "A recipient can download the files and cannot cancel: stopping somebody "
                    + "else's standing instruction is not theirs to decide.")
            boolean ownedByMe,
            @Schema(description = "The owner, whose CURRENT role and projects scope every run.")
            long createdBy,
            String createdByName,
            Instant nextRunAt,
            Instant lastRunAt,
            @Schema(description = "Most recent runs, newest first. Bounded — this is a summary, not a log.")
            List<Run> recentRuns) {
    }

    /**
     * One firing.
     *
     * @param appliedScope what the rows were narrowed to <em>on that run</em>.
     *                     A property of the run rather than of the schedule,
     *                     because the owner's role can change between two of
     *                     them and that difference is the thing worth seeing.
     * @param downloadable false once the file is gone, and for a failed run.
     *                     Sent explicitly so the client never has to infer a
     *                     button's existence from a null.
     */
    record Run(
            long id,
            Instant runAt,
            LocalDate periodFrom,
            LocalDate periodTo,
            String status,
            Integer rowCount,
            String appliedScope,
            String errorText,
            boolean downloadable) {
    }

    record ScheduleResponse(Schedule data) {
    }

    record ScheduleListResponse(List<Schedule> data) {
    }
}
