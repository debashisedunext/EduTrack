-- =====================================================================
-- Dashboard Rework Dev 1, PR 4 · `daily_ticket_stats` — Today's Progress
--
-- Table altered: daily_ticket_stats (fourteen INT columns + one JSON)
-- Source: docs/Dashboard-Rework-Plan.md, "Data" §2
--
-- WHY NULL, NOT NOT NULL DEFAULT 0 — THE `wip_by_stage` / `type_counts`
-- PRECEDENT, EXTENDED
--
-- Every other INT column on this table (A-050's original set) is
-- NOT NULL DEFAULT 0: a project's ticket volume and open counts are
-- computable for any day since the table has existed, so "no tickets"
-- and "not computed" never need telling apart. Several of these
-- fourteen cannot make that promise:
--
--   wip_updated_today  reads tickets.updated_at, which carries no
--                       history (the class-level note on
--                       DailyStatsRepository already says this of
--                       assigned_to and planned_close_date). Computing
--                       it for a day other than the one the worker is
--                       running on would report today's edit activity
--                       as if it happened that day — a wrong number,
--                       not a stale one. It is therefore populated only
--                       when the day being recomputed IS the actual
--                       current day, and left NULL otherwise, on every
--                       pass, forever. NULL here means "this question
--                       does not apply to a day that has already
--                       ended," not "not computed yet."
--
--   pending_review      resolves through workflow_stages.is_review_stage
--                        (V20260831_1550), which did not exist before
--                        this PR — a day summarised before that
--                        migration ran has no honest answer for it,
--                        the same reasoning A-056 gave for type_counts
--                        staying NULL on rows older than A-056 itself.
--
-- The other twelve (ns_*, wip_total, wip_near_delay, wip_delayed,
-- started_today, finished_*, blocked_*) ARE derivable for any past day
-- from timestamps the rows already carry, and the worker computes them
-- on every pass including backfill — declared NULL anyway, for the
-- same reason wip_by_stage was declared NULL before A-058 could fill
-- it: a stock column's absence is either "computed with nothing to
-- report" (which these upsert to zero, since every project gets a row)
-- or "not computed on this database yet," and only NULL can mean the
-- second one on a table that predates this migration.
--
-- SECOND PASS, NOT A COLUMN IN refreshTicketStats' INSERT … SELECT
--
-- A-056's header already recorded why: a correlated read of `tickets`
-- from inside that INSERT … SELECT contends with the shared locks the
-- statement is already holding on the same rows —
-- `CannotAcquireLock`, reproduced on a suite that passed the day
-- before. `refreshTypeCounts` and `refreshWipByStage` both dodge it by
-- running as their own UPDATE … JOIN afterwards; this table's
-- worker-side counterpart, `refreshTodayStats`, follows the identical
-- shape for the identical reason rather than growing the statement
-- that already deadlocked once.
--
-- WHY open_by_role IS JSON, NOT SIX COLUMNS
--
-- The same argument A-050 made for wip_by_stage and A-056 for
-- type_counts: the role vocabulary is a master table (`roles`, seeded
-- by B-001) an Admin's Role Master (S-09) can in principle extend, and
-- a column per role would need a migration every time it did. Keyed by
-- `roles.code` — DEVELOPER, QA, PM, SUPPORT_DESK, DEPLOYMENT, ADMIN —
-- plus the literal string `UNASSIGNED` for `assigned_to IS NULL`, which
-- is not a role and is deliberately not folded into any real one: an
-- unheld ticket is a different fact from a ticket held by whichever
-- role happens to be smallest.
-- =====================================================================

ALTER TABLE daily_ticket_stats
  -- ── Not Started (status category TODO: NEW, REOPENED) ────────────────
  ADD COLUMN ns_total             INT  NULL AFTER pingpong_open,
  ADD COLUMN ns_overdue           INT  NULL AFTER ns_total,
  ADD COLUMN ns_due_today         INT  NULL AFTER ns_overdue,

  -- ── WIP (status IN_PROGRESS or REWORK — see the worker for why this
  -- is narrower than the whole IN_PROGRESS category, which also holds
  -- ON_HOLD and AWAITING_INFO: those are `blocked_*` below, not WIP) ───
  ADD COLUMN wip_total            INT  NULL AFTER ns_due_today,
  ADD COLUMN wip_updated_today    INT  NULL AFTER wip_total,
  ADD COLUMN wip_near_delay       INT  NULL AFTER wip_updated_today,
  ADD COLUMN wip_delayed          INT  NULL AFTER wip_near_delay,

  -- ── Started / Finished Today — per-cycle stamps, not per-ticket ──────
  ADD COLUMN started_today        INT  NULL AFTER wip_delayed,
  ADD COLUMN finished_early       INT  NULL AFTER started_today,
  ADD COLUMN finished_on_time     INT  NULL AFTER finished_early,
  ADD COLUMN finished_late        INT  NULL AFTER finished_on_time,

  -- ── Blocked (status ON_HOLD / AWAITING_INFO) ──────────────────────────
  ADD COLUMN blocked_on_hold      INT  NULL AFTER finished_late,
  ADD COLUMN blocked_awaiting_info INT NULL AFTER blocked_on_hold,

  -- ── Pending Review — RESOLVED-not-CLOSED plus review-stage tickets ────
  ADD COLUMN pending_review       INT  NULL AFTER blocked_awaiting_info,

  -- ── Open Issues, by role of whoever currently holds the ticket ────────
  ADD COLUMN open_by_role         JSON NULL AFTER pending_review,

  ADD CONSTRAINT ck_open_by_role_is_object
    CHECK (open_by_role IS NULL OR JSON_TYPE(open_by_role) = 'OBJECT');
