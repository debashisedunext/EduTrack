-- ---------------------------------------------------------------------
-- A-050 · the pre-aggregated tables the dashboard reads instead of counting.
--
-- CLAUDE.md and PLAN.md §8 both state the rule absolutely: dashboard reads
-- never issue a live COUNT(*) over tickets. Twenty widgets (§S-05) times
-- every user's dashboard load is the query pattern that stops working first
-- as the ticket table grows, and A-073 targets 50,000.
--
-- THE GRAIN, AND WHY IT IS THIS ONE
--
-- The widgets divide into two kinds of number and only one of them
-- aggregates over days:
--
--   flow  — what HAPPENED on a day: created, closed, reopened. Summing a
--           week of these is meaningful.
--   stock — what was TRUE at a moment: open, delayed, aging, work in
--           progress. Summing a week of "how many were open" is nonsense.
--
-- A table holding only flow would leave widgets 2, 5, 12 and 16 with no
-- option but to count tickets live, which is the thing being forbidden. So
-- each row carries both: what happened that day, and what was true at the
-- end of it.
--
-- Keyed (stat_date, project_id) because those are two of the three
-- dashboard filters and the third — resource — gets its own table below.
-- Breakdowns by level and age are COLUMNS rather than key dimensions: as a
-- key they multiply the row count by every combination and still cannot
-- express "open by stage", while as columns each KPI card is a single read.
--
-- WHY THE EMPTY COLUMNS ARE HERE NOW AND NOT ADDED LATER
--
-- wip_by_stage stays NULL until Stream C's transitions carry real data
-- (A-058 is blocked on them, and the backlog says so). It is declared now
-- anyway, because a stock column cannot be backfilled: "how many tickets
-- were in QA at the end of 3 August" is unrecoverable once that day has
-- passed. A column added later starts with a hole exactly as long as the
-- delay in adding it.
--
-- REFRESH IS A FULL RECOMPUTE, NOT AN ACCUMULATION (A-051 does the work)
--
-- Every figure here is derivable from source tables for any past date —
-- open-at-end-of-day is `date_reported <= d AND (actual_close_date IS NULL
-- OR actual_close_date > d)`, and the same shape covers aging and delay. So
-- a refresh recomputes a day from scratch and is idempotent. That is what
-- makes the worker self-healing: after an outage it recomputes the days it
-- missed and is correct again, where an accumulating counter would have
-- drifted with no way back. computed_at records when, so a stale row is
-- visible rather than merely wrong.
-- ---------------------------------------------------------------------

CREATE TABLE daily_ticket_stats (
  stat_date        DATE         NOT NULL,
  project_id       BIGINT       NOT NULL,

  -- ── flow: what happened on this day ──────────────────────────────────
  created          INT          NOT NULL DEFAULT 0,
  closed           INT          NOT NULL DEFAULT 0,
  reopened         INT          NOT NULL DEFAULT 0,

  -- ── stock: what was true at the end of this day ──────────────────────
  open_total       INT          NOT NULL DEFAULT 0,
  open_critical    INT          NOT NULL DEFAULT 0,
  open_high        INT          NOT NULL DEFAULT 0,
  open_medium      INT          NOT NULL DEFAULT 0,
  open_low         INT          NOT NULL DEFAULT 0,
  open_delayed     INT          NOT NULL DEFAULT 0,
  open_reopened    INT          NOT NULL DEFAULT 0,

  -- §S-05 widget 12. Bucket edges are fixed here rather than computed at
  -- read time: a bucket boundary that can move is a chart that silently
  -- changes shape between two loads of the same day.
  aging_0_2        INT          NOT NULL DEFAULT 0,
  aging_3_7        INT          NOT NULL DEFAULT 0,
  aging_8_30       INT          NOT NULL DEFAULT 0,
  aging_31_plus    INT          NOT NULL DEFAULT 0,

  -- §S-05 widget 16, NULL until Stream C's transitions are real. JSON
  -- rather than a column per stage because stages are a master table
  -- (B-019) and a workflow template can define its own — a column set
  -- would need a migration every time somebody adds a stage.
  wip_by_stage     JSON         NULL,

  computed_at      DATETIME(6)  NOT NULL,

  PRIMARY KEY (stat_date, project_id),
  -- The dashboard's date filter reads a contiguous range across all
  -- projects; the PK's leading column already serves that. This second
  -- index serves the per-project trend, which reads one project across a
  -- range and would otherwise scan.
  KEY ix_daily_stats_project (project_id, stat_date),
  CONSTRAINT fk_daily_stats_project
    FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- resource_daily_stats — the same idea keyed by person.
--
-- A separate table rather than a third key column on the one above,
-- because the two answer different questions and share almost no columns.
-- Folding them together would give every ticket row a NULL user_id and
-- every resource row a NULL project_id, and every query would have to say
-- which kind of row it wanted.
--
-- Serves widgets 9 (velocity), 10 (resource load) and A-062's developer
-- dashboard, which is the same figures scoped to one person.
-- ---------------------------------------------------------------------

CREATE TABLE resource_daily_stats (
  stat_date        DATE          NOT NULL,
  user_id          BIGINT        NOT NULL,

  -- ── flow ─────────────────────────────────────────────────────────────
  closed           INT           NOT NULL DEFAULT 0,
  -- Effort logged FOR this day's work_date, not effort entered on this
  -- day. §4A.4 attributes hours to when the work happened, and a
  -- timesheet filled in on Friday for Monday belongs on Monday.
  effort_hours     DECIMAL(7,2)  NOT NULL DEFAULT 0.00,

  -- ── stock at end of day ──────────────────────────────────────────────
  assigned_open    INT           NOT NULL DEFAULT 0,
  assigned_critical INT          NOT NULL DEFAULT 0,
  assigned_delayed INT           NOT NULL DEFAULT 0,

  computed_at      DATETIME(6)   NOT NULL,

  PRIMARY KEY (stat_date, user_id),
  KEY ix_resource_stats_user (user_id, stat_date),
  CONSTRAINT fk_resource_stats_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
