package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · the engine's own decisions — dispatch, the date window, the overrule
 * and the validator — against a runner that records what it was handed.
 *
 * <p>No database. What is worth a container is the SQL, and that is
 * {@code ObReportScopeIT}'s.
 */
class ObReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RecordingRunner runner = new RecordingRunner(JourneyFunnelRunner.KEY);
    private final ObReportService service = new ObReportService(List.of(runner), FIXED);

    // ── dispatch ────────────────────────────────────────────────────────────

    @Test
    void anUnknownKeyIsNotFound() {
        assertThat(run("invented")).isEmpty();
    }

    /**
     * A declared-but-unbuilt key is a 404 too. The catalogue is where "exists
     * but unbuilt" is expressed with a reason a person can read; by the time
     * somebody is running a key there are no rows to describe.
     */
    @Test
    @DisplayName("a report the catalogue declares unavailable is a 404, not an empty report")
    void anUnavailableKeyIsNotFound() {
        // csat-summary is available as of B-119 — breach-log is still one of
        // the OB4b group this assertion actually needs.
        assertThat(run("breach-log")).isEmpty();
        assertThat(run("prereq-aging")).isEmpty();
    }

    /**
     * A card marked available with no runner behind it is a wiring mistake, and
     * it answers 404 rather than 500 — the caller can do nothing either way,
     * and {@code ObReportCatalogueTest} refuses the state at build time.
     */
    @Test
    void anAvailableKeyWithNoRunnerIsNotFound() {
        ObReportService withoutRunners = new ObReportService(List.of(), FIXED);

        assertThat(withoutRunners.run(caller("OB_ADMIN"), JourneyFunnelRunner.KEY,
                null, null, null, null, null, null)).isEmpty();
    }

    @Test
    void aBuiltReportRunsAndEchoesItsKey() {
        ObReportService.Rendered rendered = run(JourneyFunnelRunner.KEY).orElseThrow();

        assertThat(rendered.report().reportKey()).isEqualTo(JourneyFunnelRunner.KEY);
        assertThat(rendered.report().columns()).isEqualTo(runner.columns);
    }

    // ── the window ──────────────────────────────────────────────────────────

    /**
     * Ninety days, not thirty. The prototype has boarding taking 24 to 34
     * working days, so a thirty-day default would open every report on a window
     * shorter than a single completed journey.
     */
    @Test
    void theDefaultWindowIsNinetyDaysBackFromToday() {
        run(JourneyFunnelRunner.KEY);

        // Derived from NOW rather than restating its date. The two literals
        // that stood here said the same thing as the fixed clock only for as
        // long as somebody kept them in step by hand, and the assertion was
        // never the thing that was wrong — the service was reading the system
        // clock instead of this one, so the day moved and the test did not.
        LocalDate today = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(runner.to).isEqualTo(today);
        assertThat(runner.from).isEqualTo(today.minusDays(90));
    }

    @Test
    void aGivenFromAndToAreUsedUnchanged() {
        service.run(caller("OB_ADMIN"), JourneyFunnelRunner.KEY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                null, null, null, null);

        assertThat(runner.from).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(runner.to).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    /**
     * One instant per request, shared by every row and by {@code computedAt} —
     * a runner reading its own clock would produce rows that disagreed with the
     * timestamp printed above them.
     */
    @Test
    void theRunnerAndTheMetaShareOneInstant() {
        ObReportService.Rendered rendered = run(JourneyFunnelRunner.KEY).orElseThrow();

        assertThat(runner.now).isEqualTo(NOW);
        assertThat(rendered.meta().computedAt()).isEqualTo(NOW);
    }

    // ── scope ───────────────────────────────────────────────────────────────

    @Test
    void theOwnerFilterIsOverruledForAStepOwnerBeforeItReachesTheRunner() {
        service.run(caller("OB_STEP_OWNER"), JourneyFunnelRunner.KEY,
                null, null, null, null, 999L, null);

        assertThat(runner.ownerSubject).isEqualTo(7L);
    }

    @Test
    void everybodyElseGetsTheOwnerTheyAskedFor() {
        service.run(caller("OB_MANAGER"), JourneyFunnelRunner.KEY,
                null, null, null, null, 999L, null);

        assertThat(runner.ownerSubject).isEqualTo(999L);
    }

    @Test
    void theAppliedScopeIsOnTheResponseEvenWhenNothingWasNarrowed() {
        assertThat(run(JourneyFunnelRunner.KEY).orElseThrow().meta().appliedScope())
                .isEqualTo("all clients");
    }

    @Test
    void theCatalogueCarriesTheCallersScopeNote() {
        assertThat(service.catalogue(caller("OB_ADMIN")).scopeNote()).isNull();
        assertThat(service.catalogue(caller("OB_SALES")).scopeNote())
                .contains("clients you created");
    }

    /**
     * Every card, whatever the role. A report's rows are narrowed and its
     * existence is not — hiding cards per role would make the hub a second,
     * undocumented copy of the permission matrix.
     */
    @Test
    void thecatalogueIsTheSameTwelveCardsForEveryRole() {
        assertThat(service.catalogue(caller("OB_STEP_OWNER")).reports())
                .isEqualTo(service.catalogue(caller("OB_ADMIN")).reports());
    }

    // ── the validator ───────────────────────────────────────────────────────

    @Test
    void anIdenticalRequestOverIdenticalRowsGetsTheSameValidator() {
        String first = run(JourneyFunnelRunner.KEY).orElseThrow().etag();
        String second = run(JourneyFunnelRunner.KEY).orElseThrow().etag();

        assertThat(first).isNotNull().isEqualTo(second);
    }

    /**
     * The rows are in the hash. These reports are read live with no
     * {@code computed_at} to prove freshness, so a validator built from the
     * request alone would pin a moving answer in every cache between here and
     * the browser.
     */
    @Test
    @DisplayName("changed rows change the validator even though the request did not")
    void theValidatorFollowsTheContent() {
        String before = run(JourneyFunnelRunner.KEY).orElseThrow().etag();

        runner.rows.add(Map.of("product", "Biometric"));
        String after = run(JourneyFunnelRunner.KEY).orElseThrow().etag();

        assertThat(after).isNotEqualTo(before);
    }

    /**
     * Two roles asking the same URL must never share a validator, or a cache
     * hands one of them the other's report after a grant changes.
     */
    @Test
    void theValidatorVariesByScope() {
        String admin = run(JourneyFunnelRunner.KEY).orElseThrow().etag();
        String sales = service.run(caller("OB_SALES"), JourneyFunnelRunner.KEY,
                null, null, null, null, null, null).orElseThrow().etag();

        assertThat(sales).isNotEqualTo(admin);
    }

    @Test
    void theValidatorVariesByFilter() {
        String unfiltered = run(JourneyFunnelRunner.KEY).orElseThrow().etag();
        String filtered = service.run(caller("OB_ADMIN"), JourneyFunnelRunner.KEY,
                null, null, 3L, null, null, null).orElseThrow().etag();

        assertThat(filtered).isNotEqualTo(unfiltered);
    }

    @Test
    void theFiltersReachTheRunnerAsSent() {
        service.run(caller("OB_ADMIN"), JourneyFunnelRunner.KEY,
                null, null, 3L, 4L, null, "AMBER");

        assertThat(runner.filters).isEqualTo(new ObReportFilters(3L, 4L, "AMBER"));
    }

    private Optional<ObReportService.Rendered> run(String key) {
        return service.run(caller("OB_ADMIN"), key, null, null, null, null, null, null);
    }

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(7L, "SUPPORT", List.of(),
                List.of(ModuleAccessGuard.ONBOARDING),
                Map.of(ModuleAccessGuard.ONBOARDING, moduleRole));
    }

    /** Records what the engine handed it, and returns rows a test can move. */
    private static final class RecordingRunner implements ObReportRunner {

        private final String key;
        private final List<ObReportDtos.Column> columns = List.of(
                new ObReportDtos.Column("product", "Product", ObReportDtos.ColumnType.STRING));
        private final List<Map<String, Object>> rows =
                new ArrayList<>(List.of(Map.of("product", "ERP")));

        private LocalDate from;
        private LocalDate to;
        private Instant now;
        private Long ownerSubject;
        private ObReportFilters filters;

        private RecordingRunner(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Result run(ObReportScope scope, LocalDate from, LocalDate to, Instant now,
                          Long ownerSubject, ObReportFilters filters) {
            this.from = from;
            this.to = to;
            this.now = now;
            this.ownerSubject = ownerSubject;
            this.filters = filters;
            return new Result(columns, List.copyOf(rows));
        }
    }
}
