-- =====================================================================
-- Dashboard Rework Dev 2, PR 11 · Weekly Progress's three cards that read
-- more than a single day's stock: avg progress %, due-this-week, avg delay.
--
-- Table altered: daily_ticket_stats (+3 INT), resource_daily_stats (+2 INT)
-- Source: docs/Dashboard-Rework-Plan.md, "Data" §4
--
-- SUMS IN STORAGE, NOT AVERAGES
--
-- `open_pct_sum` / `pct_sum` is a sum, not "the average progress". The
-- average is computed on read as sum / open_total, so it stays correct
-- when the denominator moves between the day this row was written and the
-- day it is read — storing a pre-divided average would go stale the moment
-- a ticket in the denominator closed or opened without a fresh worker pass.
--
-- WHY open_pct_sum IS NULL, LIKE THE OTHER FOURTEEN, AND NOT LIKE
-- delay_days_sum / open_due_next_7 BESIDE IT
--
-- V20260831_1600's header already drew this line and it applies again
-- unchanged: `daily_ticket_stats` gives every project a row on every day
-- by construction, so NULL is the only way a row from before this
-- migration can be told apart from a row this migration's worker pass has
-- actually reached. That is a schema-level fact about this table and
-- applies to all three new columns here — but the WORKER's willingness to
-- fill a real number on a backfill pass differs per column, same as it
-- already does among the fourteen:
--
--   open_pct_sum       reads tickets.pct_complete, which — like
--                       updated_at beside it — carries no history. A
--                       backfilled day would report TODAY's slider
--                       position as if it were true on a date it may not
--                       even have been assigned yet. Filled only on the
--                       actual current-day pass, same restriction as
--                       wip_updated_today, and NULL forever for every
--                       other day even after this migration exists.
--
--   delay_days_sum,     both derive purely from planned_close_date compared
--   open_due_next_7     against the day being summarised — the identical
--                       date arithmetic ns_overdue and ns_due_today already
--                       use. Backfillable exactly like those two, and the
--                       worker computes a real value (defaulting to 0, never
--                       leaving them NULL) on every pass including backfill.
--
-- WHY resource_daily_stats' TWO NEW COLUMNS ARE NOT NULL DEFAULT 0,
-- WHEN THE PROJECT TABLE'S THREE ARE NULL
--
-- V20260831_1620's header already drew this line too: a resource row is
-- rewritten by DELETE + INSERT only when a person earns one that day, so
-- there is no "row survives from before this column existed" case for
-- NULL to distinguish here the way there is on the project table. Every
-- resource-table column, activity-dependent or not, is written as a real
-- number — pct_sum computed as 0 on a day the worker is not actually
-- running on, matching wip_updated_today's own treatment two columns
-- above it, not left NULL.
-- =====================================================================

ALTER TABLE daily_ticket_stats
  ADD COLUMN open_pct_sum    INT NULL AFTER open_by_role,
  ADD COLUMN delay_days_sum  INT NULL AFTER open_pct_sum,
  ADD COLUMN open_due_next_7 INT NULL AFTER delay_days_sum;

ALTER TABLE resource_daily_stats
  ADD COLUMN pct_sum         INT NOT NULL DEFAULT 0 AFTER pending_review,
  ADD COLUMN delay_days_sum  INT NOT NULL DEFAULT 0 AFTER pct_sum;
