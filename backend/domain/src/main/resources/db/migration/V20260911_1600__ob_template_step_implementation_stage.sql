-- =====================================================================
-- ob_journey_template_steps.implementation_stage_id — a step IS an
-- implementation stage.
--
-- Source: the request that opened this task — "if I create a new module
--         named Payment Gateway then the six implementation stages come
--         automatically, we can add subtasks inside them, and we can
--         only use these six stages".
--
-- WHAT THIS CHANGES ABOUT THE DESIGNER.
-- A template step used to be a free-text name an admin typed. It is now
-- a row of the OB-15 master, chosen from a closed list: the name is the
-- stage's, and a step that names no stage cannot be created any more.
-- `ObJourneyTemplateService#createTemplate` seeds one step per ACTIVE
-- stage the moment a Module Service is born, so the six arrive without
-- anybody adding them, and the admin's work is editing them rather than
-- inventing them.
--
-- WHY THE COLUMN IS NULLABLE.
-- Every step that predates this migration was typed by hand — "Device
-- Rollout", "Attendance Policy Mapping" — and belongs to no stage.
-- Backfilling them by matching names would be a guess, and a wrong guess
-- silently re-labels somebody's published template; deleting them would
-- empty the journeys pinned to those versions. So the old rows keep a
-- NULL and keep rendering their own names, and the closed list applies
-- to steps created from here on, which is the only half of the rule a
-- migration can honestly enforce. `ObJourneyTemplateDtos.AddStepRequest`
-- is where "from here on" is enforced: it has no `name` field left to
-- send.
--
-- WHY (template_id, implementation_stage_id) IS UNIQUE.
-- One step per stage per template. That is what makes "the six come
-- automatically" a complete answer rather than a starting point somebody
-- has to tidy: there is no second Configuration step to disambiguate,
-- the add-step picker offers exactly the stages this template is missing,
-- and it is empty when the template already holds them all. MySQL treats
-- NULLs as distinct in a unique index, so the legacy rows above are
-- untouched by it — any number of them may sit on one template.
--
-- WHY RESTRICT RATHER THAN CASCADE.
-- The same reason OB-15 has no delete route at all: a stage referenced by
-- a template step is a value the step resolves its own name through, and
-- there is no version of removing it that leaves the step readable.
-- Retiring a stage (`is_active = FALSE`) is the supported move — it drops
-- out of the add-step picker and changes nothing already written.
-- =====================================================================

ALTER TABLE ob_journey_template_steps
  ADD COLUMN implementation_stage_id BIGINT NULL
    COMMENT 'ob_implementation_stages.id — the stage this step is. NULL only on steps that predate V20260911_1600.'
    AFTER name,
  ADD CONSTRAINT fk_ob_journey_template_steps_stage
    FOREIGN KEY (implementation_stage_id) REFERENCES ob_implementation_stages (id),
  ADD UNIQUE KEY uq_ob_journey_template_steps_stage (template_id, implementation_stage_id);
