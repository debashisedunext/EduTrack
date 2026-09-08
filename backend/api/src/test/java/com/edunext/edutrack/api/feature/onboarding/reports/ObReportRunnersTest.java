package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-122 · what each runner does with the rows it is given.
 *
 * <p>The SQL is {@code ObReportScopeIT}'s to prove. What is here is the
 * arithmetic and the wording that sits above it — the parts that decide whether
 * a number on screen means what a reader will take it to mean.
 */
class ObReportRunnersTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 6, 8);
    private static final LocalDate TO = LocalDate.of(2026, 9, 6);

    private final ObReportRepository repository = mock(ObReportRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);
    private final ObReportScope scope = new ObReportScope(ObReportScope.OB_ADMIN, 1L);

    @Nested
    class Funnel {

        private final JourneyFunnelRunner runner = new JourneyFunnelRunner(repository);

        @Test
        void aRowCarriesItsProductStepAndBothCounts() {
            when(repository.funnel(any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.FunnelRow("ERP", 1, "Kick-off", 12, 5)));

            Map<String, Object> row = only(runner.run(scope, FROM, TO, NOW, null,
                    ObReportFilters.NONE));

            assertThat(row).containsEntry("product", "ERP")
                    .containsEntry("stepNo", 1)
                    .containsEntry("service", "Kick-off")
                    .containsEntry("journeys", 12L)
                    .containsEntry("locked", 5L);
        }

        /**
         * The gate column is named for what it means to a reader. "Locked" is
         * plan §5.3's state of the <em>journey</em>, and on a funnel row it
         * would read as though the step were locked.
         */
        @Test
        void theGateColumnIsLabelledForWhatItMeans() {
            when(repository.funnel(any(), any(), any(), any())).thenReturn(List.of());

            assertThat(labels(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .contains("Of which awaiting prerequisites");
        }

        @Test
        void theProductFilterIsPassedThrough() {
            when(repository.funnel(any(), any(), any(), any())).thenReturn(List.of());

            runner.run(scope, FROM, TO, NOW, null, new ObReportFilters(3L, null, null));

            verify(repository).funnel(scope, FROM, TO, 3L);
        }
    }

    @Nested
    class TatCompliance {

        private final TatComplianceRunner runner = new TatComplianceRunner(repository);

        @Test
        void thePercentageIsOverTheMeasuredStepsNotEveryCompletedOne() {
            when(repository.tatCompliance(any(), any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.TatRow("ERP", "Data migration", "Ravi", 10, 4, 3)));

            Map<String, Object> row = only(runner.run(scope, FROM, TO, NOW, null,
                    ObReportFilters.NONE));

            assertThat(row).containsEntry("completed", 10L)
                    .containsEntry("measured", 4L)
                    .containsEntry("onTime", 3L)
                    .containsEntry("onTimePct", BigDecimal.valueOf(75));
        }

        /**
         * Null, not zero. 0% claims every measured step was late, and there
         * were none — which on today's data, with C-105's clock unmerged and
         * {@code due_at} empty, is the whole table.
         */
        @Test
        @DisplayName("nothing measurable reports no percentage rather than nought percent")
        void anUnmeasurableRowReportsNull() {
            when(repository.tatCompliance(any(), any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.TatRow("ERP", "Kick-off", "Ravi", 6, 0, 0)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("completed", 6L)
                    .containsEntry("onTimePct", null);
        }

        /**
         * Worst first, unmeasured last — the report exists to produce the
         * sentence "data migration is the bottleneck", and "we have no data"
         * must not sit where "we are failing" belongs.
         */
        @Test
        void rowsAreSortedWorstFirstWithUnmeasurableRowsLast() {
            when(repository.tatCompliance(any(), any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.TatRow("ERP", "Kick-off", "Ravi", 10, 10, 9),
                    new ObReportRepository.TatRow("ERP", "Untimed", "Ravi", 3, 0, 0),
                    new ObReportRepository.TatRow("ERP", "Migration", "Meera", 10, 10, 5)));

            assertThat(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE).rows())
                    .extracting(row -> row.get("service"))
                    .containsExactly("Migration", "Kick-off", "Untimed");
        }

        @Test
        void aStepWithNoOwnerIsLabelledRatherThanBlank() {
            when(repository.tatCompliance(any(), any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.TatRow("ERP", "Kick-off", null, 1, 1, 1)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("owner", "Unassigned");
        }

        /**
         * The already-overruled subject, never the caller's raw parameter —
         * the failure {@code ObReportRunner} records five ticketing runners
         * having shipped.
         */
        @Test
        void theResolvedOwnerSubjectReachesTheQuery() {
            when(repository.tatCompliance(any(), any(), any(), any(), any())).thenReturn(List.of());

            runner.run(scope, FROM, TO, NOW, 42L, ObReportFilters.NONE);

            verify(repository).tatCompliance(scope, FROM, TO, null, 42L);
        }
    }

    @Nested
    class StuckAndAging {

        private final StuckAndAgingRunner runner =
                new StuckAndAgingRunner(repository, workingHours);

        private final Instant started = NOW.minus(Duration.ofDays(5));
        private final Instant overdue = NOW.minus(Duration.ofDays(1));

        @Test
        void aBlockedRowCarriesItsCodeAndNoteTogether() {
            stub(row("BLOCKED", "AWAITING_DATA", "Client has not sent the fee heads", null, null));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("state", "Blocked")
                    .containsEntry("reason", "AWAITING_DATA — Client has not sent the fee heads");
        }

        /**
         * An empty Reason cell on a report about why things are stuck reads as
         * missing data, so the state supplies its own sentence.
         */
        @Test
        void aWaitingRowExplainsItselfWhenThereIsNoBlockReason() {
            stub(row("WAITING_ON_CLIENT", null, null, "CLIENT", NOW.minus(Duration.ofDays(2))));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("state", "Waiting on client")
                    .containsEntry("clock", "Client time")
                    .containsEntry("reason", "Waiting on client input");
        }

        /**
         * Never started and running against us are different claims, and the
         * second is an accusation.
         */
        @Test
        void aStepWithNoClockEventReportsNoAttribution() {
            stub(row("IN_PROGRESS", null, null, null, null));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("clock", null);
        }

        /**
         * Working hours, not wall-clock: a step that missed a Friday deadline
         * is not two days late on Sunday morning.
         */
        @Test
        void overdueTimeGoesThroughTheWorkingCalendar() {
            stub(row("IN_PROGRESS", null, null, "INTERNAL", null));
            when(workingHours.workingHoursBetween(overdue, NOW))
                    .thenReturn(new BigDecimal("6.50"));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("overdueBy", new BigDecimal("6.50"));
            verify(workingHours).workingHoursBetween(overdue, NOW);
        }

        /** Zero would be a measurement saying the deadline passed this instant. */
        @Test
        void aStepInsideItsTatReportsNoOverdueTime() {
            stub(new ObReportRepository.StuckRow("Horizon", "ERP", "Kick-off", "BLOCKED",
                    "Ravi", "AWAITING_DATA", null, started, NOW.plus(Duration.ofDays(2)),
                    "INTERNAL", null));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("overdueBy", null);
        }

        @Test
        void theRagChipComesFromTheSharedFormula() {
            stub(row("IN_PROGRESS", null, null, "INTERNAL", null));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("rag", ObStepRag.RED);
        }

        /**
         * The filter selects on the value the chip shows, because both read one
         * formula — a SQL predicate beside a Java formula would eventually
         * return an AMBER row under a RED filter.
         */
        @Test
        void theRagFilterSelectsOnTheSameValueTheChipShows() {
            stub(row("IN_PROGRESS", null, null, "INTERNAL", null));

            assertThat(runner.run(scope, FROM, TO, NOW, null,
                    new ObReportFilters(null, null, "RED")).rows()).hasSize(1);
            assertThat(runner.run(scope, FROM, TO, NOW, null,
                    new ObReportFilters(null, null, "GREEN")).rows()).isEmpty();
        }

        private void stub(ObReportRepository.StuckRow... rows) {
            when(repository.stuckAndAging(any(), any(), any(), any(), eq(NOW)))
                    .thenReturn(List.of(rows));
        }

        private ObReportRepository.StuckRow row(String status, String code, String note,
                                                String clock, Instant clockSince) {
            return new ObReportRepository.StuckRow("Horizon", "ERP", "Data migration", status,
                    "Ravi", code, note, started, overdue, clock, clockSince);
        }
    }

    @Nested
    class TimeToLive {

        private final TimeToLiveRunner runner = new TimeToLiveRunner(repository, workingHours);

        @Test
        void journeysAreAveragedPerMonthAndProductInWorkingHours() {
            Instant raisedA = Instant.parse("2026-07-01T09:00:00Z");
            Instant liveA = Instant.parse("2026-08-01T09:00:00Z");
            Instant raisedB = Instant.parse("2026-07-10T09:00:00Z");
            Instant liveB = Instant.parse("2026-08-05T09:00:00Z");

            when(repository.completedJourneys(any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.CompletedJourney("ERP", "2026-08", raisedA, liveA),
                    new ObReportRepository.CompletedJourney("ERP", "2026-08", raisedB, liveB)));
            when(workingHours.workingHoursBetween(raisedA, liveA)).thenReturn(new BigDecimal("160.00"));
            when(workingHours.workingHoursBetween(raisedB, liveB)).thenReturn(new BigDecimal("140.00"));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("month", "2026-08")
                    .containsEntry("product", "ERP")
                    .containsEntry("wentLive", 2L)
                    .containsEntry("averageTimeToLive", new BigDecimal("150.00"));
        }

        /** A trend line has to come out chronologically whatever order SQL returned. */
        @Test
        void monthsComeOutInOrder() {
            Instant any = Instant.parse("2026-07-01T09:00:00Z");
            when(repository.completedJourneys(any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.CompletedJourney("ERP", "2026-08", any, any),
                    new ObReportRepository.CompletedJourney("ERP", "2026-06", any, any),
                    new ObReportRepository.CompletedJourney("ERP", "2026-07", any, any)));
            when(workingHours.workingHoursBetween(any(), any())).thenReturn(BigDecimal.TEN);

            assertThat(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE).rows())
                    .extracting(row -> row.get("month"))
                    .containsExactly("2026-06", "2026-07", "2026-08");
        }

        /**
         * Measured from when the journey was raised, so the prerequisite gate
         * wait is inside the figure — the client counts from signing.
         */
        @Test
        void theDurationRunsFromTheJourneyBeingRaisedRatherThanStarted() {
            Instant raised = Instant.parse("2026-07-01T09:00:00Z");
            Instant live = Instant.parse("2026-08-01T09:00:00Z");
            when(repository.completedJourneys(any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.CompletedJourney("ERP", "2026-08", raised, live)));
            when(workingHours.workingHoursBetween(raised, live)).thenReturn(BigDecimal.TEN);

            runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE);

            verify(workingHours).workingHoursBetween(raised, live);
        }
    }

    @Nested
    class SignoffPending {

        private final SignoffPendingRunner runner =
                new SignoffPendingRunner(repository, workingHours);

        private final Instant requested = NOW.minus(Duration.ofDays(9));

        /**
         * {@code EXPIRED} is reached by time rather than by an operation, so a
         * row is PENDING in the table long after its link died. A list showing
         * those as merely awaiting the client has somebody chasing a client who
         * cannot act.
         */
        @Test
        @DisplayName("a pending row past its TTL is reported as an expired link")
        void anExpiredLinkIsNamedRatherThanLeftLookingLive() {
            stub(NOW.minus(Duration.ofDays(2)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("linkState", "Expired")
                    .containsEntry("expiresOn", LocalDate.of(2026, 9, 4));
        }

        @Test
        void aLiveLinkSaysSo() {
            stub(NOW.plus(Duration.ofDays(2)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("linkState", "Live");
        }

        @Test
        void theWaitGoesThroughTheWorkingCalendar() {
            stub(NOW.plus(Duration.ofDays(2)));
            when(workingHours.workingHoursBetween(requested, NOW))
                    .thenReturn(new BigDecimal("48.00"));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("waitingFor", new BigDecimal("48.00"));
        }

        @Test
        void aGoLiveSignoffIsNamedInWordsRatherThanInTheEnum() {
            stub(NOW.plus(Duration.ofDays(2)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("kind", "Go-live");
        }

        @Test
        void theClientFilterIsPassedThrough() {
            when(repository.pendingSignoffs(any(), any(), any(), anyLong())).thenReturn(List.of());

            runner.run(scope, FROM, TO, NOW, null, new ObReportFilters(null, 8L, null));

            verify(repository).pendingSignoffs(scope, FROM, TO, 8L);
        }

        private void stub(Instant expiresAt) {
            when(repository.pendingSignoffs(any(), any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.PendingSignoff("Horizon", "Go-live", "GO_LIVE",
                            "Anita Rao", "anita@horizon.example", requested, expiresAt)));
        }
    }

    @Nested
    class SalesPipeline {

        private final SalesPipelineRunner runner = new SalesPipelineRunner(repository);

        @Test
        void aRowCarriesEveryStatusAndTheLiveShare() {
            when(repository.salesPipeline(any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.PipelineRow("Ravi", 20, 5, 10, 2, 3)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("boarded", 20L)
                    .containsEntry("live", 5L)
                    .containsEntry("dropped", 3L)
                    .containsEntry("livePct", BigDecimal.valueOf(25));
        }

        /**
         * Unattributed intake is exactly what somebody reading a pipeline
         * report would want to fix, so it gets a row rather than disappearing.
         */
        @Test
        void clientsWithNoSalesPersonKeepARowOfTheirOwn() {
            when(repository.salesPipeline(any(), any(), any())).thenReturn(List.of(
                    new ObReportRepository.PipelineRow(null, 4, 1, 3, 0, 0)));

            assertThat(only(runner.run(scope, FROM, TO, NOW, null, ObReportFilters.NONE)))
                    .containsEntry("salesPerson", "Unassigned")
                    .containsEntry("boarded", 4L);
        }
    }

    private static Map<String, Object> only(ObReportRunner.Result result) {
        assertThat(result.rows()).hasSize(1);
        return result.rows().get(0);
    }

    private static List<String> labels(ObReportRunner.Result result) {
        return result.columns().stream().map(ObReportDtos.Column::label).toList();
    }
}
