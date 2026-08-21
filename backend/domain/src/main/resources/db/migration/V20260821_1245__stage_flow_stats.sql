-- ---------------------------------------------------------------------
-- A-058 · §S-05 widgets 16–19, the four the ribbon unlocks.
--
--   16  Stage funnel (WIP by stage)      horizontal funnel
--   17  Rework / ping-pong tickets       KPI pair
--   18  Avg time per stage               bar, split active vs idle
--   19  Handoff latency                  line trend
--
-- All four read ticket_stage_transitions, which is why this task waited on
-- Stream C. None of them may read it live: CLAUDE.md forbids a live
-- COUNT(*) behind a dashboard, and the transitions table is the largest in
-- the schema — one row per hop, so several per ticket, where every other
-- dashboard source is one row per ticket or fewer.
--
-- WIDGET 16 IS NOT HERE, AND THAT IS DELIBERATE
--
-- A-050 declared daily_ticket_stats.wip_by_stage for widget 16 and left it
-- NULL, naming this task as the one that fills it. Nothing about that
-- decision has gone stale, so it is honoured rather than replaced: the
-- worker gains a refreshWipByStage pass and no DDL is needed for it.
--
-- Which raises the obvious question about the table below — why are the
-- stage figures split across a JSON column and a keyed table?
--
--   wip_by_stage is a SNAPSHOT, read at ONE day. Widget 16 asks "how many
--   tickets sit in each stage right now", so the reader takes the latest
--   summarised day and stops. A JSON document is a fine shape for that:
--   one read, no arithmetic between rows.
--
--   Widgets 18 and 19 are AVERAGES ACROSS THE WINDOW. An average over
--   thirty days is the sum of the numerators over the sum of the
--   denominators, and that arithmetic has to happen in SQL across every
--   day in range. Summing a JSON document per row needs JSON_TABLE and a
--   lateral join per day, which is a statement nobody can read standing in
--   for a GROUP BY that anybody can.
--
-- So: the point-in-time fact stays in the JSON column A-050 shaped for it,
-- and the summable facts get rows. Each figure lives in exactly one place;
-- `sitting` is deliberately NOT duplicated into the table below.
--
-- WHY THE DURATION COLUMNS ARE MINUTES AND NOT HOURS
--
-- ticket_stage_transitions.duration_mins is working minutes from the
-- calendar service, and this table sums it unchanged. Converting to hours
-- here would round at storage time, and widget 18 divides these sums by a
-- visit count afterwards — rounding before dividing is how a stage
-- averaging 90 minutes comes to read as one hour. The presentation layer
-- divides by 60, once, at the end.
--
-- ROWS ARE EARNED, SO THE REFRESH CLEARS THE DAY FIRST
--
-- client_daily_stats' case exactly, and for a sharper reason. A (project,
-- stage) pair earns its row by having ribbon activity that day. It can
-- also stop earning one: B-019's workflow templates can be re-pointed and
-- V20260818_2140 already deprecates stage codes, so a stage that saw
-- traffic on a day already summarised can cease to exist. An upsert cannot
-- retract what it wrote, and the orphan would go on drawing a bar on
-- widget 18 for a stage no template defines. DailyStatsRepository deletes
-- the day and rewrites it.
--
-- NO FOREIGN KEY ON stage_code, AND THAT IS NOT AN OVERSIGHT
--
-- workflow_stages is keyed (template_id, stage_code) — the same code
-- appears once per template, so there is no single row to point at.
-- tickets.current_stage and ticket_stage_transitions.to_stage are both
-- plain VARCHARs holding the code for exactly that reason, and
-- V20260818_2140 says so. This column matches them, and a code with no
-- surviving definition renders under its own code rather than vanishing.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- Widget 17 · rework and ping-pong, on the table that already holds every
-- other per-project stock figure.
--
-- Not a table of their own: these are two counts per project per day keyed
-- exactly as daily_ticket_stats already is, and a fourth summary table for
-- two integers would be a join on every read of them.
--
-- STOCK, LIKE THE COLUMNS THEY SIT BESIDE, AND SO NEVER SUMMED OVER DAYS
--
-- "How many open tickets are bouncing" is a state, not an event. A-050's
-- header carries the general argument; the specific trap here is that a
-- ticket stuck at iteration 4 for a fortnight would count fourteen times.
--
-- THE TWO THRESHOLDS ARE THE BLUEPRINT'S, NOT A GUESS
--
-- §7.9 widget 17 is "iteration_no >= 2" — the ticket has been sent back at
-- least once. §4A.7's alert table is "iteration_no >= 3", the point at
-- which it stops being one correction and starts being ping-pong, and it
-- names the PM dashboard as where the flag belongs. Two columns rather
-- than one because the widget draws both and pingpong_open is a strict
-- subset of rework_open: a card showing only the total cannot tell a team
-- with ten one-off corrections from a team with ten tickets in a loop.
--
-- COMPUTED FROM THE TRANSITIONS, NOT FROM tickets.current_iteration
--
-- current_iteration is current state with no history, so "what was this
-- ticket's iteration at the end of 3 August" is unanswerable from it and
-- the column could never be backfilled. ticket_stage_transitions carries
-- iteration_no on every hop with entered_at beside it, so the same
-- question is a MAX over rows before that instant — recoverable for any
-- past day, which is what lets these two columns start with history
-- instead of a hole.
--
-- This is also the pairing A-068 was caught by: first-time-right read
-- current_iteration while the bounce rows read the transitions, so an
-- unpopulated ribbon produced a perfect quality score beside an empty
-- table. One source for one fact.
-- ---------------------------------------------------------------------
ALTER TABLE daily_ticket_stats
  ADD COLUMN rework_open   INT NOT NULL DEFAULT 0 AFTER aging_31_plus,
  ADD COLUMN pingpong_open INT NOT NULL DEFAULT 0 AFTER rework_open;


-- ---------------------------------------------------------------------
-- Widgets 18 and 19 · the flow facts, one row per stage per project per
-- day.
-- ---------------------------------------------------------------------
CREATE TABLE stage_daily_stats (
  stat_date      DATE         NOT NULL,
  project_id     BIGINT       NOT NULL,
  stage_code     VARCHAR(20)  NOT NULL,

  -- ── flow: what happened on this day ──────────────────────────────────

  -- Hops INTO this stage whose entered_at falls on this day. A-067's
  -- report-side stage funnel calls the same figure "passed through".
  entered        INT          NOT NULL DEFAULT 0,

  -- Visits SEALED on this day — exited_at set, duration_mins final.
  --
  -- The denominator of widget 18, and the reason it is a separate count
  -- from `entered`: a stage entered on Monday and left on Thursday is one
  -- entry and one exit on two different days. Dividing elapsed_mins by
  -- `entered` would divide Thursday's durations by Thursday's arrivals,
  -- which are different tickets.
  exited         INT          NOT NULL DEFAULT 0,

  -- Working minutes those sealed visits took, summed. Elapsed calendar
  -- time, weekends already excluded by the calendar service.
  --
  -- UNSEALED VISITS CONTRIBUTE NOTHING, deliberately. An open row is a
  -- ticket STILL in that stage and its duration is not yet a fact;
  -- including a partial stay would drag every average down, worst for
  -- exactly the stages where work is piling up now — the ones the widget
  -- exists to expose. A-067's stage-cycle-time report draws the same line.
  elapsed_mins   BIGINT       NOT NULL DEFAULT 0,

  -- Minutes somebody logged working, from ticket_effort_logs.stage_code.
  -- Idle is elapsed_mins - active_mins and is not stored: a derived column
  -- recomputable from two beside it is a third place for the subtraction
  -- to be got wrong.
  --
  -- Attributed by WORK DATE, per §4A.4 — a timesheet filled in on Friday
  -- for Monday's work belongs to Monday. That is the attribution
  -- resource_daily_stats.effort_hours already uses, and it is why A-051
  -- recomputes a trailing week rather than only today.
  --
  -- ATTRIBUTED BY STAGE CODE, NOT BY VISIT. ticket_effort_logs holds no
  -- transition reference, so a stage entered twice on a rework loop has
  -- its hours counted against the stage rather than against one of the two
  -- visits. Widget 18's active share on a reworked stage is therefore an
  -- upper bound and its idle share a lower one — the safe direction for a
  -- figure whose whole purpose is finding queue waste.
  active_mins    BIGINT       NOT NULL DEFAULT 0,

  -- ── widget 19 · handoff latency ──────────────────────────────────────
  --
  -- §7.6: handoff latency = next stage entered_at - previous stage
  -- exited_at. Dead time between one team finishing and the next picking
  -- up, which belongs to neither stage's duration and is invisible in
  -- every figure above.
  --
  -- Counted against the RECEIVING stage and the day the receiving hop
  -- began, because that is the queue the ticket was waiting in.
  -- Attributing it to the stage that finished would blame the team that
  -- did its job for the time the next one took to start.
  --
  -- Two columns rather than a stored average, for the reason the header
  -- gives: widget 19 averages across the window, and an average of daily
  -- averages weights a day with one handoff equally with a day with fifty.
  handoff_count  INT          NOT NULL DEFAULT 0,
  handoff_mins   BIGINT       NOT NULL DEFAULT 0,

  computed_at    DATETIME(6)  NOT NULL,

  -- Leading stat_date matches all three existing summary tables and the
  -- dashboard's own access pattern: a contiguous range, every project in
  -- scope, grouped by stage.
  PRIMARY KEY (stat_date, project_id, stage_code),

  -- §2's row rule is `project_id IN (…)` on every read, so the project
  -- column has to be reachable without scanning the day.
  KEY ix_stage_stats_project (project_id, stat_date),

  -- Widget 18 draws one bar per stage across the whole window, which the
  -- PK cannot serve — its leading column is the date.
  KEY ix_stage_stats_stage (stage_code, stat_date),

  CONSTRAINT fk_stage_stats_project
    FOREIGN KEY (project_id) REFERENCES projects (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
