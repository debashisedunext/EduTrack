package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardStepDot;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDelayedProject;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDelayedProjectListResponse;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObProductRef;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.UserRef;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDelayedProjectsRepository.CandidateRow;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * B-128 · assembles plan §9's Delayed Projects grid —
 * {@code listObDelayedProjects}.
 *
 * <h2>{@code delayedByDays} is the one figure {@link ObDelayedProjectsRepository}
 * could not compute</h2>
 *
 * <p>CLAUDE.md: every duration in the system routes through
 * {@link WorkingHoursService}, and SQL cannot call it. So the repository hands
 * back {@code expectedCompletionAt} as a plain instant for every currently-
 * delayed journey the caller's scope can see, and this class turns it into
 * working days late — {@code ceil(workingHoursBetween(expectedCompletionAt,
 * now) / hoursPerWorkingDay)}. Ceiling, not floor or a plain divide: a step
 * due Friday and still open a minute into Monday has accrued a fraction of a
 * working day, and reporting "0 days late" until a whole day has elapsed
 * would make the grid agree with a naive calendar subtraction for the exact
 * case that subtraction gets wrong. {@code ObDelayedProjectsServiceTest}
 * pins the Friday-to-Monday case CLAUDE.md itself names.
 *
 * <h2>Sorted, filtered and paginated here, not in SQL — same reason</h2>
 *
 * <p>The contract orders this grid by {@code delayedByDays} descending and
 * lets a caller floor it with {@code minDelayDays}; neither predicate exists
 * until this class has computed the figure, so both are Java-side over the
 * repository's full, scope-bounded candidate list rather than a {@code LIMIT}
 * and an {@code ORDER BY} the database could apply. That list is the
 * repository's own "naturally bounded" set — currently-delayed journeys, not
 * every journey — so sorting and paging it in memory costs nothing this grid
 * cannot afford; see {@link ObDelayedProjectsRepository}'s class note.
 *
 * <h2>The cursor is a plain {@link Cursor}, encoding {@code delayedByDays}
 * rather than a database column</h2>
 *
 * <p>{@link Cursor} carries its sort key as opaque text and does not care
 * that this route's key is computed in Java rather than read off a row — the
 * same contract every other cursor in the codebase keeps. Decoding follows
 * the generic {@code #/components/parameters/Cursor}'s own rule: anything
 * that fails to parse is treated as the first page rather than a 400, unlike
 * {@code ObDashboardCardItemsService}'s stricter route, because this
 * contract parameter carries no such requirement.
 */
@Service
class ObDelayedProjectsService {

    private final ObDelayedProjectsRepository repository;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository calendars;
    private final Clock clock;

    /** {@code ObDashboardCardItemsService}'s own note on why {@code @Autowired} is not decorative once a second constructor exists. */
    @Autowired
    ObDelayedProjectsService(ObDelayedProjectsRepository repository, WorkingHoursService workingHours,
            WorkingCalendarRepository calendars) {
        this(repository, workingHours, calendars, Clock.systemUTC());
    }

    /** Test seam — {@code delayedByDays} is measured against "now", which a fixed clock lets a test pin. */
    ObDelayedProjectsService(ObDelayedProjectsRepository repository, WorkingHoursService workingHours,
            WorkingCalendarRepository calendars, Clock clock) {
        this.repository = repository;
        this.workingHours = workingHours;
        this.calendars = calendars;
        this.clock = clock;
    }

    ObDelayedProjectListResponse list(CallerIdentity caller, Long productId, Long ownerUserId,
            Integer minDelayDays, String cursorToken, Integer limitParam) {

        ObDashboardScope scope = ObDashboardScope.of(caller);
        int limit = PageLimit.clamp(limitParam);
        Instant now = clock.instant();
        BigDecimal hoursPerWorkingDay = hoursPerWorkingDay();

        List<Scored> scored = new ArrayList<>();
        for (CandidateRow row : repository.candidates(scope, productId, ownerUserId, now)) {
            int delayedByDays = delayedByDays(row.expectedCompletionAt(), now, hoursPerWorkingDay);
            // Ceiling division on a step that only just went overdue can still
            // land on zero — see the class note. The contract's own
            // `minimum: 1` on the wire is what that guards: a row this young
            // is not yet a delayed *project*, whatever minDelayDays asks for.
            if (delayedByDays < 1) {
                continue;
            }
            if (minDelayDays != null && delayedByDays < minDelayDays) {
                continue;
            }
            scored.add(new Scored(row, delayedByDays));
        }

        // Descending by delayedByDays per the contract ("the grid exists to be
        // worked from the top"); journeyId descending breaks ties so the order
        // is total and a keyset cursor over it is well defined.
        scored.sort(Comparator.<Scored>comparingInt(s -> s.delayedByDays)
                .reversed()
                .thenComparing(s -> s.row.journeyId(), Comparator.reverseOrder()));

        List<Scored> afterCursor = applyCursor(scored, decodeCursor(cursorToken));
        List<Scored> fetched = afterCursor.size() > PageLimit.fetchSize(limit)
                ? afterCursor.subList(0, PageLimit.fetchSize(limit))
                : afterCursor;

        CursorPage<Scored> page = CursorPage.of(
                fetched, limit, s -> new Cursor(Integer.toString(s.delayedByDays), s.row.journeyId()));

        Map<Long, List<ObProductRef>> productsBought = repository.productsBoughtByClient(
                page.data().stream().map(s -> s.row.obClientId()).distinct().toList());

        List<ObDelayedProject> data = page.data().stream()
                .map(s -> toDto(s, productsBought.getOrDefault(s.row.obClientId(), List.of())))
                .toList();

        return new ObDelayedProjectListResponse(data, page.meta());
    }

    /** One candidate with its computed, immutable-for-this-request rank. */
    private record Scored(CandidateRow row, int delayedByDays) {
    }

    private static List<Scored> applyCursor(List<Scored> sorted, Cursor cursor) {
        if (cursor == null) {
            return sorted;
        }
        int cursorDays;
        try {
            cursorDays = Integer.parseInt(cursor.sortKey());
        } catch (NumberFormatException notOurs) {
            // A cursor this route never issued reads as "start at the top",
            // the generic Cursor parameter's own rule — see the class note.
            return sorted;
        }
        List<Scored> after = new ArrayList<>();
        boolean pastCursor = false;
        for (Scored s : sorted) {
            if (pastCursor) {
                after.add(s);
                continue;
            }
            if (s.delayedByDays < cursorDays
                    || (s.delayedByDays == cursorDays && s.row.journeyId() < cursor.id())) {
                pastCursor = true;
                after.add(s);
            }
        }
        return after;
    }

    private static Cursor decodeCursor(String token) {
        return token == null || token.isBlank() ? null : Cursor.decode(token);
    }

    /**
     * Ceiling working days between {@code dueAt} and {@code now} — see the
     * class note for why ceiling and why this cannot live in SQL.
     */
    private int delayedByDays(Instant dueAt, Instant now, BigDecimal hoursPerWorkingDay) {
        BigDecimal elapsedHours = workingHours.workingHoursBetween(dueAt, now);
        if (hoursPerWorkingDay.signum() <= 0) {
            // ck_working_calendar_weekly_off guarantees a working day exists;
            // a non-positive length means the calendar row itself is broken,
            // not that this journey has no delay to report.
            return 0;
        }
        return elapsedHours.divide(hoursPerWorkingDay, 0, RoundingMode.CEILING).intValueExact();
    }

    /** The org's working day, in hours — {@code ObStepTatBudget}'s own conversion, restated: that class lives in {@code domain.onboarding} and this route has no step to budget, only a calendar to read. */
    private BigDecimal hoursPerWorkingDay() {
        long minutes = calendars.getCalendar().workDayLength().toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    private static ObDelayedProject toDto(Scored scored, List<ObProductRef> productsBought) {
        CandidateRow row = scored.row();
        ObProductRef product = new ObProductRef(row.productId(), row.productCode(), row.productName());
        ObDashboardStepDot currentStep = row.currentStepId() == null ? null : new ObDashboardStepDot(
                row.currentStepId(), row.currentStepSequence(), row.currentStepName(),
                row.currentStepStatus(), null, row.currentStepDependsOn());
        UserRef responsible = row.responsibleUserId() == null ? null
                : new UserRef(row.responsibleUserId(), row.responsibleName());

        return new ObDelayedProject(
                row.journeyId(), row.obClientId(), row.obClientName(), row.startedAt(),
                productsBought, product, currentStep, responsible,
                row.expectedCompletionAt(), scored.delayedByDays());
    }
}
