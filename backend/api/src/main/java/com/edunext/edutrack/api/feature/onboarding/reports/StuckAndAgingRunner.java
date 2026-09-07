package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.DATE;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.DURATION;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.RAG;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.ColumnType.STRING;

/**
 * B-122 · plan §10's "stuck &amp; aging (block reasons + client-attributed
 * waits)". The prototype's fifth tab — "Blocked, waiting-on-client, or breached
 * — with attribution, so TAT disputes die here."
 *
 * <h2>The attribution column is the report</h2>
 *
 * <p>Everything else here is available on the journey screen one client at a
 * time. What is only available here is the clock state beside the block reason:
 * whether the time currently accruing on a stuck step is ours or the client's.
 * Plan §1.1 makes that the addition "without which every TAT report is disputed
 * within a month", and it is read from the latest {@code ob_step_clock_events}
 * row rather than inferred from {@code status}, because a step can be
 * {@code IN_PROGRESS} with a paused clock — the status says what the work is
 * doing and the clock says whose time it is.
 *
 * <p>A step with no clock event reports "—" rather than "our time". Never
 * started and running against us are different claims, and the second one is an
 * accusation.
 *
 * <h2>Two ages, and only one of them is a duration</h2>
 *
 * <p>"Overdue by" goes through {@code WorkingHoursService}, because CLAUDE.md
 * says every duration does and because the figure is otherwise wrong in the
 * ordinary case: a step that missed a Friday deadline is not two days late on
 * Sunday morning. That is the same rule its own contract states —
 * {@code duration} on this surface is working hours, already through the
 * calendar, so a client must not re-derive it from two timestamps.
 *
 * <p>"Waiting since" is a plain date. It could have been a second working-hours
 * figure and deliberately is not: it answers "how long has nobody touched
 * this", the reader converts it to elapsed time by looking at a calendar, and a
 * second calendar call per row buys a number that says the same thing less
 * clearly. See the cost note below.
 *
 * <h2>🔴 One calendar call per row, and why that is accepted here</h2>
 *
 * <p>{@code workingHoursBetween} walks day by day and queries holidays and
 * leave, so this runner is O(rows) round trips rather than one. That is
 * tolerable for a report — opened deliberately by one person, bounded by a date
 * range, not polled — and it is the price of there being exactly one definition
 * of working time in the product, which is B-024's whole reason for existing.
 * The alternative is a second, calendar-blind subtraction in SQL, and
 * CLAUDE.md forbids it in as many words.
 *
 * <p>It is written down rather than left to be discovered because the fix is
 * real and is not this task's: a batched {@code workingHoursBetween} over many
 * windows sharing one holiday and leave load would collapse the round trips to
 * two, and it belongs on {@code WorkingHoursService} where every caller gets
 * it, not in a report that hand-rolls a cache.
 */
@Component
class StuckAndAgingRunner implements ObReportRunner {

    static final String KEY = "stuck-and-aging";

    /** Plan §5.3's four working states, in the words the screen uses. */
    private static final Map<String, String> STATE_LABELS = Map.of(
            "PENDING", "Not started",
            "IN_PROGRESS", "In progress",
            "BLOCKED", "Blocked",
            "WAITING_ON_CLIENT", "Waiting on client");

    /** {@code ob_step_clock_events.attributed_to}, in the words the caption uses. */
    private static final Map<String, String> CLOCK_LABELS = Map.of(
            "INTERNAL", "Our time",
            "CLIENT", "Client time");

    private final ObReportRepository repository;
    private final WorkingHoursService workingHours;

    StuckAndAgingRunner(ObReportRepository repository, WorkingHoursService workingHours) {
        this.repository = repository;
        this.workingHours = workingHours;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                      Long ownerSubject, ObReportFilters filters) {

        List<ObReportDtos.Column> columns = List.of(
                new ObReportDtos.Column("client", "Client", STRING),
                new ObReportDtos.Column("product", "Product", STRING),
                new ObReportDtos.Column("service", "Service", STRING),
                new ObReportDtos.Column("rag", "Health", RAG),
                new ObReportDtos.Column("state", "State", STRING),
                new ObReportDtos.Column("owner", "Owner", STRING),
                new ObReportDtos.Column("clock", "Clock", STRING),
                new ObReportDtos.Column("waitingSince", "In this state since", DATE),
                new ObReportDtos.Column("overdueBy", "Overdue by", DURATION),
                new ObReportDtos.Column("reason", "Reason", STRING));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ObReportRepository.StuckRow row : repository.stuckAndAging(
                scope, from, to, filters.productId(), now)) {

            String rag = ObStepRag.of(row.startedAt(), row.dueAt(), now);

            // Filtered here rather than in SQL so the chip and the filter read
            // the same value from the same formula — see ObStepRag.
            if (!ObStepRag.matches(rag, filters.rag())) {
                continue;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("client", row.client());
            out.put("product", row.product());
            out.put("service", row.service());
            out.put("rag", rag);
            out.put("state", STATE_LABELS.getOrDefault(row.status(), row.status()));
            out.put("owner", row.owner());
            out.put("clock", row.clock() == null ? null : CLOCK_LABELS.get(row.clock()));
            out.put("waitingSince", asDate(row.clockSince() == null ? row.startedAt() : row.clockSince()));
            out.put("overdueBy", overdueBy(row.dueAt(), now));
            out.put("reason", reason(row));
            rows.add(out);
        }
        return new Result(columns, rows);
    }

    /**
     * Working hours between the missed deadline and now, or null when the step
     * is not overdue.
     *
     * <p>Null rather than zero, on the same principle the on-time percentage
     * uses: zero is a measurement saying the deadline passed this instant, and
     * a step that is blocked but comfortably inside its TAT has not passed one.
     */
    private Object overdueBy(Instant dueAt, Instant now) {
        if (dueAt == null || !now.isAfter(dueAt)) {
            return null;
        }
        return workingHours.workingHoursBetween(dueAt, now);
    }

    /**
     * The block reason, the skip-style note, or the state's own explanation.
     *
     * <p>{@code blocked_reason_code} is mandatory on a BLOCKED step —
     * {@code ck_ob_journey_steps_blocked_reason} enforces it — and the note is
     * optional, so both are shown when both exist. A WAITING_ON_CLIENT step has
     * neither column and its reason is its state, which the prototype spells
     * "Waiting on client input" and is repeated here rather than left blank: an
     * empty Reason cell on a report about why things are stuck reads as missing
     * data.
     */
    private static String reason(ObReportRepository.StuckRow row) {
        if (row.blockCode() != null) {
            return row.blockNote() == null || row.blockNote().isBlank()
                    ? row.blockCode()
                    : row.blockCode() + " — " + row.blockNote();
        }
        if ("WAITING_ON_CLIENT".equals(row.status())) {
            return "Waiting on client input";
        }
        return null;
    }

    /**
     * A {@code date} column carries a date, not an instant.
     *
     * <p>UTC, matching every other date this product writes down. The user's
     * timezone is applied in the presentation layer, which is CLAUDE.md's rule
     * and the reason this does not read a zone from anywhere.
     */
    private static LocalDate asDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
