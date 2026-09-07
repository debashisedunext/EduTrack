package com.edunext.edutrack.worker.onboarding.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-120 · the day boundaries every card is filtered by.
 *
 * <p>Worth its own test because the whole point of the record is that
 * {@code stat_date} is the <em>organisation's</em> day, and every assertion here
 * is one that would still pass if the zone were quietly ignored — except that
 * the expected instants are all 18:30 the previous day, which is what an
 * IST-aware boundary looks like and a UTC one never does. A "due today" card
 * computed against UTC is wrong by five and a half hours in exactly one
 * direction, so it is right for two thirds of the day and nobody notices which
 * two thirds.
 */
class ObStatsDayTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Wednesday. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 12);

    @Test
    @DisplayName("the day runs midnight to midnight in the calendar's zone, not UTC's")
    void dayBoundariesAreInTheCalendarsZone() {
        ObStatsDay day = ObStatsDay.of(WEDNESDAY, IST);

        assertThat(day.date()).isEqualTo(WEDNESDAY);
        assertThat(day.start()).isEqualTo(Instant.parse("2026-08-11T18:30:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-08-12T18:30:00Z"));
    }

    @Test
    @DisplayName("a step due just after IST midnight belongs to the new day, not the old one")
    void theBoundaryIsWhereTheOrganisationSaysItIs() {
        ObStatsDay day = ObStatsDay.of(WEDNESDAY, IST);

        // 00:30 IST on the 12th. UTC still calls this the 11th.
        Instant justAfterMidnight = Instant.parse("2026-08-11T19:00:00Z");
        assertThat(justAfterMidnight).isAfterOrEqualTo(day.start()).isBefore(day.end());

        // 01:30 IST on the 13th. UTC still calls this the 12th.
        Instant justAfterTheNextMidnight = Instant.parse("2026-08-12T20:00:00Z");
        assertThat(justAfterTheNextMidnight).isAfterOrEqualTo(day.end());
    }

    @Test
    @DisplayName("the week runs Monday to Sunday, matching the working calendar's weekly-off pattern")
    void weekStartsOnMonday() {
        ObStatsDay day = ObStatsDay.of(WEDNESDAY, IST);

        // Monday 10 August, IST midnight.
        assertThat(day.weekStart()).isEqualTo(Instant.parse("2026-08-09T18:30:00Z"));
        // Exclusive: Monday 17 August, IST midnight.
        assertThat(day.weekEnd()).isEqualTo(Instant.parse("2026-08-16T18:30:00Z"));
    }

    @Test
    @DisplayName("Monday is the first day of its own week, not the last of the previous one")
    void mondayIsItsOwnWeekStart() {
        LocalDate monday = LocalDate.of(2026, 8, 10);

        ObStatsDay day = ObStatsDay.of(monday, IST);

        assertThat(day.weekStart()).isEqualTo(day.start());
    }

    @Test
    @DisplayName("Sunday still belongs to the week that began the previous Monday")
    void sundayClosesTheWeekRatherThanOpeningOne() {
        LocalDate sunday = LocalDate.of(2026, 8, 16);

        ObStatsDay day = ObStatsDay.of(sunday, IST);

        assertThat(day.weekStart()).isEqualTo(Instant.parse("2026-08-09T18:30:00Z"));
        assertThat(day.end()).isEqualTo(day.weekEnd());
    }

    @Test
    @DisplayName("the offset is the one SQL adds before DATE(), in seconds")
    void offsetIsCarriedForTheEarlyAndLateClassification() {
        assertThat(ObStatsDay.of(WEDNESDAY, IST).zoneOffsetSeconds()).isEqualTo(5 * 3600 + 1800);
        assertThat(ObStatsDay.of(WEDNESDAY, ZoneOffset.UTC).zoneOffsetSeconds()).isZero();
    }

    @Test
    @DisplayName("a UTC calendar gives plain midnight boundaries")
    void utcIsNotASpecialCase() {
        ObStatsDay day = ObStatsDay.of(WEDNESDAY, ZoneOffset.UTC);

        assertThat(day.start()).isEqualTo(Instant.parse("2026-08-12T00:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));
    }
}
