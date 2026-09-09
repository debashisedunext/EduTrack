package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObImplementorWorkload;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObImplementorWorkloadListResponse;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.UserRef;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObImplementorWorkloadRepository.Row;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.common.pagination.PageMeta;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * B-128 · assembles plan §9's Implementor workload & performance grid —
 * {@code listObImplementorWorkload}.
 *
 * <h2>{@code performanceScore} is derived here, once, on every read</h2>
 *
 * <p>A-108's migration states why at length and it is worth restating beside
 * the formula it justifies: the weighting is a product decision that will be
 * tuned, and a score written under last month's weights could never be
 * recomputed under this month's — every historical row would silently mean
 * something different from the ones beside it. Computing it on read means
 * retuning {@link #EARLY_BONUS}, {@link #BLOCK_PENALTY_PER_WORKING_DAY} or
 * {@link #BLOCK_PENALTY_CAP} re-scores every row the next time it is read,
 * consistently, rather than stratifying history the way a stored score would.
 *
 * <h2>The formula, and what it deliberately does not weigh</h2>
 *
 * <p>Two passes over the four inputs the schema names:
 *
 * <ol>
 *   <li><b>Quality</b> — {@code onTrack}'s counterpart at the completion
 *       grain: {@code (completedOnTime + completedEarly × 1.2) /
 *       (completedOnTime + completedEarly + completedLate)}, as a
 *       percentage. An early finish counts for more than an on-time one and
 *       a late one counts for nothing, which is what makes the fraction fall
 *       as the late share rises rather than needing a separate penalty term
 *       for lateness.</li>
 *   <li><b>Block penalty</b> — {@code blockedHours} converted to working
 *       days through {@link WorkingCalendarRepository} (never a hardcoded
 *       eight, {@code ObReportService}'s own reason) and subtracted at
 *       {@link #BLOCK_PENALTY_PER_WORKING_DAY} points per day, capped at
 *       {@link #BLOCK_PENALTY_CAP} — a person carrying one long-blocked
 *       client should not be able to lose the whole score to it, when the
 *       block itself may be nothing they caused.</li>
 * </ol>
 *
 * <p><b>Null for zero completions</b> — the schema's own words, restated as
 * code rather than merely as a comment: scoring somebody who has finished
 * nothing is arithmetic on an empty set dressed up as a judgement.
 * {@code onTrack}, {@code notStarted}, {@code atRisk}, {@code delayed} and
 * {@code blockedWaiting} — the five columns describing <em>open</em> work —
 * deliberately do not enter this formula at all: they describe what somebody
 * is carrying right now, and the chip answers "how did their finished work
 * turn out", a different and narrower question plan §9 keeps as its own
 * counters precisely so a caller can read either without the other.
 */
@Service
class ObImplementorWorkloadService {

    /** Multiplier on an early completion, over an on-time one — see the class note. */
    private static final BigDecimal EARLY_BONUS = BigDecimal.valueOf(1.2);

    /** Points subtracted per working day this implementor's steps spent blocked or waiting. */
    private static final BigDecimal BLOCK_PENALTY_PER_WORKING_DAY = BigDecimal.valueOf(2);

    /** The most a blocked-time penalty may take off — see the class note on why it is capped. */
    private static final BigDecimal BLOCK_PENALTY_CAP = BigDecimal.valueOf(20);

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final ObImplementorWorkloadRepository repository;
    private final WorkingCalendarRepository calendars;

    ObImplementorWorkloadService(ObImplementorWorkloadRepository repository, WorkingCalendarRepository calendars) {
        this.repository = repository;
        this.calendars = calendars;
    }

    ObImplementorWorkloadListResponse list(CallerIdentity caller, LocalDate statDateParam,
            boolean includeInactive, String cursorToken, Integer limitParam) {

        ObDashboardScope scope = ObDashboardScope.of(caller);
        int limit = PageLimit.clamp(limitParam);

        LocalDate statDate = statDateParam != null ? statDateParam : repository.latestStatDate().orElse(null);
        if (statDate == null) {
            // B-120 has never run. Silence, not a board of zeroes — A-108's
            // own reading for this table's first days, restated once more.
            return new ObImplementorWorkloadListResponse(List.of(), PageMeta.last());
        }

        Cursor cursor = decodeCursor(cursorToken);
        String cursorName = cursor == null ? null : cursor.sortKey();
        Long cursorId = cursor == null ? null : cursor.id();

        List<Row> fetched = repository.page(
                scope, statDate, includeInactive, cursorName, cursorId, PageLimit.fetchSize(limit));

        CursorPage<Row> page = CursorPage.of(fetched, limit, row -> new Cursor(row.fullName(), row.userId()));

        BigDecimal hoursPerWorkingDay = hoursPerWorkingDay();
        List<ObImplementorWorkload> data = page.data().stream()
                .map(row -> toDto(row, hoursPerWorkingDay))
                .toList();

        return new ObImplementorWorkloadListResponse(data, page.meta());
    }

    private static Cursor decodeCursor(String token) {
        return token == null || token.isBlank() ? null : Cursor.decode(token);
    }

    private BigDecimal hoursPerWorkingDay() {
        long minutes = calendars.getCalendar().workDayLength().toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    private static ObImplementorWorkload toDto(Row row, BigDecimal hoursPerWorkingDay) {
        return new ObImplementorWorkload(
                new UserRef(row.userId(), row.fullName()),
                row.isActive(),
                row.clientsOpen(), row.onTrack(), row.notStarted(), row.delayed(), row.atRisk(),
                row.blockedWaiting(), row.aheadOfSchedule(),
                row.completedOnTime(), row.completedEarly(), row.completedLate(),
                row.blockedHours(),
                performanceScore(row.completedOnTime(), row.completedEarly(), row.completedLate(),
                        row.blockedHours(), hoursPerWorkingDay),
                row.statDate());
    }

    /**
     * 0–100, or null when {@code onTime + early + late == 0} — see the class
     * note for the formula and why each term is shaped the way it is.
     *
     * <p>Package-visible, not private, so {@code ObImplementorWorkloadServiceTest}
     * can pin exact figures without going through the repository — the
     * arithmetic is the thing worth testing precisely, independent of
     * whatever a database happens to hand it.
     */
    static BigDecimal performanceScore(int onTime, int early, int late, int blockedHours,
            BigDecimal hoursPerWorkingDay) {
        int completions = onTime + early + late;
        if (completions == 0) {
            return null;
        }

        BigDecimal good = BigDecimal.valueOf(onTime).add(BigDecimal.valueOf(early).multiply(EARLY_BONUS));
        BigDecimal quality = good
                .divide(BigDecimal.valueOf(completions), 6, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED);

        BigDecimal blockedDays = hoursPerWorkingDay.signum() > 0
                ? BigDecimal.valueOf(blockedHours).divide(hoursPerWorkingDay, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal blockPenalty = blockedDays.multiply(BLOCK_PENALTY_PER_WORKING_DAY).min(BLOCK_PENALTY_CAP);

        BigDecimal score = quality.subtract(blockPenalty);
        if (score.signum() < 0) {
            score = BigDecimal.ZERO;
        } else if (score.compareTo(ONE_HUNDRED) > 0) {
            score = ONE_HUNDRED;
        }
        return score.setScale(1, RoundingMode.HALF_UP);
    }
}
