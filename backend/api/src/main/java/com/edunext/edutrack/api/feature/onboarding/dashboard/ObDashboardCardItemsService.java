package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardCardItemsRepository.ItemRow;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardItem;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardItemListMeta;
import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardItemListResponse;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * B-127 · assembles the S-06 slide-over behind one OB-02 card — plan §9,
 * screen OB-02, the contract's {@code listObDashboardCardItems}.
 *
 * <h2>Not {@link ObDashboardService}, deliberately</h2>
 *
 * <p>That class answers a pre-aggregated question and this one answers a live
 * one — the contract's own distinction, restated in {@link ObDashboardCardItemsRepository}'s
 * class note. They share the package, {@link ObDashboardCardKey} and
 * {@link ObDashboardScope} rather than a class, because the two reads have
 * almost nothing else in common: one never touches the journey tables, and
 * this one never touches {@code ob_dashboard_summary} except to borrow its
 * {@code computedAt} — see {@link #latestComputedAt}.
 *
 * <h2>Every one of the five module roles is answerable here</h2>
 *
 * <p>{@code ObDashboardScope}'s summary-side limitation — OB_SALES and
 * OB_STEP_OWNER get {@code unavailableReason} instead of a number, because
 * {@code ob_dashboard_summary} carries no scope dimension — does not apply to
 * this route. This reads {@code ob_journey_steps} and
 * {@code ob_client_prereq_tasks} directly, both of which carry every column
 * {@code OnboardingScopeResolver} filters on, so {@link ObDashboardScope#journeyPredicate}
 * and {@link ObDashboardScope#clientPredicate} answer every role a real
 * {@code CallerIdentity} can hold. Only {@link ObDashboardScope#deniesEverything()}
 * — a misconfigured or unrecognised module role — short-circuits to an empty
 * page, on {@code ObEscalationScope}'s own precedent.
 *
 * <h2>The day and week boundaries, restated from {@code ObStatsDay}</h2>
 *
 * <p>{@code ObStatsDay} lives in the {@code worker} module and this one
 * cannot depend on it, so the same organisation-zone arithmetic — resolve
 * "today" and "this week" against {@link WorkingCalendarRepository#getCalendar()}'s
 * zone rather than {@code CONVERT_TZ}, which needs timezone tables MySQL is
 * not guaranteed to have loaded — is restated here rather than shared.
 * {@code ObDashboardCardItemsServiceTest} pins the boundaries against the
 * same cases {@code ObStatsDayTest} does.
 */
@Service
class ObDashboardCardItemsService {

    /**
     * A-118's own default for the AMBER threshold, restated. The worker's
     * {@code ObStatsProperties.amberShare} is the tunable copy — B-113 is
     * where it becomes one row on the TAT settings screen instead of a value
     * living twice. Read from the identical {@code edutrack.ob-stats.amber-share}
     * key here too, so an operator who sets {@code OB_STATS_AMBER_SHARE} for
     * the worker's deployment gets the same threshold on this one without a
     * second setting to remember — but {@code backend/api/src/main/resources/application.yml}
     * is not this task's file to add the key to (outside Stream B's owned
     * paths), so this stays a {@code @Value} default until B-113 or a
     * platform task wires it through.
     */
    private static final String DEFAULT_AMBER_SHARE = "0.75";

    private final ObDashboardCardItemsRepository items;
    private final ObDashboardSummaryRepository summaries;
    private final WorkingCalendarRepository calendars;
    private final Clock clock;
    private final BigDecimal amberShare;

    /** {@code ObReportService}'s own note on why {@code @Autowired} is not decorative once a second constructor exists. */
    @Autowired
    ObDashboardCardItemsService(ObDashboardCardItemsRepository items, ObDashboardSummaryRepository summaries,
            WorkingCalendarRepository calendars,
            @Value("${edutrack.ob-stats.amber-share:" + DEFAULT_AMBER_SHARE + "}") BigDecimal amberShare) {
        this(items, summaries, calendars, Clock.systemUTC(), amberShare);
    }

    /** Test seam — "today" and "this week" cannot be asserted against a clock that only moves forwards. */
    ObDashboardCardItemsService(ObDashboardCardItemsRepository items, ObDashboardSummaryRepository summaries,
            WorkingCalendarRepository calendars, Clock clock, BigDecimal amberShare) {
        this.items = items;
        this.summaries = summaries;
        this.calendars = calendars;
        this.clock = clock;
        this.amberShare = amberShare;
    }

    /**
     * One page of one card's items.
     *
     * @param cardKeyToken the {@code {cardKey}} path segment, unparsed — a 400
     *                     on anything {@link ObDashboardCardKey#fromWire}
     *                     does not recognise, per the contract.
     * @param cursorToken  {@code ?cursor=}, unparsed — a 400 on anything
     *                     present that did not decode as a cursor this route
     *                     issued, per the contract. Blank or absent means the
     *                     first page.
     * @throws UnrecognisedCardKeyException on a {@code cardKeyToken} that is
     *         not one of the seven wire tokens.
     * @throws InvalidCursorException on a non-blank {@code cursorToken} that
     *         is not a cursor this route issued.
     */
    ObDashboardItemListResponse items(CallerIdentity caller, String cardKeyToken, Long productId, Long ownerUserId,
                                      String cursorToken, Integer limitParam) {

        ObDashboardCardKey cardKey = ObDashboardCardKey.fromWire(cardKeyToken)
                .orElseThrow(() -> new UnrecognisedCardKeyException(cardKeyToken));
        Cursor cursor = decodeCursor(cursorToken);
        ObDashboardScope scope = ObDashboardScope.of(caller);
        int limit = PageLimit.clamp(limitParam);

        Instant now = clock.instant();
        ZoneId zone = calendars.getCalendar().zone();
        // LocalDate.now(zone) reads the system clock regardless of this
        // instance's injected Clock — ObDashboardCardItemsServiceTest caught
        // exactly that, against a fixed clock disagreeing with the real date.
        // Deriving "today" from `now` instead is what makes the test-seam
        // constructor actually seam.
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        // Monday-to-Sunday, matching WorkingCalendar's own weekly-off pattern and
        // ObStatsDay's identical choice — "this week's deadlines" and "which days
        // are working days" have to agree where the week begins.
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant weekStart = monday.atStartOfDay(zone).toInstant();
        Instant weekEnd = monday.plusWeeks(1).atStartOfDay(zone).toInstant();

        List<ItemRow> fetched = scope.deniesEverything()
                ? List.of()
                : items.page(cardKey, scope, productId, ownerUserId, cursor, PageLimit.fetchSize(limit),
                        now, amberShare, dayStart, dayEnd, weekStart, weekEnd);

        CursorPage<ItemRow> page = CursorPage.of(
                fetched, limit, row -> new Cursor(row.cursorAt().toString(), row.sortKeySigned()));

        return new ObDashboardItemListResponse(
                page.data().stream().map(ObDashboardItem::of).toList(),
                ObDashboardItemListMeta.of(page.meta(), latestComputedAt(productId)));
    }

    /**
     * The same {@code computed_at} the summary card for this product would
     * report — "repeating the card's own", the contract's own wording — read
     * the identical way {@link ObDashboardService#summary} does. Never the
     * scope: this figure describes when B-120 last ran, not what the caller
     * may see, and disclosing it narrows nothing a scoped caller could not
     * already infer from the board beside this panel.
     *
     * @return null when B-120 has never run, or has no row for this product.
     */
    private Instant latestComputedAt(Long productId) {
        List<LocalDate> days = summaries.recentDays(productId);
        if (days.isEmpty()) {
            return null;
        }
        return summaries.rollup(days.get(0), productId)
                .map(ObDashboardSummaryRepository.Rollup::computedAt)
                .orElse(null);
    }

    /**
     * A cursor that does not decode is refused, <b>not</b> treated as page
     * one — the contract's own stricter rule for this route; see
     * {@link InvalidCursorException}.
     */
    private static Cursor decodeCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Cursor decoded = Cursor.decode(token);
        if (decoded == null) {
            throw new InvalidCursorException(token);
        }
        try {
            // Cursor carries the sort value as opaque text; this route's own
            // encoding is always an Instant, so a value that does not parse as
            // one is a cursor this route never issued.
            Instant.parse(decoded.sortKey());
        } catch (DateTimeParseException notOurs) {
            throw new InvalidCursorException(token);
        }
        return decoded;
    }
}
