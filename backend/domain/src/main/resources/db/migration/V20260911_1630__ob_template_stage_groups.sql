-- =====================================================================
-- ob_journey_template_stages — the Implementation Stage becomes a GROUP,
-- and the row that used to be a stage becomes a TASK under it.
--
-- Source: "Now we will have 4 steps: 1st the Module Name, 2nd
--         Implementation Stage (this will be treated as group), 3rd Task
--         (under this we will define TAT, task owner and document), 4th
--         the Task list."
--
-- WHY THE NEW LEVEL GOES ABOVE THE EXISTING ROW, NEVER IN ITS PLACE.
-- Inserting a level looks symmetrical — either the existing step row
-- becomes the group and tasks are new children, or it becomes the task
-- and the group is a new parent. It is not symmetrical. TEN tables hold
-- a foreign key to `ob_journey_steps`, the per-client copy of this row:
-- ob_step_clock_events, ob_step_history, ob_signoffs, ob_escalations,
-- ob_client_escalations, ob_attachments, ob_notifications,
-- ob_notification_outbox, ob_step_communications and
-- ob_journey_step_items. That row is the executable unit — it carries
-- the status, the SLA clock, the owner and the due date. Had it become
-- the group, all ten would anchor to a container that has none of those.
-- So the task IS the existing row, keeps every column it has, and gains
-- a parent. No data moves between tables and no running journey is
-- touched.
--
-- WHAT THIS TAKES BACK FROM V20260911_1600, AND WHY THAT IS NOT A
-- REVERSAL OF ITS ARGUMENT.
-- That migration, six hours old, added `implementation_stage_id` to the
-- step and `uq_..._stage (template_id, implementation_stage_id)` to
-- guarantee ONE step per stage. Its reasoning — the stages a service has
-- are decided on OB-15 and nowhere else — is untouched and is now
-- enforced one level up, on the group. What cannot survive is the
-- arity: a stage holds MANY tasks, so a unique key saying otherwise
-- refuses the feature. Applied migrations are corrected by new ones,
-- never edited (CLAUDE.md), which is what this is.
--
-- WHY THE GROUP COPIES THE STAGE'S NAME.
-- V20260911_1600's reason, unchanged and now applied here: a stage
-- renamed on OB-15 next year must not re-label a template published this
-- year, because the journeys instantiated from it carry the name their
-- client was shown.
--
-- WHY implementation_stage_id IS NULLABLE ON THE GROUP.
-- The steps that predate V20260911_1600 were hand-named — "Device
-- Rollout", "Kickoff & Requirement Sign-off" — and belong to no stage.
-- That migration refused to guess a stage for them and this one refuses
-- too. They are collected into one "Ungrouped" group per template, which
-- says exactly what is known: these tasks are in this service and in no
-- stage. MySQL treats NULLs as distinct in a unique index, so
-- `uq_ob_template_stages` does NOT hold that group to one per template —
-- ObJourneyTemplateService owns that invariant, the way
-- ObImplementationStageService owns its contiguous `sequence` and for the
-- same reason: it is the only writer.
--
-- WHY THE UNTOUCHED SEEDED STEPS ARE DELETED RATHER THAN CONVERTED.
-- V20260911_1600 seeded six steps into every Module Service. Converting
-- them would leave a "Configuration" task inside a "Configuration"
-- group, six times over, in every existing service — redundancy a person
-- then clears out by hand. A seeded step nobody has touched carries no
-- intent to preserve, so it goes, leaving the empty group the designer
-- now draws. "Untouched" is spelled out below and is deliberately
-- strict: any description, owner, TAT, sign-off, dependency, task-list
-- item, required document, dependent step or instantiated journey step
-- keeps it, and it converts into a task instead.
--
-- WHY sequence STAYS TEMPLATE-WIDE RATHER THAN BECOMING PER-GROUP.
-- Everything downstream — instantiation, the parallel-group layering,
-- the designer's tree — already orders steps by this one column. Made
-- per-group, each of those would need to learn to sort by (group, step)
-- or silently interleave two groups' tasks. Kept template-wide and
-- renumbered so that ascending `sequence` always walks groups in order
-- and tasks within a group in order, every one of those readers stays
-- correct without being changed. That renumbering is
-- `ObJourneyTemplateService`'s invariant from here on; this migration
-- establishes it once over the existing rows.
-- =====================================================================

CREATE TABLE ob_journey_template_stages (
  id                      BIGINT       NOT NULL AUTO_INCREMENT,
  template_id             BIGINT       NOT NULL,
  implementation_stage_id BIGINT       NULL
    COMMENT 'ob_implementation_stages.id. NULL = the Ungrouped group holding tasks that predate V20260911_1600.',
  name                    VARCHAR(120) NOT NULL
    COMMENT 'Copied from the stage at creation, so an OB-15 rename leaves published templates reading as published.',
  sequence                INT          NOT NULL
    COMMENT 'Position within the template, copied from the stage master and pinned. Not unique — see row 100 of the seed manifest for why a position column never is.',
  created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_ob_template_stages_template
    FOREIGN KEY (template_id) REFERENCES ob_journey_templates (id),
  CONSTRAINT fk_ob_template_stages_stage
    FOREIGN KEY (implementation_stage_id) REFERENCES ob_implementation_stages (id),
  -- One group per stage per template. This is V20260911_1600's rule,
  -- moved up a level: there is no second Configuration group to
  -- disambiguate, and the seeder is idempotent against it.
  UNIQUE KEY uq_ob_template_stages (template_id, implementation_stage_id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The task gains its parent. Nullable for the length of this migration
-- only — made NOT NULL at the bottom, once every surviving row has one.
-- ---------------------------------------------------------------------

ALTER TABLE ob_journey_template_steps
  ADD COLUMN template_stage_id BIGINT NULL AFTER template_id,
  ADD CONSTRAINT fk_ob_template_steps_stage_group
    FOREIGN KEY (template_stage_id) REFERENCES ob_journey_template_stages (id);


-- ---------------------------------------------------------------------
-- Backfill 1 — a group per (template, stage) already in use.
-- ---------------------------------------------------------------------

INSERT INTO ob_journey_template_stages (template_id, implementation_stage_id, name, sequence)
SELECT DISTINCT s.template_id, s.implementation_stage_id, m.name, m.sequence
  FROM ob_journey_template_steps s
  JOIN ob_implementation_stages m ON m.id = s.implementation_stage_id
 WHERE s.implementation_stage_id IS NOT NULL;

UPDATE ob_journey_template_steps s
  JOIN ob_journey_template_stages g
    ON g.template_id = s.template_id
   AND g.implementation_stage_id = s.implementation_stage_id
   SET s.template_stage_id = g.id
 WHERE s.implementation_stage_id IS NOT NULL;


-- ---------------------------------------------------------------------
-- Backfill 2 — one Ungrouped group per template that still holds a
-- hand-named step. `sequence` 9999 files it after the stages proper: a
-- template with both has its vocabulary first and its history last.
-- ---------------------------------------------------------------------

INSERT INTO ob_journey_template_stages (template_id, implementation_stage_id, name, sequence)
SELECT DISTINCT s.template_id, NULL, 'Ungrouped', 9999
  FROM ob_journey_template_steps s
 WHERE s.implementation_stage_id IS NULL;

UPDATE ob_journey_template_steps s
  JOIN ob_journey_template_stages g
    ON g.template_id = s.template_id
   AND g.implementation_stage_id IS NULL
   SET s.template_stage_id = g.id
 WHERE s.implementation_stage_id IS NULL;


-- ---------------------------------------------------------------------
-- The untouched seeded steps go. Every clause below is a way for a
-- person to have meant something by this row; one of them true and the
-- row stays and becomes a task.
--
-- The last clause reads ob_journey_template_steps while deleting from
-- it, which MySQL refuses (ERROR 1093) unless the subquery is
-- materialised — hence the derived table, which is the standard escape
-- rather than a flourish.
-- ---------------------------------------------------------------------

DELETE s FROM ob_journey_template_steps s
 WHERE s.implementation_stage_id IS NOT NULL
   AND s.name = (SELECT m.name FROM ob_implementation_stages m WHERE m.id = s.implementation_stage_id)
   AND s.description IS NULL
   AND s.owner_user_id IS NULL
   AND s.owner_role IS NULL
   AND s.backup_owner_user_id IS NULL
   AND s.requires_signoff = 0
   AND s.tat_days = 1
   AND s.depends_on_step_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM ob_journey_template_step_items i WHERE i.step_id = s.id)
   AND NOT EXISTS (SELECT 1 FROM ob_journey_template_step_docs d WHERE d.step_id = s.id)
   AND NOT EXISTS (SELECT 1 FROM ob_journey_steps j WHERE j.template_step_id = s.id)
   AND s.id NOT IN (
         SELECT held FROM (
           SELECT o.depends_on_step_id AS held
             FROM ob_journey_template_steps o
            WHERE o.depends_on_step_id IS NOT NULL
         ) AS dependents
       );


-- ---------------------------------------------------------------------
-- Renumber so that ascending `sequence` walks groups in order, then
-- tasks within a group. See the header for why this is worth doing once
-- here rather than teaching four readers a two-column sort.
-- ---------------------------------------------------------------------

UPDATE ob_journey_template_steps s
  JOIN (
        SELECT st.id,
               ROW_NUMBER() OVER (PARTITION BY st.template_id
                                  ORDER BY g.sequence, st.sequence, st.id) AS position
          FROM ob_journey_template_steps st
          JOIN ob_journey_template_stages g ON g.id = st.template_stage_id
       ) ordered ON ordered.id = s.id
   SET s.sequence = ordered.position;


-- ---------------------------------------------------------------------
-- Every surviving task now has a group, so say so. A task outside a
-- stage is not a state this model has.
-- ---------------------------------------------------------------------

ALTER TABLE ob_journey_template_steps
  MODIFY COLUMN template_stage_id BIGINT NOT NULL
    COMMENT 'ob_journey_template_stages.id — the stage group this task belongs to.';


-- ---------------------------------------------------------------------
-- And the column the group now owns. The FK is dropped before the index
-- because MySQL will not drop an index a foreign key is using; the
-- unique key goes with it, being the arity rule this migration replaces.
-- ---------------------------------------------------------------------

ALTER TABLE ob_journey_template_steps
  DROP FOREIGN KEY fk_ob_journey_template_steps_stage;

ALTER TABLE ob_journey_template_steps
  DROP INDEX uq_ob_journey_template_steps_stage,
  DROP COLUMN implementation_stage_id;
