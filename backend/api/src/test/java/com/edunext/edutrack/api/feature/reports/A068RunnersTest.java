package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.domain.journal.ChainDigest;
import com.edunext.edutrack.domain.journal.ChainPayloads;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A-068 · the judgements the five new runners make on rows they are handed,
 * without a database.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * <p>The SQL belongs in an integration test — a bound that drops a day and a
 * scope clause that lets somebody else's rows in are invisible to a mock, and
 * {@code ReportRunnersIT} says as much for the first six reports.
 *
 * <p>What a mock <em>can</em> pin is the part of each runner that decides what a
 * figure means: the cases where the arithmetically obvious answer is a false
 * statement. Every test below is one of those, and each corresponds to a
 * specific way this task could have shipped a confident wrong number.
 */
class A068RunnersTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    /** Admin: unscoped, sees everything. */
    private static final ReportScope ADMIN = new ReportScope(false, 1L, List.of());

    @Nested
    @DisplayName("rework analysis")
    class Rework {

        @Test
        @DisplayName("first-time-right is withheld when no backward move was seen at all")
        void ftrWithheldWithoutEvidence() {
            // The defect this exists to prevent, and it is the most flattering
            // wrong answer in the product. current_iteration lives on `tickets`
            // and defaults to 1, while backward moves live on the transitions —
            // and nothing in a running application writes a transition, because
            // no ticket's first hop is ever opened. So the naive report shows
            // "100% first-time-right" beside an empty bounce table, and the two
            // halves come from different tables with only one populated.
            //
            // 100% here would not be a rounding error. It is a claim that the
            // team never has work sent back, published to the people who decide
            // whether the process is working.
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.reworkAnalysis(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of());
            when(tickets.firstTimeRight(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(new TicketReportRepository.FirstTimeRightRow("Apollo", 40, 40)));

            ReportRunner.Result result =
                    new ReworkAnalysisRunner(tickets).run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("closed")).isEqualTo(40L);
            assertThat(result.rows().get(0).get("firstTimeRight"))
                    .describedAs("40 of 40 closed at iteration 1 is only 100%% if rework is being recorded")
                    .isNull();
        }

        @Test
        @DisplayName("first-time-right is computed once a backward move proves rework is recorded")
        void ftrComputedWithEvidence() {
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.reworkAnalysis(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(new TicketReportRepository.ReworkRow(
                            "Priya", "Apollo", "QA", "DEV", 3, 2)));
            when(tickets.firstTimeRight(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(new TicketReportRepository.FirstTimeRightRow("Apollo", 40, 30)));

            ReportRunner.Result result =
                    new ReworkAnalysisRunner(tickets).run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            Map<String, Object> ftrRow = result.rows().get(result.rows().size() - 1);
            assertThat(ftrRow.get("firstTimeRight")).isEqualTo(BigDecimal.valueOf(75.0).setScale(1));
        }

        @Test
        @DisplayName("a bounce row carries no project total, so nothing invites summing it")
        void bounceRowsCarryNoProjectTotals() {
            // The closed count is per project. Repeated onto each of a project's
            // stage-pair rows it would be summed by any reader building a total,
            // and the answer would be the closed count times the number of stage
            // pairs — a plausible, large, wrong number.
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.reworkAnalysis(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(
                            new TicketReportRepository.ReworkRow("Priya", "Apollo", "QA", "DEV", 3, 2),
                            new TicketReportRepository.ReworkRow("Sam", "Apollo", "VERIFY", "DEV", 1, 1)));
            when(tickets.firstTimeRight(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(new TicketReportRepository.FirstTimeRightRow("Apollo", 40, 30)));

            ReportRunner.Result result =
                    new ReworkAnalysisRunner(tickets).run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows().subList(0, 2))
                    .allSatisfy(row -> assertThat(row.get("closed")).isNull());
        }

        @Test
        @DisplayName("the runner's backward action codes are the ones the query filters on")
        void backwardActionsAgreeWithTheQuery() {
            // The set is stated twice on purpose — once as a Java constant for
            // anyone reading the runner, once as SQL literals because the
            // database needs literals. Duplication is acceptable when it is
            // checked; this is the check. A fifth backward code added to
            // TransitionService and to the SQL but not here, or the reverse,
            // turns this red.
            assertThat(ReworkAnalysisRunner.BACKWARD_ACTIONS)
                    .containsExactlyInAnyOrder("REWORK", "VERIFY_FAILED", "DEPLOY_FAILED", "SIGNOFF_REJECTED");
        }
    }

    @Nested
    @DisplayName("deployment report")
    class Deployment {

        @Test
        @DisplayName("shipped and rolled back partition the deployment count")
        void segmentsPartitionTheBar() {
            // The descriptor draws this as a bar. A-056's widget 10 note is the
            // precedent: a stacked pair whose segments do not sum to the bar
            // makes an arithmetic claim that is false, and nobody reports it —
            // they misread it.
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.deploymentReport(any(), any(), any(), anyBoolean(), anyLong()))
                    .thenReturn(List.of(new TicketReportRepository.DeploymentRow(
                            LocalDate.of(2026, 8, 3), "Apollo", 10, 8, 2, BigDecimal.valueOf(95.5))));

            ReportRunner.Result result =
                    new DeploymentReportRunner(tickets).run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            Map<String, Object> row = result.rows().get(0);
            long shipped = (long) row.get("succeeded");
            long back = (long) row.get("rolledBack");
            assertThat(shipped + back).isEqualTo(row.get("deployments"));
            assertThat(row.get("rollbackRate")).isEqualTo(BigDecimal.valueOf(20.0).setScale(1));
        }

        @Test
        @DisplayName("a week with no deployment has a null rollback rate, not 0%")
        void idleWeekHasNoRate() {
            // "0% rolled back" for a week that shipped nothing reads as flawless
            // delivery. A-057's SLA gauge made the same call: nothing measured
            // is a sentence, never a needle at zero.
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.deploymentReport(any(), any(), any(), anyBoolean(), anyLong()))
                    .thenReturn(List.of(new TicketReportRepository.DeploymentRow(
                            LocalDate.of(2026, 8, 10), "Apollo", 0, 0, 0, BigDecimal.ZERO)));

            ReportRunner.Result result =
                    new DeploymentReportRunner(tickets).run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows().get(0).get("rollbackRate")).isNull();
        }
    }

    @Nested
    @DisplayName("resource contribution")
    class Contribution {

        @Test
        @DisplayName("hours per ticket divides, and effort with no stage keeps its row")
        void perTicketAndUnstagedEffort() {
            // (no stage) is currently the ordinary case rather than the
            // exception, because nothing opens a first hop — so dropping those
            // rows would empty the report and make its total disagree with the
            // effort summary two cards over.
            TicketReportRepository tickets = mock(TicketReportRepository.class);
            when(tickets.resourceContribution(any(), any(), any(), anyBoolean(), anyLong(), nullable(Long.class)))
                    .thenReturn(List.of(
                            new TicketReportRepository.ContributionRow(
                                    "Priya", "Apollo", "DEV", BigDecimal.valueOf(40), 8, 12),
                            new TicketReportRepository.ContributionRow(
                                    "Sam", "Apollo", "(no stage)", BigDecimal.valueOf(9), 3, 3)));

            ReportRunner.Result result = new ResourceContributionRunner(tickets)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows()).hasSize(2);
            assertThat(result.rows().get(0).get("hoursPerTicket")).isEqualTo(BigDecimal.valueOf(5.0).setScale(1));
            assertThat(result.rows().get(1).get("stage")).isEqualTo("(no stage)");
            assertThat(result.rows().get(1).get("hoursPerTicket")).isEqualTo(BigDecimal.valueOf(3.0).setScale(1));
        }
    }

    @Nested
    @DisplayName("audit and compliance")
    class Compliance {

        @Test
        @DisplayName("an untouched entry verifies, and altering any hashed field breaks it")
        void verdictDetectsTampering() {
            // The report's whole reason for existing. If this assertion can be
            // made to pass with a broken recomputation, the export is a prettier
            // version of trusting the table.
            TicketHistory entry = historyEntry();
            entry.setRowHash(ChainDigest.rowHash(entry.getPrevHash(), ChainPayloads.of(entry)));

            assertThat(verdictOf(entry)).isEqualTo(AuditComplianceRunner.VERIFIED);

            // Reattribution — the change an insider would actually make, and the
            // one ChainPayloads covers actor_id and actor_type together to catch.
            entry.setActorId(999L);
            assertThat(verdictOf(entry)).isEqualTo(AuditComplianceRunner.ALTERED);
        }

        @Test
        @DisplayName("an entry written before the chain existed is 'not chained', never 'verified'")
        void unhashedIsNotAPass() {
            // prev_hash and row_hash are nullable because A-040 added the chain
            // after the table. Treating null as a pass would mark the oldest and
            // least verifiable rows as the safest ones on the page.
            TicketHistory entry = historyEntry();
            entry.setRowHash(null);

            assertThat(verdictOf(entry)).isEqualTo(AuditComplianceRunner.NOT_CHAINED);
        }

        @Test
        @DisplayName("hitting the cap adds a visible row rather than trailing off")
        void truncationIsAnnounced() {
            // CLAUDE.md's no-silent-caps rule, on the one report whose entire
            // purpose is completeness. A trail cut short without saying so is
            // indistinguishable from a trail that ends there.
            ComplianceReportRepository compliance = mock(ComplianceReportRepository.class);
            when(compliance.auditTrail(any(), any(), any(), anyBoolean(), anyLong(),
                    nullable(Long.class), anyInt()))
                    .thenReturn(trailOf(AuditComplianceRunner.MAX_ENTRIES + 1));

            ReportRunner.Result result = new AuditComplianceRunner(compliance)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows()).hasSize(AuditComplianceRunner.MAX_ENTRIES + 1);
            Map<String, Object> last = result.rows().get(result.rows().size() - 1);
            assertThat(last.get("event")).isEqualTo("— truncated —");
            assertThat((String) last.get("field")).contains("Narrow the date range");
        }

        @Test
        @DisplayName("under the cap, nothing is added")
        void noNoticeWhenComplete() {
            ComplianceReportRepository compliance = mock(ComplianceReportRepository.class);
            when(compliance.auditTrail(any(), any(), any(), anyBoolean(), anyLong(),
                    nullable(Long.class), anyInt()))
                    .thenReturn(trailOf(3));

            ReportRunner.Result result = new AuditComplianceRunner(compliance)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows()).noneSatisfy(row ->
                    assertThat(row.get("event")).isEqualTo("— truncated —"));
        }

        private static String verdictOf(TicketHistory entry) {
            ComplianceReportRepository compliance = mock(ComplianceReportRepository.class);
            when(compliance.auditTrail(any(), any(), any(), anyBoolean(), anyLong(),
                    nullable(Long.class), anyInt()))
                    .thenReturn(List.of(new ComplianceReportRepository.TrailRow(
                            7L, "APL-1", "Apollo", LocalDateTime.of(2026, 8, 4, 9, 0), "Priya", entry)));

            ReportRunner.Result result = new AuditComplianceRunner(compliance)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);
            return (String) result.rows().get(0).get("integrity");
        }

        private static List<ComplianceReportRepository.TrailRow> trailOf(int n) {
            return java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> new ComplianceReportRepository.TrailRow(
                            7L, "APL-1", "Apollo",
                            LocalDateTime.of(2026, 8, 4, 9, 0), "Priya", historyEntry()))
                    .toList();
        }

        private static TicketHistory historyEntry() {
            TicketHistory entry = new TicketHistory();
            entry.setId(1L);
            entry.setTicketId(7L);
            entry.setCycleNo((short) 1);
            entry.setEventType("FIELD_CHANGED");
            entry.setFieldName("level");
            entry.setOldValue("LOW");
            entry.setNewValue("CRITICAL");
            entry.setActorId(3L);
            entry.setActorType("USER");
            entry.setCorrection(false);
            entry.setPrevHash("a".repeat(ChainDigest.HASH_LENGTH));
            return entry;
        }
    }

    @Nested
    @DisplayName("email delivery log")
    class Mail {

        @Test
        @DisplayName("a mail that went first time shows no retry count")
        void zeroRetriesReadAsBlank() {
            // A column of zeroes is noise in the one report whose job is making
            // the handful of exceptions findable.
            ComplianceReportRepository compliance = mock(ComplianceReportRepository.class);
            when(compliance.deliveryLog(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                    .thenReturn(List.of(
                            mailRow("SENT", 0, null),
                            mailRow("FAILED", 3, "550 mailbox unavailable")));

            ReportRunner.Result result = new EmailDeliveryLogRunner(compliance)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows().get(0).get("retries")).isNull();
            assertThat(result.rows().get(1).get("retries")).isEqualTo(3);
            assertThat(result.rows().get(1).get("error")).isEqualTo("550 mailbox unavailable");
        }

        @Test
        @DisplayName("the engine's own status words are shown, not prettified")
        void statusIsUnmapped() {
            // SENT means the provider accepted it. D-036's bounce webhook is
            // what later turns some of those into BOUNCED, so relabelling SENT
            // as "Delivered" would be a claim the column cannot support — on a
            // report that exists to be evidence.
            ComplianceReportRepository compliance = mock(ComplianceReportRepository.class);
            when(compliance.deliveryLog(any(), any(), any(), anyBoolean(), anyLong(), anyInt()))
                    .thenReturn(List.of(mailRow("SENT", 0, null)));

            ReportRunner.Result result = new EmailDeliveryLogRunner(compliance)
                    .run(ADMIN, FROM, TO, List.of(), null, ReportFilters.NONE);

            assertThat(result.rows().get(0).get("status")).isEqualTo("SENT");
        }

        private static ComplianceReportRepository.MailRow mailRow(String status, int retries, String error) {
            return new ComplianceReportRepository.MailRow(
                    1L, "APL-1", "Apollo", "TICKET_ASSIGNED", "priya@example.com", "Priya",
                    "Ticket APL-1 assigned to you", status, retries,
                    LocalDateTime.of(2026, 8, 4, 9, 0), null, error);
        }
    }
}
