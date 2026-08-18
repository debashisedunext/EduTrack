package com.edunext.edutrack.worker.digest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-038 · the subject line is most of the digest.
 *
 * <p>A digest is read in a list of subject lines and often nowhere else, so
 * what the subject says is the feature — not a label on it. §4B.6 writes both
 * of these out by hand and they are transcribed rather than invented.
 */
class DigestSubjectTest {

    private static DigestRepository.Assignee assignee(int open, int dueToday, int overdue) {
        return new DigestRepository.Assignee(1L, "a@b.test", "A", open, dueToday, overdue);
    }

    @Test
    @DisplayName("§4B.6's example: 'Your open tickets — 4 due today, 1 overdue'")
    void theBlueprintsOwnExample() {
        assertThat(DigestScheduler.dailySubject(assignee(9, 4, 1)))
                .isEqualTo("Your open tickets — 9 open, 4 due today, 1 overdue");
    }

    @Test
    @DisplayName("a zero is left out, never printed as '0 overdue'")
    void absenceIsNotNews() {
        // Printing "0 overdue" spends the reader's attention on nothing, every
        // day, which is how a digest becomes something people filter — and then
        // the one that mattered is filtered too.
        assertThat(DigestScheduler.dailySubject(assignee(3, 0, 0)))
                .isEqualTo("Your open tickets — 3 open");
        assertThat(DigestScheduler.dailySubject(assignee(3, 0, 2)))
                .isEqualTo("Your open tickets — 3 open, 2 overdue");
    }

    @Test
    @DisplayName("§4B.6's weekly example: 'Team summary — week of 03 Aug'")
    void theWeeklyExample() {
        var manager = new DigestRepository.Manager(1L, "pm@b.test", "PM", 12, 0, 0);

        assertThat(DigestScheduler.weeklySubject(manager, LocalDate.of(2026, 8, 3)))
                .startsWith("Team summary — week of 03 Aug");
    }

    @Test
    @DisplayName("the weekly subject leads with what a manager acts on")
    void theWeeklyCarriesTheTwoFiguresThatMatter() {
        var manager = new DigestRepository.Manager(1L, "pm@b.test", "PM", 12, 3, 2);

        assertThat(DigestScheduler.weeklySubject(manager, LocalDate.of(2026, 8, 3)))
                .isEqualTo("Team summary — week of 03 Aug · 12 open, 3 overdue, 2 critical");
    }
}
