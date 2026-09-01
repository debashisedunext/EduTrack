-- ---------------------------------------------------------------------
-- Dashboard Rework Dev 2, PR 14 · §S-05 Analytics widget `module-open`,
-- module-wise total open tickets.
--
-- The fourth summary table, on client_daily_stats' precedent and for the
-- same reason: CLAUDE.md forbids a live COUNT(*) behind a dashboard, and
-- nothing already summarised can answer "how much open work per module".
-- daily_ticket_stats is keyed by project, resource_daily_stats by person,
-- client_daily_stats by client. tickets.module_id is orthogonal to all
-- three.
--
-- WHY project_id IS IN THE KEY, AND NOT ONLY module_id
--
-- Exactly A-059's argument, and it is a disclosure rather than a rounding
-- error. §2's row rule scopes every read by project; a table keyed by
-- (stat_date, module_id) alone can express no such filter, so a module's
-- bar would be the organisation's figure and every non-Admin reading the
-- widget would be served counts covering projects they cannot open a
-- single ticket from. Keyed by both, the scope predicate is the same
-- `project_id IN (…)` every other dashboard query carries, and a module's
-- bar is the sum over the projects that caller can see.
--
-- Unlike clients, modules are a small fixed master — blueprint §7.3 lists
-- eight — so the cardinality here is projects × 8 × days rather than
-- anything unbounded.
--
-- ALL THREE COLUMNS ARE STOCK, WHICH MEANS NONE OF THEM BACKFILL
--
-- A-050 applied this test to wip_by_stage and A-059 to open_total; this
-- table is the case where the answer is "no" for every column. "How many
-- of this module's tickets were work-in-progress at the end of 3 August"
-- is unrecoverable once that day has passed: status is a current value on
-- the ticket, and ticket_history records the transitions but the widget
-- would then be reconstructing state per day per module, which is the live
-- computation this table exists to avoid.
--
-- So the table starts empty and fills forward from the day it lands. That
-- is stated here rather than discovered: a chart that is blank for its
-- first days is correct, not broken, and the AsOfNotice already says the
-- figures are computed rather than live.
--
-- Summing stock ACROSS PROJECTS is sound; across DAYS it is not. A ticket
-- belongs to exactly one project, so one date over five projects counts it
-- once. Five dates counts it five times. Space, yes; time, never — which
-- is why the widget reads a single day and not a range sum.
--
-- THE THREE SEGMENTS PARTITION THE POPULATION, AND OVERDUE WINS
--
-- The widget is a stacked bar, and a stacked bar makes an arithmetic
-- claim: the segments add up to the whole. So the three are disjoint by
-- construction rather than by hope, and the precedence is fixed here in
-- the recompute rather than left to the chart:
--
--   open_overdue      planned_close_date has passed — whatever the status
--   open_wip          not overdue, status category IN_PROGRESS
--   open_not_started  not overdue, status category TODO
--
-- An overdue ticket that is also in progress is counted ONCE, under
-- overdue. Without that rule the bar overstates every module's load, the
-- overstatement is proportional to how late that module is running, and
-- nobody notices for a month because each segment is individually
-- plausible. `DailyStatsRepository.refreshModuleStats` carries the same
-- note at the point the SQL implements it.
--
-- THE POPULATION IS OUTSTANDING WORK, NOT EVERY UNCLOSED RECORD
--
-- Category DONE is excluded, which drops RESOLVED-not-CLOSED. Those are
-- tickets whose work is finished and whose record is still open — S-05
-- counts them on the Today tab's Pending Review card, where they are the
-- point. Including them here would put finished work in a chart titled
-- "open tickets" and, worse, in none of the three segments, so the bar
-- would silently stop summing to its own total.
--
-- TICKETS WITH NO MODULE ARE ABSENT, NOT ZERO
--
-- module_id is nullable — §7.5's "where it happened" fields postdate the
-- tickets raised before them, and V20260819_1336 added the column NULL.
-- Those tickets are not summarised here: there is no module whose open
-- count they are. The widget is therefore a breakdown of module-attributed
-- work rather than of all work, exactly as client-volume is of
-- client-attributed work, and the KPI cards remain where the total is read.
--
-- ROWS ARE EARNED, SO THE REFRESH CLEARS THE DAY FIRST
--
-- A (project, module) pair earns its row by having open work, and it can
-- stop earning one: tickets.module_id is mutable — §7.5's fields are
-- editable on the ticket — so re-pointing a ticket changes who qualified
-- on days already summarised. An upsert cannot retract what it wrote, so
-- the old module's row would survive intact while the new module's row
-- showed the same ticket, and one ticket would stand in two bars. Same
-- reason refreshClientStats and refreshStageStats delete the day and
-- rewrite it.
-- ---------------------------------------------------------------------

CREATE TABLE module_daily_stats (
  stat_date         DATE         NOT NULL,
  project_id        BIGINT       NOT NULL,
  -- INT, matching product_modules.id (V20260819_1336) rather than the
  -- BIGINT the other summary tables use for their own keys.
  module_id         INT          NOT NULL,

  -- ── stock: what was true at the end of this day ──────────────────────
  -- Disjoint, in precedence order. See the header: overdue wins.
  open_overdue      INT          NOT NULL DEFAULT 0,
  open_wip          INT          NOT NULL DEFAULT 0,
  open_not_started  INT          NOT NULL DEFAULT 0,

  computed_at       DATETIME(6)  NOT NULL,

  -- Leading stat_date matches the other three summary tables and serves
  -- the widget's access pattern: one date, every project in scope,
  -- grouped by module.
  PRIMARY KEY (stat_date, project_id, module_id),

  -- The scope predicate is `project_id IN (…)`, which needs the project
  -- column reachable without scanning the day.
  KEY ix_module_stats_project (project_id, stat_date),

  -- One module across a range, for a future module trend report — the
  -- PK cannot serve it, its leading column being the date.
  KEY ix_module_stats_module (module_id, stat_date),

  CONSTRAINT fk_module_stats_project
    FOREIGN KEY (project_id) REFERENCES projects (id),
  CONSTRAINT fk_module_stats_module
    FOREIGN KEY (module_id) REFERENCES product_modules (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
