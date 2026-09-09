package com.edunext.edutrack.api.feature.onboarding.reports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * B-122 · every statement OB-10 runs, in one place.
 *
 * <h2>Why one repository rather than one per runner</h2>
 *
 * <p>Because the scope predicate is interpolated into the SQL text, and the
 * thing that must be auditable is "does every statement carry it". Six runners
 * each holding their own SQL is six places to check and six places for the
 * seventh author to omit it; here the answer is one file and
 * {@code ObReportRepositoryTest.everyStatementCarriesTheScopePredicate} can
 * assert it mechanically.
 *
 * <h2>Interpolated predicate, bound values</h2>
 *
 * <p>{@link ObReportScope#journeyPredicate} and
 * {@link ObReportScope#clientPredicate} return SQL fragments that are
 * concatenated into the statement. That is deliberate and it is safe for a
 * specific reason rather than by luck: the fragments are chosen from a closed
 * set of five compile-time literals by a {@code switch} over the module role,
 * and every value inside them is a named bind. No caller-supplied string ever
 * reaches the text. A fragment built from a parameter would be an injection,
 * and this note is here so that the next person adding a sixth role writes a
 * literal rather than a format string.
 *
 * <h2>Live reads, and why that does not break CLAUDE.md</h2>
 *
 * <p>"Never live {@code COUNT(*)} for dashboards. Read the pre-aggregated
 * summary tables." These are reports, not dashboards, and the distinction is
 * the one the ticketing side already draws — {@code ReportRepository} reads
 * summaries where a summary answers the question and
 * {@code TicketReportRepository} reads the ticket tables where none does. Every
 * report here is at a grain {@code ob_dashboard_summary} does not have: per
 * step, per owner, per sales person, per sign-off. A summary keyed
 * {@code (stat_date, product_id)} cannot answer any of them, and a report is
 * opened deliberately by one person rather than polled by every open browser,
 * which is what made the dashboard rule necessary in the first place.
 */
@Repository
class ObReportRepository {

    private final JdbcClient jdbc;

    ObReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── journey-funnel ──────────────────────────────────────────────────────

    /**
     * One row per (product, step) with the journeys currently sitting there.
     *
     * <p><b>"Currently sitting there" is the first step that is not DONE or
     * SKIPPED</b>, which is also this report's definition of "still in the
     * funnel": a journey whose steps are all terminal joins nothing and drops
     * out. That is one definition rather than two — filtering on
     * {@code completed_at IS NULL} as well would let a journey that finished
     * its last step before the flag was stamped be counted in neither place, or
     * in both.
     *
     * <p>Locked journeys are counted, at the first step, and carry their own
     * column. A journey waiting on prerequisites has not started work but it
     * <em>is</em> in the pipeline, and plan §5.3 makes the gate a normal state
     * rather than an exception; hiding them would make the funnel disagree with
     * the board's {@code journeys_locked} card. Reporting them without saying
     * so would inflate step one, which on a healthy pipeline is where most of
     * them are.
     */
    List<FunnelRow> funnel(ObReportScope scope, LocalDate from, LocalDate to, Long productId) {
        String sql = """
                SELECT p.name AS product,
                       s.sequence AS step_no,
                       s.name AS service,
                       COUNT(*) AS journeys,
                       SUM(CASE WHEN j.gate_status = 'LOCKED' THEN 1 ELSE 0 END) AS locked
                  FROM ob_journeys j
                  JOIN ob_products p ON p.id = j.product_id
                  JOIN ob_journey_steps s ON s.id = (
                           SELECT cur.id
                             FROM ob_journey_steps cur
                            WHERE cur.journey_id = j.id
                              AND cur.status NOT IN ('DONE', 'SKIPPED')
                            ORDER BY cur.sequence
                            LIMIT 1)
                 WHERE j.archived_at IS NULL
                   AND DATE(j.created_at) BETWEEN :from AND :to
                   AND (:productId IS NULL OR j.product_id = :productId)
                   AND %s
                 GROUP BY p.name, s.sequence, s.name
                 ORDER BY p.name, s.sequence
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("productId", productId)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new FunnelRow(
                        rs.getString("product"), rs.getInt("step_no"), rs.getString("service"),
                        rs.getLong("journeys"), rs.getLong("locked")))
                .list();
    }

    record FunnelRow(String product, int stepNo, String service, long journeys, long locked) {
    }

    // ── tat-compliance ──────────────────────────────────────────────────────

    /**
     * One row per (product, service, owner) over steps finished in the window.
     *
     * <p><b>{@code measured} is separate from {@code completed} on purpose.</b>
     * On-time is {@code finished_at <= due_at}, and a step with no
     * {@code due_at} cannot be judged either way. Dropping those rows silently
     * would make a percentage over three steps look like a percentage over
     * thirty; counting them as on time would flatter every owner. So both
     * numbers travel and the runner divides by the second, which lets a reader
     * see when a figure rests on very little.
     *
     * <p>That matters more than usual right now: {@code due_at} is C-105's to
     * maintain and is nullable in A-107's schema, so on a deployment where the
     * clock has not run yet {@code measured} is legitimately zero and the
     * report says "not measurable" rather than "nobody was late".
     *
     * <p><b>The waiting-on-client exclusion is not in this query and must not
     * be.</b> Plan §1.1 pauses the TAT clock when a step waits on the client,
     * and that pause is expressed by {@code due_at} moving — C-105 owns it.
     * Subtracting clock events here would be a second definition of the same
     * exclusion, and the two would disagree the first time either changed.
     */
    List<TatRow> tatCompliance(ObReportScope scope, LocalDate from, LocalDate to,
                               Long productId, Long ownerSubject) {
        String sql = """
                SELECT p.name AS product,
                       s.name AS service,
                       s.owner_user_id AS owner_id,
                       u.full_name AS owner,
                       COUNT(*) AS completed,
                       SUM(CASE WHEN s.due_at IS NOT NULL THEN 1 ELSE 0 END) AS measured,
                       SUM(CASE WHEN s.due_at IS NOT NULL AND s.finished_at <= s.due_at
                                THEN 1 ELSE 0 END) AS on_time
                  FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                  JOIN ob_products p ON p.id = j.product_id
                  LEFT JOIN users u ON u.id = s.owner_user_id
                 WHERE j.archived_at IS NULL
                   AND s.status = 'DONE'
                   AND s.finished_at IS NOT NULL
                   AND DATE(s.finished_at) BETWEEN :from AND :to
                   AND (:productId IS NULL OR j.product_id = :productId)
                   AND (:ownerSubject IS NULL OR s.owner_user_id = :ownerSubject)
                   AND %s
                 GROUP BY p.name, s.name, s.owner_user_id, u.full_name
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("productId", productId)
                .param("ownerSubject", ownerSubject)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new TatRow(
                        rs.getString("product"), rs.getString("service"), rs.getString("owner"),
                        rs.getLong("completed"), rs.getLong("measured"), rs.getLong("on_time")))
                .list();
    }

    record TatRow(String product, String service, String owner,
                  long completed, long measured, long onTime) {
    }

    // ── stuck-and-aging ─────────────────────────────────────────────────────

    /**
     * One row per step that is blocked, waiting on the client, or past its
     * {@code due_at} and not finished.
     *
     * <p><b>Aging is stock, so the date range picks the cohort rather than a
     * span to add up.</b> "How long has this been stuck" has exactly one answer
     * at a time; summing thirty days of it would count the same step thirty
     * times and still draw a chart that looked reasonable. The range therefore
     * selects <em>which journeys</em> to look at — by when they were raised,
     * the same dimension {@link #funnel} uses — and the states are read as they
     * are now. {@code AgingReportRunner} settled the identical question one
     * module over and this follows it.
     *
     * <p>The clock attribution comes from the latest
     * {@code ob_step_clock_events} row for the step, which is what says whether
     * the time currently accruing is ours or the client's — plan §1.1's whole
     * point, and the column that makes a TAT dispute answerable. A step with no
     * clock event yet reports null rather than {@code INTERNAL}: never started
     * and running on our time are different claims.
     *
     * <p>RAG is deliberately <b>not</b> computed here. It needs "now" and a
     * share of the TAT window, it is the value the {@code ?rag=} filter selects
     * on, and a formula in SQL for the chip plus a formula in Java for the
     * filter is two formulas. See {@link StuckAndAgingRunner}.
     */
    List<StuckRow> stuckAndAging(ObReportScope scope, LocalDate from, LocalDate to,
                                 Long productId, Instant now) {
        String sql = """
                SELECT c.name AS client,
                       p.name AS product,
                       s.name AS service,
                       s.status AS status,
                       u.full_name AS owner,
                       s.blocked_reason_code AS block_code,
                       s.blocked_note AS block_note,
                       s.started_at AS started_at,
                       s.due_at AS due_at,
                       ce.attributed_to AS clock,
                       ce.occurred_at AS clock_since
                  FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                  JOIN ob_clients c ON c.id = j.ob_client_id
                  JOIN ob_products p ON p.id = j.product_id
                  LEFT JOIN users u ON u.id = s.owner_user_id
                  LEFT JOIN ob_step_clock_events ce ON ce.id = (
                           SELECT last.id
                             FROM ob_step_clock_events last
                            WHERE last.step_id = s.id
                            ORDER BY last.occurred_at DESC, last.id DESC
                            LIMIT 1)
                 WHERE j.archived_at IS NULL
                   AND s.status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT')
                   AND (s.status IN ('BLOCKED', 'WAITING_ON_CLIENT')
                        OR (s.due_at IS NOT NULL AND s.due_at < :now))
                   AND DATE(j.created_at) BETWEEN :from AND :to
                   AND (:productId IS NULL OR j.product_id = :productId)
                   AND %s
                 ORDER BY s.due_at IS NULL, s.due_at, c.name, s.sequence
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("productId", productId)
                .param("now", Timestamp.from(now))
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new StuckRow(
                        rs.getString("client"), rs.getString("product"), rs.getString("service"),
                        rs.getString("status"), rs.getString("owner"),
                        rs.getString("block_code"), rs.getString("block_note"),
                        instantOrNull(rs.getTimestamp("started_at")),
                        instantOrNull(rs.getTimestamp("due_at")),
                        rs.getString("clock"),
                        instantOrNull(rs.getTimestamp("clock_since"))))
                .list();
    }

    record StuckRow(String client, String product, String service, String status, String owner,
                    String blockCode, String blockNote, Instant startedAt, Instant dueAt,
                    String clock, Instant clockSince) {
    }

    // ── time-to-live ────────────────────────────────────────────────────────

    /**
     * One row per journey that completed in the window, with the two instants
     * the duration is measured between.
     *
     * <p><b>Rows, not an average.</b> The average is working time, and working
     * time is {@code WorkingHoursService}'s to compute — a MySQL
     * {@code AVG(TIMESTAMPDIFF(…))} would be a second, calendar-blind
     * definition of a duration, which CLAUDE.md forbids in as many words. So
     * this returns the endpoints and the runner does the arithmetic.
     *
     * <p><b>Measured from {@code created_at}, not {@code started_at}.</b> A
     * journey is created LOCKED and starts when its prerequisites clear, so
     * {@code started_at} would exclude the gate wait — and the gate wait is
     * part of the answer to "how long did boarding take", which is the question
     * this report exists for. The client counts from when they signed. That the
     * wait was the client's own is a different report's finding, which is why
     * stuck-and-aging carries the attribution column and this one does not.
     */
    List<CompletedJourney> completedJourneys(ObReportScope scope, LocalDate from, LocalDate to,
                                             Long productId) {
        String sql = """
                SELECT p.name AS product,
                       DATE_FORMAT(j.completed_at, '%%Y-%%m') AS month,
                       j.created_at AS raised_at,
                       j.completed_at AS completed_at
                  FROM ob_journeys j
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.archived_at IS NULL
                   AND j.completed_at IS NOT NULL
                   AND DATE(j.completed_at) BETWEEN :from AND :to
                   AND (:productId IS NULL OR j.product_id = :productId)
                   AND %s
                 ORDER BY month, p.name
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("productId", productId)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new CompletedJourney(
                        rs.getString("product"), rs.getString("month"),
                        rs.getTimestamp("raised_at").toInstant(),
                        rs.getTimestamp("completed_at").toInstant()))
                .list();
    }

    record CompletedJourney(String product, String month, Instant raisedAt, Instant completedAt) {
    }

    // ── sales-pipeline ──────────────────────────────────────────────────────

    /**
     * One row per sales person over clients boarded in the window.
     *
     * <p>The only report whose grain is a client rather than a journey, and
     * therefore the only caller of {@link ObReportScope#clientPredicate} — see
     * that method for why the OB_SALES case is {@code created_by} directly.
     *
     * <p><b>Grouped by {@code sales_person_id} and scoped by {@code created_by},
     * which are different columns.</b> They are usually the same person and
     * need not be: a manager capturing a client on a colleague's behalf sets
     * one and is the other. The grouping is the relationship owner because that
     * is whose pipeline it is; the scope is the creator because that is what §3
     * grants. A salesperson can therefore see a row attributed to a colleague,
     * for a client they themselves captured — which is correct and is the
     * honest reading of both rules rather than a leak.
     *
     * <p>Clients with no sales person group into a single null row, which the
     * runner labels. Losing them would make the column totals disagree with the
     * client list, and a pipeline report that quietly drops unassigned intake
     * is the one that hides the problem it should surface.
     */
    List<PipelineRow> salesPipeline(ObReportScope scope, LocalDate from, LocalDate to) {
        String sql = """
                SELECT u.full_name AS sales_person,
                       COUNT(*) AS boarded,
                       SUM(CASE WHEN c.overall_status = 'LIVE' THEN 1 ELSE 0 END) AS live,
                       SUM(CASE WHEN c.overall_status = 'ONBOARDING' THEN 1 ELSE 0 END) AS onboarding,
                       SUM(CASE WHEN c.overall_status = 'ON_HOLD' THEN 1 ELSE 0 END) AS on_hold,
                       SUM(CASE WHEN c.overall_status = 'DROPPED' THEN 1 ELSE 0 END) AS dropped
                  FROM ob_clients c
                  LEFT JOIN users u ON u.id = c.sales_person_id
                 WHERE c.onboarding_date BETWEEN :from AND :to
                   AND %s
                 GROUP BY c.sales_person_id, u.full_name
                 ORDER BY boarded DESC, sales_person
                """.formatted(scope.clientPredicate("c"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new PipelineRow(
                        rs.getString("sales_person"), rs.getLong("boarded"), rs.getLong("live"),
                        rs.getLong("onboarding"), rs.getLong("on_hold"), rs.getLong("dropped")))
                .list();
    }

    record PipelineRow(String salesPerson, long boarded, long live, long onboarding,
                       long onHold, long dropped) {
    }

    // ── signoff-pending ─────────────────────────────────────────────────────

    /**
     * Sign-offs requested and unanswered, oldest first.
     *
     * <p>{@code status = 'PENDING'} only. SIGNED, OBJECTED and CANCELLED are
     * all answers; this report is about the ones nobody has given.
     *
     * <p><b>{@code EXPIRED} is not a status this query can filter on, and that
     * is the contract's own point.</b> {@code ObSignoffStatus} says the state is
     * "reached by the token's TTL passing, not by an operation — there is no
     * route that expires a sign-off, because the thing that expires it is
     * time". So a row can be PENDING in the table and dead in fact, and the
     * expiry instant travels with it so the runner can say which. A pending
     * list that showed an expired link as merely "awaiting the client" would
     * have somebody chasing a client who cannot act.
     */
    List<PendingSignoff> pendingSignoffs(ObReportScope scope, LocalDate from, LocalDate to,
                                         Long obClientId) {
        String sql = """
                SELECT c.name AS client,
                       COALESCE(s.name, 'Go-live') AS service,
                       so.kind AS kind,
                       ct.name AS contact,
                       ct.email AS contact_email,
                       so.requested_at AS requested_at,
                       so.token_expires_at AS expires_at
                  FROM ob_signoffs so
                  JOIN ob_journeys j ON j.id = so.journey_id
                  JOIN ob_clients c ON c.id = so.ob_client_id
                  JOIN ob_client_contacts ct ON ct.id = so.sent_to_contact_id
                  LEFT JOIN ob_journey_steps s ON s.id = so.step_id
                 WHERE j.archived_at IS NULL
                   AND so.status = 'PENDING'
                   AND DATE(so.requested_at) BETWEEN :from AND :to
                   AND (:obClientId IS NULL OR so.ob_client_id = :obClientId)
                   AND %s
                 ORDER BY so.requested_at
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("obClientId", obClientId)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new PendingSignoff(
                        rs.getString("client"), rs.getString("service"), rs.getString("kind"),
                        rs.getString("contact"), rs.getString("contact_email"),
                        rs.getTimestamp("requested_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    record PendingSignoff(String client, String service, String kind, String contact,
                          String contactEmail, Instant requestedAt, Instant expiresAt) {
    }

    // ── csat-summary ────────────────────────────────────────────────────────

    /**
     * B-119 · one row per product, over {@code GO_LIVE} sign-offs answered in
     * the window.
     *
     * <p><b>Grain is the sign-off, scoped by journey, on {@code pendingSignoffs}'
     * own pattern one section up.</b> CSAT lives on {@code ob_signoffs} and is
     * 1:1 with the row a client answered through — see the B-119 migration —
     * so this joins the same way that one does and carries the same
     * {@link ObReportScope#journeyPredicate}.
     *
     * <p><b>Raw sums travel; the runner divides.</b> {@code TatComplianceRunner}
     * makes the identical call for its own percentage, one file over: a MySQL
     * {@code AVG()} would need its own rounding decided in SQL, and a runner
     * that receives the total and the count instead can round once, the same
     * way every other report here does its arithmetic in Java.
     *
     * <p>The five {@code score*} columns are a count each, not a formula —
     * plan §10's "CSAT summary" wants a distribution beside the average, and a
     * {@code CASE WHEN} per possible score is the plain way to get one without
     * a second query.
     */
    List<CsatRow> csatSummary(ObReportScope scope, LocalDate from, LocalDate to, Long productId) {
        String sql = """
                SELECT p.name AS product,
                       COUNT(*) AS responses,
                       SUM(so.csat_score) AS score_total,
                       SUM(CASE WHEN so.csat_score = 1 THEN 1 ELSE 0 END) AS score1,
                       SUM(CASE WHEN so.csat_score = 2 THEN 1 ELSE 0 END) AS score2,
                       SUM(CASE WHEN so.csat_score = 3 THEN 1 ELSE 0 END) AS score3,
                       SUM(CASE WHEN so.csat_score = 4 THEN 1 ELSE 0 END) AS score4,
                       SUM(CASE WHEN so.csat_score = 5 THEN 1 ELSE 0 END) AS score5
                  FROM ob_signoffs so
                  JOIN ob_journeys j ON j.id = so.journey_id
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.archived_at IS NULL
                   AND so.kind = 'GO_LIVE'
                   AND so.csat_submitted_at IS NOT NULL
                   AND DATE(so.csat_submitted_at) BETWEEN :from AND :to
                   AND (:productId IS NULL OR j.product_id = :productId)
                   AND %s
                 GROUP BY p.name
                 ORDER BY p.name
                """.formatted(scope.journeyPredicate("j"));

        return jdbc.sql(sql)
                .param("from", from)
                .param("to", to)
                .param("productId", productId)
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query((rs, n) -> new CsatRow(
                        rs.getString("product"), rs.getLong("responses"), rs.getLong("score_total"),
                        rs.getLong("score1"), rs.getLong("score2"), rs.getLong("score3"),
                        rs.getLong("score4"), rs.getLong("score5")))
                .list();
    }

    record CsatRow(String product, long responses, long scoreTotal,
                   long score1, long score2, long score3, long score4, long score5) {
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
