-- =====================================================================
-- A-005 · Flyway baseline 3/5 — workflow
--
-- Tables: workflow_templates, workflow_stages, ticket_stage_transitions
--         + the deferred FK from tickets.workflow_template_id
--
-- Source:  blueprint §4A.5
-- Applied: PLAN.md §3.1 (VARCHAR(20)[] -> JSON), §3.3, §3.6, §3.7
--
-- ticket_stage_transitions IS THE RIBBON. Every segment rendered in §4A.3,
-- every row of the §4A.4 effort grid, and every stage-SLA the Stream D
-- scanner checks reads from this one table. It is append-only with exactly
-- one permitted mutation — sealing — described below.
-- =====================================================================


-- ---------------------------------------------------------------------
-- workflow_templates — §4A.9. Three are seeded by Stream B in B-004:
-- Standard Dev Flow (8 stages), Support Fast-Track (5), Infra Flow (5).
--
-- A template is versioned by copy, never edited in place: a ticket in
-- flight must keep rendering the ribbon it started on, so changing a
-- live template would rewrite history for open tickets. The designer
-- (S-30, Stream B) clones instead. Enforced in the service layer.
-- ---------------------------------------------------------------------
CREATE TABLE workflow_templates (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  name          VARCHAR(80)   NOT NULL,
  description   VARCHAR(255)  NULL,
  is_default    TINYINT(1)    NOT NULL DEFAULT 0,
  is_active     TINYINT(1)    NOT NULL DEFAULT 1,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_workflow_templates_name (name),
  KEY ix_workflow_templates_default (is_default)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- workflow_stages — the ordered segments of a template.
--
-- owner_role is what makes the golden rule of §2 enforceable: only the
-- current stage's owning role (plus PM and Admin) may move a ticket on.
-- A Developer cannot push a ticket into Deployment while it sits with QA.
--
-- can_return_to: PostgreSQL VARCHAR(20)[] -> JSON per PLAN.md §3.1.
-- Holds an array of stage codes, e.g. ["DEV","TRIAGE"]. Validated against
-- the stage-code enum in the service layer, queried with JSON_CONTAINS.
-- ck_can_return_to_is_array is the one thing the database can still assert
-- once the array becomes an opaque JSON document.
--
-- sla_hours is WORKING hours, not wall-clock. Every duration in this
-- system routes through Stream B's working-calendar service (B-021) —
-- weekends, org holidays and resource leave — so a Friday-18:00 handoff
-- with a 4-hour stage SLA does not breach on Saturday morning.
-- ---------------------------------------------------------------------
CREATE TABLE workflow_stages (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  template_id   BIGINT        NOT NULL,
  seq           SMALLINT      NOT NULL,       -- ribbon order, left to right
  stage_code    VARCHAR(20)   NOT NULL,       -- INTAKE|TRIAGE|DEV|QA|DEPLOY|
                                              -- VERIFY|SIGNOFF|CLOSED
  display_name  VARCHAR(50)   NOT NULL,
  owner_role    VARCHAR(20)   NOT NULL,
  sla_hours     DECIMAL(6,2)  NULL,           -- working hours
  is_optional   TINYINT(1)    NOT NULL DEFAULT 0,   -- skippable, with reason
  can_return_to JSON          NULL,           -- ["DEV","TRIAGE"]
  icon          VARCHAR(30)   NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_workflow_stages_seq (template_id, seq),
  UNIQUE KEY uq_workflow_stages_code (template_id, stage_code),
  KEY ix_workflow_stages_owner_role (owner_role),
  CONSTRAINT ck_can_return_to_is_array
    CHECK (can_return_to IS NULL OR JSON_TYPE(can_return_to) = 'ARRAY'),
  CONSTRAINT fk_workflow_stages_template
    FOREIGN KEY (template_id) REFERENCES workflow_templates (id)
      ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_stage_transitions — APPEND ONLY (one sealing exception) · HASH CHAINED
--
-- One row per hop. The ribbon is this table, ordered by seq_no.
--
-- THE TWO INDEPENDENT COUNTERS (§4A.2) — the single most misread concept
-- in the spec, so stated here where the columns live:
--
--   iteration_no  increments when a ticket moves BACKWARDS inside a cycle.
--                 QA fails the build -> back to DEV -> iteration 2.
--   cycle_no      increments when a CLOSED ticket is REOPENED.
--                 Cycle 2 starts a fresh journey with iteration back at 1.
--
-- They are not nested versions of the same idea and neither derives from
-- the other. A ticket can be cycle 2 / iteration 3.
--
-- THE ONE PERMITTED MUTATION — sealing. When a stage closes, exited_at
-- goes NULL -> timestamp, duration_mins is computed and is_current drops
-- to 0. Nothing else on the row may ever change. The blueprint (§4A.5)
-- only blocks DELETE here and leaves every other column updatable by a
-- stray query or an ORM dirty-check; PLAN.md §3.6 tightens that to a
-- BEFORE UPDATE trigger permitting exactly those three columns and only
-- while the row is still open. That trigger is A-008.
--
-- duration_mins is WORKING minutes from the calendar service, not
-- wall-clock — it feeds "idle vs active" in §4A.4, the number that tells
-- a manager a 2-day stage with 2 hours of effort is a queue problem.
--
-- The generated column current_ticket_id, replacing the PostgreSQL
-- partial index ix_stage_current (PLAN.md §3.3), is A-009.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_stage_transitions (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  ticket_id      BIGINT       NOT NULL,
  cycle_no       SMALLINT     NOT NULL,
  iteration_no   SMALLINT     NOT NULL DEFAULT 1,
  seq_no         INT          NOT NULL,   -- 1,2,3… hop order within the cycle
  from_stage     VARCHAR(20)  NULL,       -- NULL on the very first hop
  to_stage       VARCHAR(20)  NOT NULL,
  from_user_id   BIGINT       NULL,
  to_user_id     BIGINT       NULL,
  action_code    VARCHAR(20)  NOT NULL,   -- FORWARD|REWORK|DEPLOY_FAILED|
                                          -- VERIFY_FAILED|SIGNOFF_REJECTED|
                                          -- CLARIFICATION|SKIP|OVERRIDE
  handoff_note   TEXT         NULL,
  reason         TEXT         NULL,       -- mandatory on any backward move,
                                          -- enforced in the service (§4A.6)
  entered_at     DATETIME(6)  NOT NULL,
  exited_at      DATETIME(6)  NULL,       -- NULL = the ticket is here now
  duration_mins  INT          NULL,       -- working minutes, set on seal
  is_current     TINYINT(1)   NOT NULL DEFAULT 1,
  prev_hash      CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL,
  row_hash       CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_stage_transitions (ticket_id, cycle_no, seq_no),
  KEY ix_stage_ticket_id (ticket_id, id),          -- chain walk, §3.7
  KEY ix_stage_rollup (ticket_id, cycle_no, to_stage, iteration_no),
  KEY ix_stage_to_user (to_user_id),
  KEY ix_stage_from_user (from_user_id),
  KEY ix_stage_open (to_stage, exited_at),         -- stage-SLA scan, Stream D
  CONSTRAINT fk_stage_ticket
    FOREIGN KEY (ticket_id)    REFERENCES tickets (id),
  CONSTRAINT fk_stage_from_user
    FOREIGN KEY (from_user_id) REFERENCES users (id),
  CONSTRAINT fk_stage_to_user
    FOREIGN KEY (to_user_id)   REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Deferred foreign key from A-004. tickets.workflow_template_id was
-- declared there because the blueprint adds it by ALTER in §4A.5; the
-- constraint lands now that its target exists.
-- ---------------------------------------------------------------------
ALTER TABLE tickets
  ADD CONSTRAINT fk_tickets_workflow_template
    FOREIGN KEY (workflow_template_id) REFERENCES workflow_templates (id);
