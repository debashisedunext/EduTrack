-- =====================================================================
-- B-019 · S-10 Project Master — the Settings tab's storage.
--
-- Tables: projects (created by A-003, V20260805_1024)
--         project_task_types (new)
--
-- The tab has three settings and one of them already has a column:
-- `auto_assign_rule` was added by B-016 (V20260813_1420), whose header
-- says in as many words that "the Settings tab that edits it is B-019"
-- and that the *other* half — allowed task types — was left out
-- deliberately, because inventing a join table's shape from a branch
-- that could not use it would be deciding this task's design in a
-- migration nobody building it would think to read. This is that shape.
--
-- FLAGGED FOR STREAM A. `projects` is A-003's table. CLAUDE.md mandates
-- their review only for `tickets` and the three append-only tables and
-- this touches none of those — but the identity baseline should not
-- change on a masters branch unremarked. Same precedent B-011, B-016 and
-- B-017 each set.
--
-- ---------------------------------------------------------------------
-- 1. `project_task_types` — a join table, and an ABSENCE that means
--    "unrestricted"
--
-- This is the decision the whole feature turns on, so it is written down
-- here rather than only in the service:
--
--   NO ROWS FOR A PROJECT MEANS EVERY ACTIVE TASK TYPE IS ALLOWED.
--
-- It does not mean none are. Every project in the system today has no
-- rows, because the table did not exist until this file ran; if an empty
-- set meant "no task type may be raised", this migration would stop
-- ticket creation on every project the moment it was applied. There is
-- no backfill that could avoid that either — writing eleven rows per
-- project to preserve today's behaviour would turn "this project has
-- never been configured" into "this project has been configured to allow
-- exactly the eleven types that existed on 14 Aug 2026", and the twelfth
-- task type an Admin adds next month would then be silently barred
-- everywhere.
--
-- The consequence an administrator can see is that unticking every box
-- clears the restriction rather than forbidding everything, and the
-- screen has to say so in words. A project that permitted no task type
-- could raise no ticket, so that state does not exist and the request
-- that would express it does something else instead.
--
-- ON DELETE CASCADE on `project_id`, and NOT on `task_type_id`. Deleting
-- a project is not a thing the product does — S-10 has no delete, and a
-- project that issued ticket IDs cannot be removed without orphaning
-- them — so the cascade is a tidy-up for a path that should not be
-- reachable rather than a behaviour anything relies on. The task type
-- side is deliberately restrictive: an allow-list row referencing a task
-- type is a reason that task type must not vanish, and B-020's master
-- deactivates rather than deletes for exactly this class of reason.
--
-- ---------------------------------------------------------------------
-- 2. `projects.mandatory_fields` — JSON, not a third join table
--
-- The B-011 test applies: is this a set somebody queries *across* rows?
-- Nothing asks "which projects require a module"; the create form asks
-- one project what it requires, alongside everything else it already
-- reads about that project. So a `project_mandatory_fields` table would
-- be a second join table and a second read to serve a checkbox list of
-- ten values, and `users.skills` (V20260811_1520) made the same call for
-- the same reason with the same JSON_SCHEMA_VALID guard.
--
-- Allowed task types stay a join table and this does not, which is not
-- an inconsistency: task type ids are foreign keys into a master an
-- Admin edits, and referential integrity is the whole argument for
-- normalising. Field codes are a vocabulary in the application, with
-- nothing to point at.
--
-- THE CHECK CONSTRAINS SHAPE, NOT VOCABULARY, AND THAT IS DELIBERATE.
--
-- `ck_projects_status` (B-016) pinned an enum in the schema because
-- blueprint S-10 fixes those three values and a documented vocabulary
-- drifts where a constrained one cannot. This list is different: it is
-- exactly the optional fields of Stream C's create form, so pinning the
-- values here would mean C cannot add a form field without a migration
-- in Stream B's directory — a cross-stream block, in exchange for a
-- guard the service already applies against a Java enum on every write.
-- The pattern still refuses free text, an empty string and a duplicate,
-- so a later normalisation would have something regular to read.
--
-- NULL and [] both mean "nothing beyond the always-required fields".
-- NULL is what every existing row gets and [] is what the screen sends
-- when the last box is unticked; making the column NOT NULL DEFAULT
-- '[]' would avoid the pair, and would also rewrite every row of
-- `projects` to say something no administrator has said yet. The service
-- reads them identically and there is a test that says so.
-- =====================================================================

CREATE TABLE project_task_types (
  project_id    BIGINT    NOT NULL,
  task_type_id  INT       NOT NULL,
  added_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (project_id, task_type_id),
  KEY ix_project_task_types_task_type (task_type_id),
  CONSTRAINT fk_project_task_types_project
    FOREIGN KEY (project_id)   REFERENCES projects (id)   ON DELETE CASCADE,
  CONSTRAINT fk_project_task_types_task_type
    FOREIGN KEY (task_type_id) REFERENCES task_types (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
  COMMENT = 'S-10 Settings tab allow-list. NO ROWS FOR A PROJECT = every active task type allowed, not none.';

ALTER TABLE projects
  ADD COLUMN mandatory_fields JSON NULL
    COMMENT 'S-10 Settings tab. Ticket fields this project requires beyond the always-required ones. NULL and [] both mean none.'
    AFTER auto_assign_rule,

  ADD CONSTRAINT ck_projects_mandatory_fields
    CHECK (mandatory_fields IS NULL
           OR JSON_SCHEMA_VALID(
             '{"type":"array",
               "items":{"type":"string","pattern":"^[A-Z][A-Z_]{1,39}$"},
               "uniqueItems":true,
               "maxItems":20}',
             mandatory_fields));
