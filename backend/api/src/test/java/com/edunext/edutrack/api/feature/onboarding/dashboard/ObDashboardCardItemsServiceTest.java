package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-127 · what happens around the SQL — cardKey and cursor validation, the day
 * and week boundaries, the deny-everything short circuit, and the borrowed
 * {@code computedAt} — without a database. {@code ObDashboardCardItemsIT}
 * covers the union query itself.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObDashboardCardItemsServiceTest {

    /** A Wednesday, 10:00 UTC — comfortably inside both its own day and its own week. */
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant COMPUTED = Instant.parse("2026-09-02T06:00:00Z");

    private final ObDashboardCardItemsRepository items = mock(ObDashboardCardItemsRepository.class);
    private final ObDashboardSummaryRepository summaries = mock(ObDashboardSummaryRepository.class);
    private final WorkingCalendarRepository calendars = mock(WorkingCalendarRepository.class);

    private ObDashboardCardItemsService service;

    @BeforeEach
    void wireUp() {
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("UTC");
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        calendar.setWorkDayStart(LocalTime.of(9, 30));
        calendar.setWorkDayEnd(LocalTime.of(18, 30));
        when(calendars.getCalendar()).thenReturn(calendar);

        when(summaries.recentDays(any())).thenReturn(List.of(LocalDate.of(2026, 9, 2)));
        when(summaries.rollup(any(), any())).thenReturn(
                Optional.of(new ObDashboardSummaryRepository.Rollup(
                        LocalDate.of(2026, 9, 2), COMPUTED, Map.of())));

        service = new ObDashboardCardItemsService(
                items, summaries, calendars, Clock.fixed(NOW, ZoneOffset.UTC), new BigDecimal("0.75"));

        when(items.page(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    // ── cardKey ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unrecognised cardKey is refused before the repository is asked anything")
    void anUnrecognisedCardKeyIsRefused() {
        assertThatThrownBy(() -> service.items(caller("OB_MANAGER"), "not-a-real-card", null, null, null, null))
                .isInstanceOf(UnrecognisedCardKeyException.class);

        verifyNoInteractions(items);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ongoing-projects", "this-weeks-deadlines", "todays-delivery",
            "overdue-clients", "live", "at-risk", "client-escalations"})
    @DisplayName("every wire token the contract enumerates is accepted")
    void everyContractTokenIsAccepted(String token) {
        assertThatCode(() -> service.items(caller("OB_MANAGER"), token, null, null, null, null))
                .doesNotThrowAnyException();
    }

    // ── cursor ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank cursor is the first page, not a refusal")
    void aBlankCursorIsTheFirstPage() {
        service.items(caller("OB_MANAGER"), "ongoing-projects", null, null, "", null);
        service.items(caller("OB_MANAGER"), "ongoing-projects", null, null, null, null);
        // Neither call threw, which is the assertion — see aGarbageCursorIsRefused
        // for the contrast.
    }

    @Test
    @DisplayName("a cursor that never came from meta.nextCursor is refused, not treated as page one")
    void aGarbageCursorIsRefused() {
        assertThatThrownBy(() -> service.items(
                caller("OB_MANAGER"), "ongoing-projects", null, null, "not-a-real-cursor!!", null))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor that decodes but whose sort key is not an instant is refused")
    void aCursorWithANonInstantSortKeyIsRefused() {
        // A syntactically valid Cursor (base64 of "7|not-an-instant") this route
        // never issued — every cursor it hands out encodes an Instant.
        String foreign = new Cursor("not-an-instant", 7).encode();

        assertThatThrownBy(() -> service.items(
                caller("OB_MANAGER"), "ongoing-projects", null, null, foreign, null))
                .isInstanceOf(InvalidCursorException.class);
    }

    // ── deny-everything ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a role that denies everything gets an empty page without querying the repository")
    void aDeniedRoleNeverReachesTheRepository() {
        var response = service.items(caller("TICKETING_MEMBER"), "ongoing-projects", null, null, null, null);

        assertThat(response.data()).isEmpty();
        assertThat(response.meta().hasMore()).isFalse();
        assertThat(response.meta().nextCursor()).isNull();
        verify(items, never()).page(any(), any(), any(), any(), any(),
                anyInt(), any(), any(), any(), any(), any(), any());
    }

    // ── computedAt ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("meta.computedAt repeats the summary card's own, not a value from the item rows")
    void computedAtIsBorrowedFromTheSummary() {
        var response = service.items(caller("OB_MANAGER"), "ongoing-projects", 9L, null, null, null);

        assertThat(response.meta().computedAt()).isEqualTo(COMPUTED);
    }

    @Test
    @DisplayName("computedAt is null once the summary has never been computed, and the page still answers")
    void computedAtIsNullBeforeTheFirstRefresh() {
        when(summaries.recentDays(any())).thenReturn(List.of());

        var response = service.items(caller("OB_MANAGER"), "ongoing-projects", null, null, null, null);

        assertThat(response.meta().computedAt()).isNull();
        assertThat(response.data()).isEmpty();
    }

    // ── the day and week boundaries ──────────────────────────────────────────

    @Test
    @DisplayName("today's boundaries are midnight to midnight in the calendar's zone")
    void todaysBoundaries() {
        service.items(caller("OB_MANAGER"), "todays-delivery", null, null, null, null);

        verify(items).page(eq(ObDashboardCardKey.TODAYS_DELIVERY), any(), isNull(), isNull(), isNull(),
                anyInt(), eq(NOW), any(),
                eq(Instant.parse("2026-09-02T00:00:00Z")), eq(Instant.parse("2026-09-03T00:00:00Z")),
                any(), any());
    }

    @Test
    @DisplayName("this week runs Monday to Monday, matching WorkingCalendar's own week")
    void thisWeeksBoundaries() {
        service.items(caller("OB_MANAGER"), "this-weeks-deadlines", null, null, null, null);

        // 2 September 2026 is a Wednesday; its week is 31 Aug (Mon) to 7 Sep (Mon).
        verify(items).page(eq(ObDashboardCardKey.THIS_WEEKS_DEADLINES), any(), isNull(), isNull(), isNull(),
                anyInt(), eq(NOW), any(), any(), any(),
                eq(Instant.parse("2026-08-31T00:00:00Z")), eq(Instant.parse("2026-09-07T00:00:00Z")));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(
                42, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}
