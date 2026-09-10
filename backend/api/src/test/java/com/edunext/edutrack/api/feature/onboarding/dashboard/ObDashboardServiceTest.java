package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDashboardCard;
import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-121 · the board's arithmetic, without a database.
 *
 * <p>The repository is mocked because what is under test is what happens
 * <em>around</em> the SQL: which days a delta compares, which cards admit they
 * are upper bounds, what a caller with an unanswerable scope receives, and
 * whether two roles can share an ETag. {@code ObDashboardSummaryIT} covers the
 * SQL itself.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObDashboardServiceTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);
    private static final Instant COMPUTED = Instant.parse("2026-09-02T06:00:00Z");

    private final ObDashboardSummaryRepository repository = mock(ObDashboardSummaryRepository.class);
    private final ObScopeDashboardSummaryRepository scopedRepository =
            mock(ObScopeDashboardSummaryRepository.class);
    private final ObDashboardService service = new ObDashboardService(repository, scopedRepository);

    // ── the counts ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("all seven cards are returned, in board order, zeroes included")
    void everyCardIsAlwaysPresent() {
        givenDays(List.of(WEDNESDAY));
        givenRollup(WEDNESDAY, counts(4, 0, 0, 0, 0, 0, 0));

        var cards = service.summary(manager(), null).summary().cards();

        // An absent card and a card reading nought are different claims, and
        // only one of them is true when nothing is overdue.
        assertThat(cards).extracting(ObDashboardCard::key)
                .containsExactly(ObDashboardCardKey.values());
        assertThat(cards).extracting(ObDashboardCard::count)
                .containsExactly(4L, 0L, 0L, 0L, 0L, 0L, 0L);
    }

    // ── the delta ───────────────────────────────────────────────────────────

    /**
     * The delta compares the two most recent <em>stored</em> days. A worker
     * that was down on Tuesday leaves no Tuesday row, and asking for
     * {@code latest - 1 day} would find nothing and report every delta as null
     * — which reads as "we have never had a previous day" rather than as
     * "Tuesday is missing".
     */
    @Test
    @DisplayName("a gap in the series compares against the day before the gap, not against nothing")
    void theDeltaSkipsAMissingDay() {
        givenDays(List.of(WEDNESDAY, MONDAY));
        givenRollup(WEDNESDAY, counts(10, 0, 0, 0, 0, 0, 0));
        givenRollup(MONDAY, counts(7, 0, 0, 0, 0, 0, 0));

        var cards = service.summary(manager(), null).summary().cards();

        assertThat(card(cards, ObDashboardCardKey.ONGOING_PROJECTS).deltaFromYesterday())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("a fall is a negative delta, not an absolute difference")
    void theDeltaHasDirection() {
        givenDays(List.of(WEDNESDAY, MONDAY));
        givenRollup(WEDNESDAY, counts(0, 0, 0, 2, 0, 0, 0));
        givenRollup(MONDAY, counts(0, 0, 0, 5, 0, 0, 0));

        var cards = service.summary(manager(), 3L).summary().cards();

        // Three fewer clients are overdue than on Monday. Reported as -3; an
        // absolute difference would draw the same arrow for progress and for
        // collapse.
        assertThat(card(cards, ObDashboardCardKey.OVERDUE_CLIENTS).deltaFromYesterday())
                .isEqualTo(-3L);
    }

    @Test
    @DisplayName("the first day a deployment has data reports no delta rather than zero")
    void theFirstDayHasNoDirection() {
        givenDays(List.of(WEDNESDAY));
        givenRollup(WEDNESDAY, counts(10, 0, 0, 0, 0, 0, 0));

        var cards = service.summary(manager(), null).summary().cards();

        // Null, not 0. A zero is a claim that nothing moved.
        assertThat(cards).allSatisfy(one ->
                assertThat(one.deltaFromYesterday()).isNull());
        verify(repository, never()).rollup(eq(MONDAY), any());
    }

    // ── the upper bound ─────────────────────────────────────────────────────

    /**
     * The defect this flag exists for. B-120 stores the three client-counted
     * columns as {@code COUNT(DISTINCT client)} <em>per product</em>, so a
     * client who bought two products and is late on both contributes 1 to each
     * row and the sum says 2. Not recoverable from this table; the flag is what
     * stops the board overstating quietly.
     */
    @Test
    @DisplayName("on the all-products board the three client-counted cards admit they may overstate")
    void clientCountedCardsAreUpperBoundsWhenSummed() {
        givenDays(List.of(WEDNESDAY));
        givenRollup(WEDNESDAY, counts(9, 9, 9, 9, 9, 9, 9));

        var cards = service.summary(manager(), null).summary().cards();

        assertThat(cards).filteredOn(ObDashboardCard::countIsUpperBound)
                .extracting(one -> one.key().wireName())
                .containsExactlyInAnyOrder("overdue-clients", "live", "client-escalations");
    }

    @Test
    @DisplayName("with a product selected nothing is summed, so every card is exact")
    void oneProductIsAlwaysExact() {
        givenDays(List.of(WEDNESDAY));
        givenRollup(WEDNESDAY, counts(9, 9, 9, 9, 9, 9, 9));

        var cards = service.summary(manager(), 3L).summary().cards();

        assertThat(cards).noneMatch(ObDashboardCard::countIsUpperBound);
    }

    // ── the two narrowed scopes, answered from their own table ──────────────

    /**
     * The property that matters most in this file: a narrowed caller's figures
     * come from {@code ob_scope_dashboard_summary} and the org-wide table is
     * <b>never touched</b>. Reading the org-wide board for a Step Owner would
     * disclose the size of the book to somebody scoped out of most of it, and
     * nothing on screen would look wrong — so the absence of that call is
     * asserted rather than inferred from the numbers.
     */
    @Test
    @DisplayName("a narrowed role is answered from the scoped table, never the org-wide one")
    void aNarrowedScopeReadsItsOwnTable() {
        givenScopedDays(List.of(WEDNESDAY));
        givenScopedRollup(WEDNESDAY, counts(6, 5, 4, 3, 2, 1, 0));

        var rendered = service.summary(stepOwner(), null);

        assertThat(rendered.summary().cards()).extracting(ObDashboardCard::count)
                .containsExactly(6L, 5L, 4L, 3L, 2L, 1L, 0L);
        assertThat(rendered.summary().cards())
                .allSatisfy(one -> assertThat(one.unavailableReason()).isNull());
        assertThat(rendered.summary().appliedScope())
                .isEqualTo("journeys containing your services");
        assertThat(rendered.summary().computedAt()).isEqualTo(COMPUTED);
        verifyNoInteractions(repository);
    }

    /**
     * The scoped table is keyed by caller, so the caller has to reach the
     * query. Passing the wrong id — or none — would hand one Step Owner
     * another's board, which is the one way this feature can leak.
     */
    @Test
    void the_scoped_read_is_keyed_by_the_caller() {
        givenScopedDays(List.of(WEDNESDAY));
        givenScopedRollup(WEDNESDAY, counts(1, 0, 0, 0, 0, 0, 0));

        service.summary(stepOwner(), 3L);

        verify(scopedRepository).rollup(WEDNESDAY, 42L, 3L);
    }

    /**
     * Days exist, this caller has no row. The refresh writes none for an empty
     * scope on purpose, so that "nothing is yours yet" stays separable from
     * "the job has never run" — and neither is a board of zeroes, which would
     * claim nothing of theirs is overdue.
     */
    @Test
    void a_caller_with_nothing_in_scope_is_told_so_rather_than_shown_zeroes() {
        givenScopedDays(List.of(WEDNESDAY));
        when(scopedRepository.rollup(eq(WEDNESDAY), eq(42L), any())).thenReturn(Optional.empty());

        var summary = service.summary(stepOwner(), null).summary();

        assertThat(summary.computedAt()).isNull();
        assertThat(summary.cards()).allSatisfy(one -> {
            assertThat(one.count()).isZero();
            assertThat(one.unavailableReason()).contains("Nothing is in your scope yet");
            assertThat(one.unavailableReason()).doesNotContain("No summary has been computed");
        });
    }

    /**
     * A caller holding the module but no recognised role in it. A-111's gate
     * answers 404 before this in production; the branch exists so the service
     * does not depend on a guard elsewhere having run, and no query is issued
     * because none would have helped.
     */
    @Test
    @DisplayName("an unrecognised module role gets seven sentences and no query")
    void anUnrecognisedRoleIsToldRatherThanApproximated() {
        var rendered = service.summary(caller("TICKETING_MEMBER"), null);

        assertThat(rendered.summary().cards())
                .hasSize(ObDashboardCardKey.values().length)
                .allSatisfy(one -> {
                    assertThat(one.unavailableReason()).contains("no onboarding role");
                    assertThat(one.count()).isZero();
                    assertThat(one.countIsUpperBound()).isFalse();
                });
        assertThat(rendered.summary().computedAt()).isNull();
        assertThat(rendered.etag()).isNull();
        verifyNoInteractions(repository);
        verifyNoInteractions(scopedRepository);
    }

    /**
     * No ETag on an unavailable board. The state changes when a grant changes,
     * and there is no {@code computed_at} to prove it — a stable validator
     * would pin the refusal on screen until the URL changed.
     */
    @Test
    void an_unavailable_board_carries_no_validator() {
        assertThat(service.summary(caller("TICKETING_MEMBER"), null).etag()).isNull();
    }

    // ── before the first refresh ────────────────────────────────────────────

    @Test
    @DisplayName("an empty table reports never-computed rather than a board of zeroes")
    void beforeTheFirstRefreshTheBoardSaysSo() {
        givenDays(List.of());

        var rendered = service.summary(manager(), null);

        assertThat(rendered.summary().computedAt()).isNull();
        assertThat(rendered.etag()).isNull();
        assertThat(rendered.summary().cards()).allSatisfy(one ->
                assertThat(one.unavailableReason()).contains("No summary has been computed"));
    }

    /**
     * A day the table lists but has no row for under this product. The bare
     * aggregate still returns a row with every column NULL, the repository
     * turns that into empty, and this asserts the service does not then render
     * it as a zeroed board.
     *
     * <p>It is also told apart from never-computed. Both are boards with no
     * figures, and conflating them would tell somebody whose product has simply
     * been quiet that the refresh has never run — which sends them to look at
     * the worker instead of at the product picker.
     */
    @Test
    void a_product_with_no_row_on_the_latest_day_is_not_a_board_of_zeroes() {
        givenDays(List.of(WEDNESDAY));
        when(repository.rollup(WEDNESDAY, 3L)).thenReturn(Optional.empty());

        var summary = service.summary(manager(), 3L).summary();

        assertThat(summary.computedAt()).isNull();
        assertThat(summary.cards()).allSatisfy(one -> {
            assertThat(one.count()).isZero();
            assertThat(one.unavailableReason()).contains("nothing for this product");
            assertThat(one.unavailableReason()).doesNotContain("No summary has been computed");
        });
    }

    // ── the validator ───────────────────────────────────────────────────────

    /**
     * The scope is in the hash, not only the URL. Two callers with different
     * onboarding roles ask the same URL and must not share a validator, or an
     * intermediary — or a browser cache after a grant change — hands one of
     * them the other's board.
     */
    @Test
    @DisplayName("two roles asking the same URL do not share a validator")
    void theEtagCoversTheScope() {
        String admin = ObDashboardService.etagOf(
                new ObDashboardScope(true, "OB_ADMIN"), null, COMPUTED);
        String viewer = ObDashboardService.etagOf(
                new ObDashboardScope(true, "OB_VIEWER"), null, COMPUTED);

        assertThat(admin).isNotEqualTo(viewer);
    }

    @Test
    void the_etag_covers_the_product_filter() {
        var scope = new ObDashboardScope(true, "OB_ADMIN");

        assertThat(ObDashboardService.etagOf(scope, null, COMPUTED))
                .isNotEqualTo(ObDashboardService.etagOf(scope, 3L, COMPUTED));
    }

    @Test
    @DisplayName("the validator moves when the refresh does, and not otherwise")
    void theEtagTracksComputedAt() {
        var scope = new ObDashboardScope(true, "OB_ADMIN");

        assertThat(ObDashboardService.etagOf(scope, null, COMPUTED))
                .isEqualTo(ObDashboardService.etagOf(scope, null, COMPUTED))
                .isNotEqualTo(ObDashboardService.etagOf(scope, null, COMPUTED.plusSeconds(300)));
    }

    /**
     * Two Step Owners share a role, a product filter and — because one refresh
     * pass stamps every row it writes with one instant — a {@code computedAt},
     * while seeing genuinely different figures. Before the scoped table they
     * both received a null validator and could not collide; now the caller has
     * to be in the hash, or a cache shared between them hands one the other's
     * board. The disclosure `unrestricted` exists to prevent, arriving through
     * the validator instead of through the query.
     */
    @Test
    @DisplayName("two narrowed callers never share a validator")
    void narrowedCallersDoNotShareAnEtag() {
        var one = new ObDashboardScope(false, "OB_STEP_OWNER", 42L);
        var other = new ObDashboardScope(false, "OB_STEP_OWNER", 77L);

        assertThat(ObDashboardService.etagOf(one, null, COMPUTED))
                .isNotEqualTo(ObDashboardService.etagOf(other, null, COMPUTED));
    }

    /**
     * The mirror of the case above. An unrestricted caller's board provably
     * does not depend on who is asking, so two of them go on sharing a
     * validator rather than fragmenting every cache entry by user id.
     */
    @Test
    void two_unrestricted_callers_still_share_a_validator() {
        var one = new ObDashboardScope(true, "OB_ADMIN", 42L);
        var other = new ObDashboardScope(true, "OB_ADMIN", 77L);

        assertThat(ObDashboardService.etagOf(one, null, COMPUTED))
                .isEqualTo(ObDashboardService.etagOf(other, null, COMPUTED));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void givenDays(List<LocalDate> days) {
        when(repository.recentDays(any())).thenReturn(days);
    }

    private void givenRollup(LocalDate day, Map<ObDashboardCardKey, Long> counts) {
        when(repository.rollup(eq(day), any()))
                .thenReturn(Optional.of(new ObDashboardSummaryRepository.Rollup(day, COMPUTED, counts)));
    }

    private void givenScopedDays(List<LocalDate> days) {
        when(scopedRepository.recentDays(any())).thenReturn(days);
    }

    private void givenScopedRollup(LocalDate day, Map<ObDashboardCardKey, Long> counts) {
        when(scopedRepository.rollup(eq(day), eq(42L), any()))
                .thenReturn(Optional.of(new ObDashboardSummaryRepository.Rollup(day, COMPUTED, counts)));
    }

    /** Seven figures in board order, so a case reads as the board it describes. */
    private static Map<ObDashboardCardKey, Long> counts(long... values) {
        Map<ObDashboardCardKey, Long> counts = new EnumMap<>(ObDashboardCardKey.class);
        ObDashboardCardKey[] keys = ObDashboardCardKey.values();
        for (int i = 0; i < keys.length; i++) {
            counts.put(keys[i], values[i]);
        }
        return counts;
    }

    private static ObDashboardCard card(List<ObDashboardCard> cards, ObDashboardCardKey key) {
        return cards.stream().filter(one -> one.key() == key).findFirst().orElseThrow();
    }

    private static CallerIdentity manager() {
        return caller("OB_MANAGER");
    }

    private static CallerIdentity stepOwner() {
        return caller("OB_STEP_OWNER");
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(
                42, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }
}
