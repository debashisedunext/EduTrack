-- ---------------------------------------------------------------------
-- A-059 · §S-05 widget 20, client-wise volume.
--
-- The third summary table, and it exists for the reason the first two do:
-- CLAUDE.md forbids a live COUNT(*) behind a dashboard, and nothing already
-- summarised can answer "how many tickets per client". daily_ticket_stats
-- is keyed by project and resource_daily_stats by person; a client is
-- neither, and tickets.client_id is orthogonal to both.
--
-- WHY project_id IS IN THE KEY, AND NOT ONLY client_id
--
-- This is the column the table would be wrong without, and the mistake it
-- prevents is a disclosure rather than a rounding error.
--
-- §2's row rule scopes every read by project: a PM or Support user sees
-- their projects and nothing else. A table keyed by (stat_date, client_id)
-- alone can express no such filter — a client's volume in it is the
-- organisation's figure, and every non-Admin reading the widget would be
-- served numbers covering projects they cannot open a single ticket from.
-- The bar would be unclickable proof of work they are not scoped to see.
--
-- Keyed by both, the scope predicate is the same `project_id IN (…)` every
-- other dashboard query already carries, and a client's bar is the sum over
-- the projects that caller can see. Two callers with different scopes get
-- different bars for the same client, which is correct: each is the honest
-- answer to "this client's volume, in the work you can see".
--
-- Summing stock ACROSS PROJECTS is sound; summing it across DAYS is not.
-- A-050's header states the second half. The first is worth stating too,
-- because the reader who has internalised "never sum stock" will hesitate
-- here: open_total on one date across five projects counts each open
-- ticket once, since a ticket belongs to exactly one project. Across five
-- dates it counts the same ticket five times. Space, yes; time, never.
--
-- WHICH COLUMNS ARE HERE NOW, AND WHY THAT IS THE LINE
--
-- Widget 20 reads `created` and nothing else. `closed` and `open_total`
-- are here anyway, and the test is the one A-050 applied to wip_by_stage:
-- can it be backfilled?
--
--   open_total  — NO. "How many of this client's tickets were open at the
--                 end of 3 August" is unrecoverable once that day passes,
--                 the same way a project's was. A stock column added with
--                 A-068's client report starts with a hole exactly as long
--                 as the delay in adding it, and the client report is a
--                 trend report — a hole is precisely what it cannot have.
--   closed      — yes, derivable from actual_close_date for any past date,
--                 and here only because the recompute already reads the
--                 rows and computing it costs one more SUM.
--
-- What is deliberately NOT here: sla_closed/sla_met and resolution time,
-- both of which A-068's client report needs (blueprint §7.8 — "Volume,
-- open versus closed, SLA compliance, avg resolution time and satisfaction
-- per client"). Both are flow, both derive from immutable close
-- timestamps, and both are therefore backfillable in full whenever that
-- task lands. Declaring columns now that nothing reads and no test
-- exercises would be guessing at their shape a task early.
--
-- TICKETS WITH NO CLIENT ARE ABSENT, NOT ZERO
--
-- client_id is nullable — §4B.7 is explicit that an internally-raised
-- ticket need not belong to a client — and those tickets are not
-- summarised here at all. There is no client whose volume they are. The
-- widget is therefore a breakdown of client-attributed work and not of all
-- work, which is what "client-wise" asks for; the KPI cards remain the
-- place the organisation's total is read.
--
-- ROWS ARE EARNED, SO THE REFRESH CLEARS THE DAY FIRST
--
-- Unlike daily_ticket_stats, where every project gets a row on every day
-- by construction, a (project, client) pair earns its row by having
-- something to report. That makes it resource_daily_stats' case exactly: a
-- pair can also STOP earning one — tickets.client_id is mutable, and
-- re-attributing a ticket changes who qualified on days already
-- summarised. An upsert cannot retract what it wrote last time, so the old
-- client's row would survive with its count intact while the new client's
-- row showed the same ticket, and the volume would be double-counted
-- across two bars. DailyStatsRepository.refreshClientStats deletes the day
-- and rewrites it for that reason.
-- ---------------------------------------------------------------------

CREATE TABLE client_daily_stats (
  stat_date    DATE         NOT NULL,
  project_id   BIGINT       NOT NULL,
  client_id    BIGINT       NOT NULL,

  -- ── flow: what happened on this day ──────────────────────────────────
  -- §S-05 widget 20. "Volume" is intake — tickets raised — which is what
  -- blueprint §7.8 means by listing Volume separately from "open versus
  -- closed", and it is the measure that sums correctly over the dashboard's
  -- date window. An open-tickets bar would have been widget 15's treemap
  -- with the labels changed.
  created      INT          NOT NULL DEFAULT 0,
  closed       INT          NOT NULL DEFAULT 0,

  -- ── stock: what was true at the end of this day ──────────────────────
  open_total   INT          NOT NULL DEFAULT 0,

  computed_at  DATETIME(6)  NOT NULL,

  -- Leading stat_date matches the other two summary tables and serves the
  -- dashboard's own access pattern: a contiguous date range, every project
  -- in scope, grouped by client.
  PRIMARY KEY (stat_date, project_id, client_id),

  -- A-068's client report reads one client across a range, which the PK
  -- cannot serve — its leading column is the date.
  KEY ix_client_stats_client (client_id, stat_date),

  -- The scope predicate is `project_id IN (…)`, so the project column needs
  -- to be reachable without scanning the day.
  KEY ix_client_stats_project (project_id, stat_date),

  CONSTRAINT fk_client_stats_project
    FOREIGN KEY (project_id) REFERENCES projects (id),
  CONSTRAINT fk_client_stats_client
    FOREIGN KEY (client_id) REFERENCES clients (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
