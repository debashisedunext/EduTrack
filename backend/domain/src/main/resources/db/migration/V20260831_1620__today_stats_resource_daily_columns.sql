-- =====================================================================
-- Dashboard Rework Dev 1, PR 4 · `resource_daily_stats` — the Assignee MIS
--
-- Table altered: resource_daily_stats (the same fourteen counters
-- V20260831_1600 gave the project table, minus `open_by_role` — a
-- per-resource row already IS one role's slice)
-- Source: docs/Dashboard-Rework-Plan.md, "Data" §3
--
-- WHY NOT NULL DEFAULT 0 HERE, WHEN THE PROJECT TABLE JUST WENT NULL
--
-- This table already answers "not computed" a different way to the
-- project one: `daily_ticket_stats` gives every project a row on every
-- day by construction, so a NULL column is the only way to say "this
-- day predates the column." `resource_daily_stats` gives a user a row
-- only when they EARN one that day (holding, closing or logging effort
-- against something) and rewrites the whole day by DELETE + INSERT —
-- `refreshResourceStats`'s own header already states why an upsert
-- cannot be used here. A row that exists at all is therefore always
-- freshly computed by the pass that wrote it; there is no "row survives
-- from before this column existed" case to distinguish with NULL, the
-- same way there is none for `assigned_open` or `assigned_delayed`
-- beside them. `wip_updated_today`'s caveat on the project table
-- (`tickets.updated_at` carries no history, so a past day cannot
-- honestly answer it) still applies here — the worker computes it as
-- zero rather than a true count for a day it is not run on, matching
-- how a resource with no other qualifying activity that day gets no row
-- at all rather than a NULL cell.
--
-- WHY THESE COLUMNS AND NOT open_by_role
--
-- `open_by_role`'s whole reason to exist is grouping ACROSS resources by
-- who holds the work; a row already scoped to one `user_id` has nothing
-- left to group. It stays on `daily_ticket_stats` alone.
--
-- SAME NAMES AS THE PROJECT TABLE, NOT `assigned_`-PREFIXED
--
-- Every existing column here (`assigned_open`, `assigned_delayed`, …)
-- carries that prefix because MIS is the only place they are read and
-- "assigned" disambiguates them from nothing project-shaped sitting
-- beside them. These fourteen are read against `daily_ticket_stats`'
-- identically-named columns by the same MIS row — `TodaySummaryCard`'s
-- and `AssigneeMisTable`'s figures reconcile against each other, per
-- the plan's own header note ("figures reconcile with the MIS below" —
-- prototype), and giving the same fact two different names in the two
-- tables it lives in would be the thing a future reader has to notice
-- is not a bug.
-- =====================================================================

ALTER TABLE resource_daily_stats
  -- ── Not Started (status category TODO: NEW, REOPENED) ────────────────
  ADD COLUMN ns_total              INT NOT NULL DEFAULT 0 AFTER assigned_due_next_7,
  ADD COLUMN ns_overdue            INT NOT NULL DEFAULT 0 AFTER ns_total,
  ADD COLUMN ns_due_today          INT NOT NULL DEFAULT 0 AFTER ns_overdue,

  -- ── WIP (status IN_PROGRESS or REWORK) ────────────────────────────────
  ADD COLUMN wip_total             INT NOT NULL DEFAULT 0 AFTER ns_due_today,
  ADD COLUMN wip_updated_today     INT NOT NULL DEFAULT 0 AFTER wip_total,
  ADD COLUMN wip_near_delay        INT NOT NULL DEFAULT 0 AFTER wip_updated_today,
  ADD COLUMN wip_delayed           INT NOT NULL DEFAULT 0 AFTER wip_near_delay,

  -- ── Started / Finished Today — per-cycle stamps, attributed to
  -- whoever `ticket_cycles.assigned_to` names for THAT cycle, not to
  -- today's current assignee (see the worker: the one place in this
  -- migration's columns that IS faithfully historical, because A-008's
  -- cycle row is fixed once sealed) ─────────────────────────────────────
  ADD COLUMN started_today         INT NOT NULL DEFAULT 0 AFTER wip_delayed,
  ADD COLUMN finished_early        INT NOT NULL DEFAULT 0 AFTER started_today,
  ADD COLUMN finished_on_time      INT NOT NULL DEFAULT 0 AFTER finished_early,
  ADD COLUMN finished_late         INT NOT NULL DEFAULT 0 AFTER finished_on_time,

  -- ── Blocked (status ON_HOLD / AWAITING_INFO) ──────────────────────────
  ADD COLUMN blocked_on_hold       INT NOT NULL DEFAULT 0 AFTER finished_late,
  ADD COLUMN blocked_awaiting_info INT NOT NULL DEFAULT 0 AFTER blocked_on_hold,

  -- ── Pending Review — RESOLVED-not-CLOSED plus review-stage tickets,
  -- against today's assigned_to like the rest of this table's stock
  -- columns (the class note's "not faithful — assigned_to" caveat) ─────
  ADD COLUMN pending_review        INT NOT NULL DEFAULT 0 AFTER blocked_awaiting_info;
