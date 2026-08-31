-- =====================================================================
-- Dashboard Rework Dev 1, PR 3 · `ticket_cycles.started_at` / `finished_at`
--
-- Table altered: ticket_cycles (two columns, backfilled from history)
-- Source: docs/Dashboard-Rework-Plan.md ("Data: no live COUNT(*)" §1),
--         docs/Dashboard-Rework-Dev1.md PR 3
--
-- WHY NOT REUSE start_date / actual_close_date
--
-- Both already exist on this table and both mean something else.
-- `start_date` is stamped when the cycle itself opens — ticket creation
-- for cycle 1, the reopen moment for every cycle after — which can sit
-- in the TODO category for days before anyone picks the ticket up.
-- `actual_close_date` is stamped when the cycle is formally closed,
-- which on this workflow is a later, separate step from the work being
-- done (RESOLVED, `statuses.category = 'DONE'`) — sign-off can lag the
-- work by however long verification takes.
--
-- The dashboard's "Started Today" / "Finished Today" sections are about
-- the work, not the record: the first moment a cycle's status entered
-- IN_PROGRESS, and the first moment it entered DONE. Neither existing
-- column answers that, so this is two new columns rather than a
-- reinterpretation of the two that are already load-bearing elsewhere
-- (planned/actual close date reporting, SLA maths).
--
-- NULL, PER CYCLE, ON PURPOSE
--
-- A reopen creates a new `ticket_cycles` row (cycle_no increments), so a
-- ticket that finishes and is later reopened counts again in its new
-- cycle rather than staying permanently "finished" — the same reasoning
-- that keeps effort attributed per cycle. A cycle that has not reached
-- either milestone yet (or never will, e.g. a cycle closed without ever
-- passing through IN_PROGRESS) simply carries NULL; there is no default
-- that would be honest.
--
-- THIS MIGRATION IS SCHEMA + BACKFILL ONLY
--
-- Stamping `started_at` / `finished_at` at transition time is a paired
-- Stream C PR (Divyansh) — writing here would duplicate work already
-- scoped to a different codebase area (TransitionService /
-- QuickUpdateService). This file only shapes the columns and recovers
-- the values already implied by the existing STATUS_CHANGED history for
-- every cycle that predates both this migration and that PR.
--
-- THE BACKFILL QUERY
--
-- ticket_history.cycle_no is stamped from `ticket.current_cycle_no` at
-- append time (QuickUpdateService, TransitionService), so it lines up
-- exactly with `ticket_cycles.cycle_no` — no date-range guessing needed
-- to attribute a history row to its cycle. For each (ticket_id,
-- cycle_no), `started_at` is the EARLIEST STATUS_CHANGED row whose new
-- status carries category IN_PROGRESS; `finished_at` is the earliest
-- whose new status carries category DONE. Earliest, not latest: these
-- are one-time per-cycle milestones ("first time this cycle's work
-- began" / "first time this cycle's work was claimed done"), consistent
-- with them being per-cycle columns rather than per-iteration ones — a
-- cycle bounced back for rework and resolved again does not un-start or
-- un-finish, it iterates (§4A.2's `iteration_no`, untouched by this
-- migration).
--
-- `is_correction = 0` excludes compensating-entry reversals (CLAUDE.md's
-- accounting-reversal pattern) from being read as a genuine transition.
--
-- PROTECTED-TABLE ADJACENCY
--
-- `ticket_cycles` is not itself one of the three append-only tables, but
-- it sits immediately beside `ticket_history` in the ticket lifecycle —
-- needs Stream A review before merge even though this PR is Stream A's
-- own (CLAUDE.md, and the precedent V20260818_1530 states for the same
-- table).
-- =====================================================================


-- 1. The columns. Nullable, additive — no existing row's shape changes.
ALTER TABLE ticket_cycles
  ADD COLUMN started_at  DATETIME(6) NULL AFTER client_verification_requested,
  ADD COLUMN finished_at DATETIME(6) NULL AFTER started_at;


-- 2. Indexes. The worker's daily pass reads "started today" / "finished
-- today" as a date-range scan across every cycle, not filtered by
-- ticket — a single-column index per stamp is what that scan uses.
CREATE INDEX ix_ticket_cycles_started_at  ON ticket_cycles (started_at);
CREATE INDEX ix_ticket_cycles_finished_at ON ticket_cycles (finished_at);


-- 3. Backfill `started_at` — earliest entry into IN_PROGRESS, per cycle.
UPDATE ticket_cycles tc
  JOIN (
    SELECT th.ticket_id, th.cycle_no, MIN(th.created_at) AS first_started
      FROM ticket_history th
      JOIN statuses s ON s.code = th.new_value
     WHERE th.event_type    = 'STATUS_CHANGED'
       AND th.field_name    = 'status'
       AND th.is_correction = 0
       AND s.category       = 'IN_PROGRESS'
     GROUP BY th.ticket_id, th.cycle_no
  ) started
    ON started.ticket_id = tc.ticket_id
   AND started.cycle_no  = tc.cycle_no
   SET tc.started_at = started.first_started;


-- 4. Backfill `finished_at` — earliest entry into DONE, per cycle.
UPDATE ticket_cycles tc
  JOIN (
    SELECT th.ticket_id, th.cycle_no, MIN(th.created_at) AS first_finished
      FROM ticket_history th
      JOIN statuses s ON s.code = th.new_value
     WHERE th.event_type    = 'STATUS_CHANGED'
       AND th.field_name    = 'status'
       AND th.is_correction = 0
       AND s.category       = 'DONE'
     GROUP BY th.ticket_id, th.cycle_no
  ) finished
    ON finished.ticket_id = tc.ticket_id
   AND finished.cycle_no  = tc.cycle_no
   SET tc.finished_at = finished.first_finished;
