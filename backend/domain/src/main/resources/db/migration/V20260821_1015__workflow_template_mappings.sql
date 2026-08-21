-- =====================================================================
-- B-041 · S-13 tab 3 — workflow_template_mappings
--
-- Table created: workflow_template_mappings
-- Source: blueprint §4A.9 — "the workflow template master (screen S-13)
--         lets an Admin define a template per project and per task type"
--         — and §7.4 tab 3, "built by picking stages, then mapped to
--         project x task type".
--
-- WHY THIS IS A TABLE AND NOT TWO COLUMNS ON workflow_templates
--
-- The contract has carried `projectId` and `taskTypeId` as scalar fields
-- on `WorkflowTemplateWriteRequest` since D-001, which reads as though a
-- template belongs to one pair. §4A.9's own three examples refute it:
-- Standard Dev Flow is mapped to Production Bug, Change Request *and*
-- Future Release, and Infra Flow to Server Issue and Network Issue. A
-- scalar pair cannot hold that, so B-040 removed both fields from the
-- response rather than serve two nulls, and named this task as the one
-- that brings the mapping back with somewhere to store it.
--
-- The relation is many-to-one from the pair's side and one-to-many from
-- the template's: a template maps to many pairs, and a pair resolves to
-- exactly one template, or ticket creation would have to choose.
--
-- WHY BOTH COLUMNS ARE NULLABLE
--
-- NULL means "any". §4A.9 asks for a template "per project and per task
-- type", and an organisation that runs one flow for every task type on
-- one project should say so in one row rather than eleven — one per
-- seeded task type, each needing a twelfth added the day somebody adds a
-- task type. The four rungs of the resolution ladder are what the two
-- nullable columns exist to express:
--
--   1. (project, task type)  — this project, this task type
--   2. (project, NULL)       — this project, any task type
--   3. (NULL, task type)     — any project, this task type
--   4. workflow_templates.is_default — the last rung, and the reason
--      exactly one template may carry the flag
--
-- Resolution is TemplateResolver's, not the database's: a view could
-- express the ladder, and a view could not tell a caller which rung
-- answered, which is the one thing the screen has to show.
--
-- THE GENERATED COLUMNS ARE THE POINT OF THIS FILE
--
-- `UNIQUE KEY (project_id, task_type_id)` looks like it enforces "one
-- template per pair" and does not. In MySQL a NULL is distinct from
-- every other NULL inside a unique index, so (5, NULL) may be inserted
-- twice, by two different templates, and neither insert fails. Rung 2
-- then has two answers and the resolver returns whichever the optimiser
-- reached first — a ticket created on Monday and one created on Tuesday
-- getting different ribbons, with nothing anywhere recording that the
-- configuration was ambiguous.
--
-- Collapsing NULL to 0 in a STORED generated column and keying on that
-- restores the constraint, because 0 is not distinct from 0. Neither
-- table has an id of 0 (both are AUTO_INCREMENT, which starts at 1), so
-- the sentinel cannot collide with a real row. MySQL 8.0.13+ would also
-- accept a functional index on the expression directly; the generated
-- columns are preferred because they are visible in DESCRIBE and in an
-- EXPLAIN, and a constraint whose mechanism is invisible is one somebody
-- removes while tidying.
--
-- WHY THE FOREIGN KEYS DIFFER
--
-- `template_id` is ON DELETE CASCADE: a mapping is a fact *about* a
-- template and means nothing without it, the same call A-005 made for
-- workflow_stages. Whether the template may be deleted at all is
-- TemplateService's, and mostly it may not.
--
-- `project_id` and `task_type_id` are RESTRICT (the default), which is
-- deliberate and the opposite call. A mapping is not a fact about the
-- project; it is a routing rule an Admin authored, and cascading it away
-- with the project would silently re-route every task type that rule
-- covered to the default template. RESTRICT makes deleting a mapped
-- project fail loudly instead. Neither master offers a hard delete
-- today, so in practice this is insurance rather than a live path.
--
-- NO STREAM A REVIEW
--
-- CLAUDE.md asks for Stream A's review on migrations touching `tickets`,
-- `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions`.
-- This file touches none of them and creates a table with no other
-- reader — the same call B-039 and B-042 made. `tickets` is *read* by
-- the resolver through `workflow_template_id`, and reading is not what
-- that clause is about.
-- =====================================================================


CREATE TABLE workflow_template_mappings (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  template_id   BIGINT      NOT NULL,
  project_id    BIGINT      NULL,        -- NULL = any project
  task_type_id  INT         NULL,        -- NULL = any task type

  -- The two columns that make the unique key mean what it reads as.
  -- STORED rather than VIRTUAL: a unique index over a virtual column is
  -- accepted by MySQL but recomputes the expression on every read of the
  -- index, and this one is read on every ticket creation.
  project_key   BIGINT      AS (IFNULL(project_id, 0))   STORED NOT NULL,
  task_type_key INT         AS (IFNULL(task_type_id, 0)) STORED NOT NULL,

  created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),

  -- One template per pair, NULLs included. See the header.
  UNIQUE KEY uq_workflow_template_mappings_pair (project_key, task_type_key),

  -- "Every mapping of this template", which is the read tab 3 makes.
  KEY ix_workflow_template_mappings_template (template_id),

  -- The resolver asks for up to three pairs at once, all of them against
  -- the generated columns, so the unique key above serves it. These two
  -- cover the RESTRICT check when a project or task type is deleted,
  -- which would otherwise scan the table.
  KEY ix_workflow_template_mappings_project (project_id),
  KEY ix_workflow_template_mappings_task_type (task_type_id),

  CONSTRAINT fk_workflow_template_mappings_template
    FOREIGN KEY (template_id) REFERENCES workflow_templates (id)
      ON DELETE CASCADE,
  CONSTRAINT fk_workflow_template_mappings_project
    FOREIGN KEY (project_id) REFERENCES projects (id),
  CONSTRAINT fk_workflow_template_mappings_task_type
    FOREIGN KEY (task_type_id) REFERENCES task_types (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Seed §4A.9's own three sentences, and nothing beyond them.
--
-- The blueprint names the task types for each template in parentheses,
-- and those seven rows are the whole of what it says. Every other pair
-- falls through to rung 4 — Standard Dev Flow, which carries
-- is_default = 1 from B-004 and which §4A.9 calls the flow every other
-- template is a reduction of.
--
-- All seven are (NULL, task_type): §4A.9's examples are task-type rules
-- that hold across projects. A project-scoped row would be inventing a
-- policy for a project this seed knows nothing about, so rungs 1 and 2
-- are left entirely to the Admin — which also means tab 3 opens on a
-- state where the ladder is genuinely exercised rather than one where
-- every lookup hits the same rung.
--
-- Matched by code rather than by id. B-002's task type ids are stable in
-- the seed and this file is the wrong place to depend on that. A code
-- that does not exist contributes no row rather than failing the
-- migration, which is the right call for a seed whose only job is to
-- make the screen open on something recognisable.
-- ---------------------------------------------------------------------
INSERT INTO workflow_template_mappings (template_id, project_id, task_type_id)
SELECT wt.id, NULL, tt.id
  FROM workflow_templates wt
  JOIN task_types tt
    ON (wt.name = 'Standard Dev Flow'
        AND tt.code IN ('PRODUCTION_BUG', 'CHANGE_REQUEST', 'FUTURE_RELEASE'))
    OR (wt.name = 'Support Fast-Track'
        AND tt.code IN ('CLIENT_REQUEST', 'BROWSER_ISSUE'))
    OR (wt.name = 'Infra Flow'
        AND tt.code IN ('SERVER_ISSUE', 'NETWORK_ISSUE'));
