-- =====================================================================
-- A-073 · the index review, as a script rather than a paragraph
--
-- PLAN.md §6 M7 lists "index review against real query plans" as
-- hardening work. This is that review, in the form that can be re-run:
-- every claim in V20260820_0445__ticket_list_sort_indexes.sql's header
-- and in tools/perf/README.md comes from output this file produces.
--
--   docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack \
--     < tools/perf/explain.sql > plans.txt
--
-- Run it against a database seeded by tools/perf/seed-50k.sql. Against
-- B-007's 200 rows every query below is fast and every plan is a table
-- scan, which tells you nothing — the whole point of the corpus is that
-- 200 rows cannot distinguish a good plan from a bad one.
--
-- WHY EXPLAIN ANALYZE AND NOT EXPLAIN
--
-- EXPLAIN prints what the optimiser intends. EXPLAIN ANALYZE prints what
-- it did, including rows actually read, which is the number that exposes
-- the failure mode this review exists to catch: a plan that looks
-- excellent (no filesort, index scan) while reading 46,090 rows to
-- return 51. `rows=51` in an EXPLAIN would have hidden that entirely.
--
-- WHAT THESE QUERIES ARE
--
-- The ticket list as ScopedTickets + TicketListSpecs compose it: the
-- mandatory scope predicate from ScopeResolver AND-ed with the request's
-- filters, ordered by the sort key and `id`, limited to one page plus the
-- extra row CursorPage.of consumes. Written out longhand here rather than
-- captured from Hibernate, so the file is readable and stable — the shape
-- is checked against the real generated SQL, and if TicketListSpecs
-- changes, this file is what needs updating alongside it.
--
-- The scope variants are ScopeResolver's three branches:
--   Admin                      no predicate
--   PM / Support               project_id IN (...)
--   Developer / QA / Deployment  assigned_to = ?
-- =====================================================================

SELECT '================ corpus size ================' AS ``;
-- `delayed` and `closed` are not usable as bare aliases here: DELAYED is a
-- reserved word (INSERT DELAYED) and the parser rejects it outright.
SELECT COUNT(*) AS n_tickets, SUM(status = 'CLOSED') AS n_closed,
       SUM(status <> 'CLOSED') AS n_open, SUM(is_delayed) AS n_delayed
  FROM tickets;

SELECT '================ indexes present ================' AS ``;
SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = DATABASE() AND table_name = 'tickets'
 GROUP BY index_name ORDER BY index_name;


SELECT '--- Q1 · Admin, default list, no filters ------------------' AS ``;
-- The single most-served query in the product: the list every Admin and
-- most PMs land on. Wants ix_tickets_created.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q2 · Developer/QA/Deployment scope --------------------' AS ``;
-- ScopeResolver's assignedTo branch. Wants ix_tickets_assignee_created.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.assigned_to = 5
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q3 · PM scope, several projects -----------------------' AS ``;
-- ScopeResolver's inProjects branch, holding most of the table. The
-- optimiser should reach for ix_tickets_created and filter, not for a
-- project composite — see the migration header on why that composite is
-- deliberately absent.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.project_id IN (1, 2)
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q4 · PM scope, one project ----------------------------' AS ``;
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.project_id IN (3)
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q5 · PM scope + selective filters ---------------------' AS ``;
-- Should stay on ix_tickets_project_status and sort the few hundred rows
-- it returns. If this ever switches to a created_at index, the plan has
-- gone wrong in the way the migration header describes.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.project_id = 1 AND t.level = 'CRITICAL' AND t.status = 'NEW'
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q6 · assignee scope + status filter -------------------' AS ``;
-- Should stay on ix_tickets_assignee_status for the same reason: both
-- assignee indexes exist and the more selective one must win here.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.assigned_to = 5 AND t.status = 'NEW'
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q7 · deep keyset page (A-053) -------------------------' AS ``;
-- The cursor predicate TicketListSpecs.keyset builds. This is the query
-- whose cost must NOT grow with how deep the page is; without
-- ix_tickets_created it repeats the whole scan and sort every page.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE (t.created_at < '2025-06-01 00:00:00'
    OR (t.created_at = '2025-06-01 00:00:00' AND t.id < 40000))
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q8 · dashboard drill-down, reported window ------------' AS ``;
-- A-060's reportedFrom/reportedTo, which every S-05 widget drill-down
-- emits. The query that ix_tickets_created alone makes THREE TIMES WORSE
-- than no index at all, and the reason ix_tickets_reported exists. Watch
-- "rows=" on the scan line, not the elapsed time.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.date_reported >= '2025-04-01' AND t.date_reported < '2025-04-08'
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q9 · drill-down + project scope -----------------------' AS ``;
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.project_id IN (1, 2)
   AND t.date_reported >= '2025-04-01' AND t.date_reported < '2025-04-08'
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q10 · delayed-tickets filter --------------------------' AS ``;
-- is_delayed is a low-cardinality flag and is intentionally unindexed:
-- at ~10% of the table an index on it would rarely beat a scan, and the
-- SLA scanner does not use this column — it uses ix_tickets_pcd_open.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t
 WHERE t.is_delayed = 1
 ORDER BY t.created_at DESC, t.id DESC
 LIMIT 51;

SELECT '--- Q11 · SLA scan (D-020), the pcd_open partial index ----' AS ``;
-- A-009's generated column standing in for PostgreSQL's partial index.
-- Included because A-009's own header claims this stays O(breaches) at
-- the A-073 target, and this is where that claim gets checked.
EXPLAIN ANALYZE
SELECT t.id, t.ticket_code, t.pcd_open FROM tickets t
 WHERE t.pcd_open < NOW()
 ORDER BY t.pcd_open
 LIMIT 200;

SELECT '--- Q12 · ticket-code deep link (A-072) -------------------' AS ``;
-- PLAN.md §3.8 calls this "the dominant search... exact and instant".
-- Confirming it resolves on uq_tickets_code and not on the FULLTEXT index.
EXPLAIN ANALYZE
SELECT t.* FROM tickets t WHERE t.ticket_code = 'CRM-26-08000';

SELECT '--- Q13 · global search (A-072), FULLTEXT -----------------' AS ``;
EXPLAIN ANALYZE
SELECT t.id, MATCH(t.title, t.description) AGAINST ('corpus' IN BOOLEAN MODE) AS score
  FROM tickets t
 WHERE MATCH(t.title, t.description) AGAINST ('corpus' IN BOOLEAN MODE)
 ORDER BY score DESC
 LIMIT 20;


SELECT '================ dashboard: summary tables ================' AS ``;
-- DashboardRepository and WidgetRepository read only these. The point of
-- checking them here is to confirm that claim still holds at 50,000
-- tickets — their cost must track the number of summarised DAYS, which
-- grows with calendar time, not with the ticket table.

SELECT 'daily_ticket_stats' AS t, COUNT(*) AS rows_now FROM daily_ticket_stats
UNION ALL SELECT 'resource_daily_stats', COUNT(*) FROM resource_daily_stats
UNION ALL SELECT 'client_daily_stats', COUNT(*) FROM client_daily_stats;

SELECT '--- Q14 · projectFlow (DashboardRepository) ---------------' AS ``;
EXPLAIN ANALYZE
SELECT COALESCE(SUM(created), 0), COALESCE(SUM(closed), 0), COALESCE(SUM(reopened), 0)
  FROM daily_ticket_stats
 WHERE stat_date BETWEEN DATE_SUB(CURDATE(), INTERVAL 30 DAY) AND CURDATE();

SELECT '--- Q15 · projectStock, latest-day subquery ---------------' AS ``;
EXPLAIN ANALYZE
SELECT COALESCE(SUM(open_total), 0), COALESCE(SUM(open_critical), 0)
  FROM daily_ticket_stats
 WHERE stat_date = (SELECT MAX(stat_date) FROM daily_ticket_stats
                     WHERE stat_date BETWEEN DATE_SUB(CURDATE(), INTERVAL 30 DAY) AND CURDATE());

SELECT '--- Q16 · projectSeries, the sparklines -------------------' AS ``;
EXPLAIN ANALYZE
SELECT stat_date, SUM(created), SUM(closed), SUM(open_total)
  FROM daily_ticket_stats
 WHERE stat_date BETWEEN DATE_SUB(CURDATE(), INTERVAL 30 DAY) AND CURDATE()
 GROUP BY stat_date ORDER BY stat_date;

SELECT '--- Q17 · the statement A-051 runs per day ----------------' AS ``;
-- The other side of the bargain: the dashboard is cheap because this is
-- expensive, and this one DOES scan tickets. Its cost is what decides
-- whether a five-minute refresh window is enough at 50,000 rows, and
-- whether a full 18-month backfill is minutes or hours.
EXPLAIN ANALYZE
SELECT project_id,
       SUM(actual_close_date IS NULL) AS open_total,
       SUM(actual_close_date IS NULL AND level = 'CRITICAL') AS open_critical
  FROM tickets
 WHERE date_reported < DATE_ADD(CURDATE(), INTERVAL 1 DAY)
   AND (actual_close_date IS NULL OR actual_close_date >= CURDATE())
 GROUP BY project_id;
