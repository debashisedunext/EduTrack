-- =====================================================================
-- B-042 · S-13 tab 2 — workflow_stages.is_deprecated
--
-- Table altered: workflow_stages (two columns, no backfill)
-- Source: blueprint §7.4 — "Stages used by live tickets can only be
--         deprecated, never deleted — otherwise historical ribbons would
--         break" — and §17's risk table, which states the same rule a
--         second time as a mitigation.
--
-- WHY DELETION IS DESTRUCTIVE HERE AND NOWHERE OBVIOUS
--
-- A-005 made `ticket_stage_transitions.to_stage` and
-- `tickets.current_stage` plain VARCHAR columns holding the stage *code*,
-- deliberately without a foreign key onto this table. So a DELETE against
-- `workflow_stages` breaks no constraint, cascades nothing, and fails
-- nowhere. What it does instead is leave every historical ribbon segment
-- pointing at a definition that no longer exists, and stop Stream D's
-- §4A.7 stuck-in-stage scan matching those rows — the same pair of silent
-- consequences B-040 froze `stage_code` against, one verb further along.
--
-- The database cannot refuse this, which is exactly why it is a column
-- and a service rule rather than a constraint. A row that must survive
-- for its history needs somewhere to record that it is retired.
--
-- WHY TWO COLUMNS AND NOT ONE
--
-- `deprecated_at` is not decoration. The question an Admin asks about a
-- retired stage is "when did we stop using this?", and the answer is not
-- derivable: `ticket_stage_transitions` records the last hop *into* the
-- stage, which is when it was last used rather than when it was retired,
-- and those differ by exactly the interval that makes the question worth
-- asking. `created_at` on this table answers neither.
--
-- The CHECK keeps them from disagreeing. A stage flagged deprecated with
-- no timestamp, or timestamped with the flag cleared, is a row no code
-- path can produce and every reader would have to defend against —
-- `ck_workflow_stages_deprecation` means none of them has to.
--
-- NOT NULL WITH A DEFAULT, WHICH B-039 ARGUED AGAINST
--
-- B-039's `statuses.category` was added nullable, backfilled row by row
-- and only then constrained, because a DEFAULT would have outlived the
-- migration and let a ninth status arrive carrying a category nobody
-- chose. The opposite is right here, and the difference is that a
-- category is a *judgement* while this is a *state with a correct
-- initial value*: every one of B-004's 18 stages is live, every stage
-- created after this file is live on the day it is created, and there is
-- no row for which 0 is a guess. A nullable column would mean three
-- states on the wire where the domain has two.
--
-- THE INDEX
--
-- `uq_workflow_stages_seq (template_id, seq)` already covers the
-- template. This one covers the read the tab and every future stage
-- picker actually make — "the live stages of this template, in ribbon
-- order" — without touching the seq unique key, which the reorder writes
-- through twice per drag and should not be asked to serve a filter too.
-- Eighteen rows do not need it for speed; it is here so that the
-- distinction between live and retired stays cheap on the day a
-- long-running organisation has retired more stages than it runs.
-- =====================================================================


-- 1. The flag and the date it was set.
ALTER TABLE workflow_stages
  ADD COLUMN is_deprecated  TINYINT(1)  NOT NULL DEFAULT 0 AFTER can_return_to,
  ADD COLUMN deprecated_at  DATETIME(6) NULL               AFTER is_deprecated;


-- 2. They move together or not at all. MySQL 8.4 enforces CHECK
-- (8.0.16+), unlike the parse-and-ignore behaviour older guidance warns
-- about — PLAN.md §3.1.
ALTER TABLE workflow_stages
  ADD CONSTRAINT ck_workflow_stages_deprecation
    CHECK ((is_deprecated = 0 AND deprecated_at IS NULL)
        OR (is_deprecated = 1 AND deprecated_at IS NOT NULL));


-- 3. "The live stages of this template, in ribbon order."
CREATE INDEX ix_workflow_stages_live
  ON workflow_stages (template_id, is_deprecated, seq);
