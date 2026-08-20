-- =====================================================================
-- A-073 · clear the summary tables so A-051's backfill rebuilds them
--
-- RUN THIS AFTER seed-50k.sql, ALWAYS. Skipping it does not fail
-- anything; it makes every dashboard measurement quietly wrong.
--
-- WHY IT IS NEEDED, WHICH IS NOT OBVIOUS AND COST AN HOUR TO FIND
--
-- The dashboard reads only daily_ticket_stats, resource_daily_stats and
-- client_daily_stats (A-050). seed-50k.sql writes 50,000 tickets spread
-- across eighteen months — and does not touch those three tables. So
-- immediately after seeding, the dashboard is still serving figures
-- computed for B-007's 200 tickets, and it looks completely normal doing
-- it: the screen renders, every widget has data, nothing errors.
--
-- The natural assumption is that A-051's worker will notice and catch
-- up. It will not, and the reason is a deliberate design decision in
-- DailyStatsRepository.backfillResumePoint that is correct in general
-- and unhelpful here:
--
--   resume point = (newest summarised day below the 7-day window) + 1
--
-- It only ever moves FORWARD. Its javadoc explains why — advancing one
-- calendar day at a time is what stops the older "which reported dates
-- lack a row" version from leaving holes that never close — and it names
-- the cost directly: it "gives up noticing a single day deleted by hand
-- from the middle of summarised history", repaired "by clearing
-- daily_ticket_stats from the damaged day forward and letting backfill
-- rebuild it".
--
-- Seeding is a bigger version of exactly that case. The table already
-- holds rows from two months back, so the resume point sits at the edge
-- of the current window, `from` is not before `before`, backfillOlderThan
-- returns 0 — and the sixteen months of tickets that were just inserted
-- are never summarised at all. The worker is not broken and no log line
-- says anything is wrong, because from its point of view history is
-- complete.
--
-- So this is the documented repair, applied at the documented scale.
--
-- IS DELETING THESE ROWS SAFE
--
-- Yes, and by construction rather than by luck. A-050's own migration
-- header states the property: "a refresh recomputes a day from scratch
-- and is idempotent... after an outage it recomputes the days it missed
-- and is correct again, where an accumulating counter would have
-- drifted." Every figure in all three tables is derivable from `tickets`
-- for any past date. Nothing here is a source of truth.
--
-- These are also not the append-only tables. ticket_history,
-- ticket_effort_logs and ticket_stage_transitions are insert-only and
-- hash-chained and this file must never learn to touch them — which is
-- also why seed-50k.sql writes none of them.
--
-- RUN
--
--   docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack \
--     < tools/perf/reset-stats.sql
--
-- then start the worker with a backfill big enough to cover the corpus
-- in a few passes rather than over a day of five-minute intervals:
--
--   java -jar worker/target/edutrack-worker-0.1.0-SNAPSHOT.jar \
--     --spring.profiles.active=local \
--     --edutrack.stats.backfill-per-pass=700 \
--     --edutrack.stats.refresh-interval=PT1M
--
-- Watch it land with:
--
--   SELECT COUNT(*), MIN(stat_date), MAX(stat_date) FROM daily_ticket_stats;
--
-- It is done when MIN(stat_date) reaches MIN(DATE(date_reported)) from
-- `tickets`. Until then the dashboard is showing a partial history, which
-- is a different wrong number from the one this file exists to prevent
-- but still not one to quote.
--
-- ONE MORE TRAP, IF THE WORKER LOOKS IDLE
--
-- The refresh holds a ShedLock ("statsRefresh", lockAtMostFor=PT4M). Kill
-- a worker mid-pass and the next one finds the lock still held and skips
-- — silently, and then waits a full refresh-interval before trying
-- again. If nothing happens for a few minutes after a restart, that is
-- why; wait out the four minutes or use a short interval as above.
-- =====================================================================

DELETE FROM daily_ticket_stats;
DELETE FROM resource_daily_stats;
DELETE FROM client_daily_stats;

SELECT 'Summary tables cleared. Start the worker with a large '
       'backfill-per-pass; it will rebuild from the earliest ticket.' AS result;

SELECT MIN(DATE(date_reported)) AS rebuild_should_reach_back_to,
       COUNT(*)                 AS tickets_to_summarise
  FROM tickets;
