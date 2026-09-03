-- =====================================================================
-- A-103 · Client Onboarding — journey templates (the Module Service page)
--
-- Tables: ob_journey_templates, ob_journey_template_steps,
--         ob_journey_template_step_items, ob_journey_template_step_docs
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model), §5.1/§5.5/§5.6
--          (versioning, service dependency, step dependency), §9 OB-07
--          docs/prototype/onboarding.html — TPL_STEPS is the field list
--
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative).
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- VOCABULARY — the v1.2 renames (plan, line 11) are the product's words for
-- the objects below, and the two do not line up the way a reader expects:
--
--     Module Service  = one row of `ob_journey_templates`
--     Service         = one row of `ob_journey_template_steps`
--     Task List       = `ob_journey_template_step_items` (was "sub-categories")
--     Implementor     = the step's owner
--
-- Column names follow plan §4, which kept the original words. **TATs are
-- working DAYS, not hours** — that is a v1.2 change of unit, not only of
-- label, and `tat_days` is named so nobody re-reads it as hours.
--
-- WHO IS WAITING ON THIS FILE (docs/DEPENDENCIES.md):
--     A-104 journey instances · A-128 service dependency & escalation
--     C-101 template domain and versioning · C-102 OB-07 · C-119 step
--     dependency graph · C-120 journey TAT roll-up · C-123 service-level
--     dependency engine
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_journey_templates — a Module Service, and one *version* of it.
--
-- ONE ROW IS ONE VERSION, NOT ONE SERVICE. Plan §5.1: editing publishes a
-- new version and in-flight journeys keep theirs, so a product accumulates
-- rows over time and `ob_journeys` pins the row it was instantiated from
-- (A-104). That pinning is the whole mechanism by which an admin's edit
-- cannot corrupt a journey already running.
--
-- `uq_ob_journey_templates_active` IS THE "one active version per product"
-- RULE, AND IT NEEDS AN IDIOM.
--
-- MySQL 8.4 has no partial unique index — PostgreSQL would write
-- `CREATE UNIQUE INDEX … WHERE is_active`. `active_key` is a generated
-- column holding 1 when the row is active and NULL otherwise; a unique
-- index ignores NULLs, so any number of retired versions coexist while a
-- second *active* one for the same product is refused.
--
-- **Second use of this pattern** — `ob_client_contacts.is_primary_key`
-- (A-101, row 77) is the first, for one primary SPOC per client. Named as a
-- pattern here rather than left to be rediscovered a third time; PLAN.md
-- §3.1 does not cover this translation because nothing in phase 1 needed it.
--
-- `sequence` IS DELIBERATELY NOT UNIQUE, which is worth stating because
-- `workflow_stages.seq` next door is. It orders *services* — plan §5.5, the
-- OB-07 ↑/↓ control, driving the order a client's journeys instantiate and
-- display. Every version of one product's service shares that position, so
-- a unique index over a table holding all history would refuse the second
-- version of anything. Uniqueness that matters is among *active* rows only,
-- and that is one row per product already.
--
-- `depends_on_template_id` IS **NOT** CYCLE-FREE BY CONSTRUCTION, unlike
-- the step dependency below, and the plan's wording invites the confusion:
-- "cycle-free" appears next to both. Here it is cross-product by design
-- (§5.5's example is Biometric Device Rollout after the ERP service) and
-- unbounded in depth, so detecting a cycle is a transitive graph walk. No
-- CHECK and no trigger can do that — a CHECK cannot see another row.
--
-- **So this constraint is the service layer's, and only the service
-- layer's.** Plan §9 OB-07 says the picker "excludes anything that
-- transitively depends on it", which is the enforcement point; C-123 owns
-- it. The self-FK below buys referential integrity and nothing more. A
-- reader who assumes the database is holding this line will not write the
-- check, and the first cycle will be found by a journey that never starts.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_templates (
  id                      BIGINT        NOT NULL AUTO_INCREMENT,
  product_id              BIGINT        NOT NULL,
  name                    VARCHAR(160)  NOT NULL,
  version                 INT           NOT NULL DEFAULT 1,
  is_active               TINYINT(1)    NOT NULL DEFAULT 0,
  -- 1 when active, NULL otherwise. See the note above: this is how one
  -- active version per product is enforced without a partial index.
  active_key              TINYINT(1)
      GENERATED ALWAYS AS (IF(is_active = 1, 1, NULL)) STORED,
  -- Service order (§5.5). Not unique — every version shares its service's
  -- position, and only the active rows are one-per-product.
  sequence                INT           NOT NULL DEFAULT 0,
  -- Cross-product, cycle-freedom enforced in the service layer only.
  depends_on_template_id  BIGINT        NULL,
  published_by            BIGINT        NULL,
  published_at            DATETIME(6)   NULL,
  created_by              BIGINT        NULL,
  created_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                            ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_journey_templates_active (product_id, active_key),
  UNIQUE KEY uq_ob_journey_templates_version (product_id, version),
  KEY ix_ob_journey_templates_sequence (sequence, id),
  KEY ix_ob_journey_templates_depends_on (depends_on_template_id),
  CONSTRAINT fk_ob_journey_templates_product
    FOREIGN KEY (product_id) REFERENCES ob_products (id),
  -- RESTRICT, not CASCADE: a service other services depend on cannot be
  -- deleted out from under them. The designer re-points first.
  CONSTRAINT fk_ob_journey_templates_depends_on
    FOREIGN KEY (depends_on_template_id) REFERENCES ob_journey_templates (id),
  CONSTRAINT fk_ob_journey_templates_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT fk_ob_journey_templates_published_by
    FOREIGN KEY (published_by) REFERENCES users (id),
  CONSTRAINT ck_ob_journey_templates_version
    CHECK (version > 0)
  -- NO self-reference CHECK, and not for want of trying: MySQL 8.4 rejects
  -- `CHECK (depends_on_template_id <> id)` with ERROR 3818, "check
  -- constraint cannot refer to an auto-increment column". So even the
  -- one-hop cycle — a service declaring itself its own dependency — is the
  -- service layer's to refuse, alongside the transitive ones. C-123 owns
  -- the whole check rather than the interesting half of it, which is
  -- simpler to reason about than a split responsibility would have been.
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_journey_template_steps — a Service within a Module Service.
--
-- `depends_on_step_id` IS CYCLE-FREE BY CONSTRUCTION, AND HALF OF THAT IS
-- ENFORCED HERE.
--
-- Plan §4: "each step declares at most one dependency, constrained to an
-- **earlier step in the same template**". Two clauses, and they are not
-- equally enforceable:
--
--   same template — enforced. `uq_ob_journey_template_steps_scope` exists
--                   solely to give the composite foreign key below a target,
--                   so (template_id, depends_on_step_id) can only resolve to
--                   a row with the same template_id. A step in template 7
--                   pointing at a step in template 9 is refused by the
--                   database, not by a service that might forget.
--
--   earlier       — NOT enforced here. It compares `sequence` against
--                   another row's, which a CHECK cannot do, and a trigger
--                   would have to re-validate the whole set on every
--                   reorder. C-119 owns it, and the designer re-validates
--                   on reorder and delete (§9 OB-07).
--
-- Together those two give cycle-freedom: a dependency graph whose every
-- edge points backwards inside one template cannot contain a cycle. The
-- half the database holds is the half that would otherwise let an edge
-- escape the template entirely, which is the failure a service check is
-- least likely to catch.
--
-- NULL means **parallel**, not "first". Plan §5.6: a step with no
-- dependency activates at gate-open and runs alongside its siblings, so a
-- journey can hold several in-progress steps at once and completes only
-- when all of them land. The ribbon marks these `∥`.
--
-- `tat_days` — WORKING DAYS (v1.2). All duration maths goes through the
-- working calendar (CLAUDE.md), so this is a budget in working days that
-- `ob_step_clock_events` (A-105) spends in working hours.
--
-- `backup_owner_user_id` is the architect's leave-coverage addition
-- (plan §1.1 item 4). `owner_role` carries the *role* that will own the
-- step when no specific person is named — C-109's ribbon already reads
-- both, showing the role for a step nobody has picked up yet.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_template_steps (
  id                     BIGINT        NOT NULL AUTO_INCREMENT,
  template_id            BIGINT        NOT NULL,
  sequence               INT           NOT NULL,
  name                   VARCHAR(200)  NOT NULL,
  description            TEXT          NULL,
  -- Working days. Not hours — the v1.2 rename changed the unit.
  tat_days               INT           NOT NULL DEFAULT 1,
  owner_user_id          BIGINT        NULL,
  owner_role             VARCHAR(40)   NULL,
  backup_owner_user_id   BIGINT        NULL,
  -- Client sign-off required before this step may complete (§8).
  requires_signoff       TINYINT(1)    NOT NULL DEFAULT 0,
  -- NULL = runs in parallel. See the note above.
  depends_on_step_id     BIGINT        NULL,
  created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_journey_template_steps_seq (template_id, sequence),
  -- Exists only as the composite FK's target. Redundant as a uniqueness
  -- claim — `id` is already the primary key — and load-bearing as an index:
  -- without it the foreign key below cannot be declared at all.
  UNIQUE KEY uq_ob_journey_template_steps_scope (template_id, id),
  KEY ix_ob_journey_template_steps_owner (owner_user_id),
  -- RESTRICT, NOT CASCADE, and this was measured rather than assumed.
  --
  -- With CASCADE, deleting a template deletes its steps — which the self-FK
  -- below then refuses, because a step being cascaded away is still the
  -- target of its successor's dependency. Verified on MySQL 8.4: a template
  -- whose steps form a chain fails with ERROR 1451 naming
  -- `fk_ob_journey_template_steps_depends_on`, while one whose steps are all
  -- parallel deletes cleanly.
  --
  -- That is the worst available behaviour: deletion works, *unless* the
  -- admin used the feature the OB-07 page is built around, and the error
  -- when it fails names a constraint on a different table than the one they
  -- asked to delete. RESTRICT makes it fail the same way every time, so the
  -- service deletes steps in reverse dependency order and then the template
  -- — which is what the designer already does on a single step delete
  -- (§9 OB-07 re-validates dependencies on delete and reorder).
  --
  -- ON DELETE SET NULL is not the alternative it looks like: the composite
  -- key below would have to null `template_id` as well, and that column is
  -- NOT NULL, so MySQL refuses the declaration outright.
  --
  -- Worth saying that deletion is the rare path regardless. A published
  -- version is pinned by every journey instantiated from it (A-104), so it
  -- is retired with `is_active = 0` and kept — the same argument
  -- `ob_products` makes one table over. Only an unpublished draft is ever a
  -- genuine delete.
  CONSTRAINT fk_ob_journey_template_steps_template
    FOREIGN KEY (template_id) REFERENCES ob_journey_templates (id),
  -- The composite key: a dependency can only be a step of the SAME
  -- template. RESTRICT on delete, so a step something depends on cannot
  -- vanish — the designer re-points first (§9 OB-07 re-validates on delete).
  CONSTRAINT fk_ob_journey_template_steps_depends_on
    FOREIGN KEY (template_id, depends_on_step_id)
      REFERENCES ob_journey_template_steps (template_id, id),
  CONSTRAINT fk_ob_journey_template_steps_owner
    FOREIGN KEY (owner_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_journey_template_steps_backup_owner
    FOREIGN KEY (backup_owner_user_id) REFERENCES users (id),
  CONSTRAINT ck_ob_journey_template_steps_tat
    CHECK (tat_days > 0)
  -- No self-reference CHECK here either — MySQL 8.4's ERROR 3818, same as
  -- on the template table. Worth knowing what that does and does not leave
  -- open: the composite key above happily resolves `depends_on_step_id =
  -- id` to the step itself, so a self-dependency is referentially valid and
  -- the database will take it. What refuses it is the "earlier step" rule,
  -- which C-119 enforces and which excludes self by definition — a step
  -- cannot precede itself. The database holds the template boundary; the
  -- ordering, and therefore the acyclicity, is the service's.
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_journey_template_step_items — the Task List (was "sub-categories").
--
-- Plan §5.8: a service completes only when every item is answered, and a
-- False answer requires a remark. That gate is the instance's
-- (`ob_journey_step_items`, A-104); this is the versioned definition it is
-- snapshotted from.
--
-- A table rather than a JSON column for the reason plan §9 makes plain:
-- the instance rows carry an answer and a remark each, so the definition
-- has to have stable per-item identity to snapshot against. JSON would
-- make "the third item" the identity, and reordering a template would
-- silently re-point every answered instance.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_template_step_items (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  step_id     BIGINT        NOT NULL,
  sequence    INT           NOT NULL,
  label       VARCHAR(300)  NOT NULL,
  created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_template_step_items_seq (step_id, sequence),
  CONSTRAINT fk_ob_template_step_items_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_template_steps (id)
      ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_journey_template_step_docs — the per-step document checklist.
--
-- The architect's addition 7 (plan §1.1): "A step can't complete with
-- required documents missing." The prototype's `TPL_STEPS[].docs` is this
-- list — "Signed requirement sheet", "Migration source file".
--
-- `is_required` rather than every row being mandatory, because the same
-- checklist carries documents the owner *may* attach. Only the required
-- ones gate completion.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_template_step_docs (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  step_id      BIGINT        NOT NULL,
  sequence     INT           NOT NULL DEFAULT 0,
  label        VARCHAR(300)  NOT NULL,
  is_required  TINYINT(1)    NOT NULL DEFAULT 1,
  created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_ob_template_step_docs_step (step_id, sequence),
  CONSTRAINT fk_ob_template_step_docs_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_template_steps (id)
      ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
