-- =====================================================================
-- A-104 · Client Onboarding — journey instances
--
-- Tables: ob_journeys, ob_journey_steps, ob_journey_step_items
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model), §5.2 (LOCKED
--          instantiation), §5.3 (the gate), §5.5 (service-level hold),
--          §5.6 (step activation by dependency), §5.8 (the task-list gate)
--
-- SCOPE — deliberately three tables. `ob_step_clock_events` is A-105 and
-- the append-only pair (`ob_step_communications`, `ob_step_history`) is
-- A-106; both are listed beside these in plan §4 and both are somebody's
-- own task with their own review. This file creates what a journey *is*,
-- not what happens to it.
--
-- THESE ROWS ARE A SNAPSHOT, WHICH IS THE WHOLE POINT.
--
-- `name`, `tat_days`, `requires_signoff` and the task-list labels are
-- copied from the template at instantiation rather than read through
-- `template_step_id` at display time. That looks like denormalisation and
-- is the opposite: plan §1.1 item 2 makes template snapshotting an
-- architectural requirement, because an admin editing a template must not
-- mutate a journey already running. The FK back to the template row is
-- kept for provenance — "which version did this come from" — never as the
-- source of what the step says today.
--
-- WHO IS WAITING ON THIS FILE (docs/DEPENDENCIES.md):
--     A-105 clock events · A-106 the append-only pair · A-107 sign-off and
--     outbox · A-108 dashboard summary · B-101 fixtures · C-103
--     instantiation · C-104 step lifecycle · C-118 the gate service ·
--     C-119 step dependency graph · C-120 journey TAT roll-up
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_journeys — one per purchased product, per client.
--
-- TWO INDEPENDENT HOLDS, AND MODELLING THEM AS ONE FIELD IS THE MISTAKE
-- THIS TABLE IS MOST LIKELY TO INVITE.
--
--   gate_status         the client's PREREQUISITES (§5.3). LOCKED at
--                       boarding — steps visible, owners resolved, TATs
--                       shown, clocks dead, scanner ignoring it — and it
--                       flips OPEN for *every* journey at once when the
--                       prerequisite gate clears.
--
--   held_by_journey_id  this journey's SERVICE-LEVEL DEPENDENCY (§5.5).
--                       Points at another journey *of the same client*
--                       that must complete first. Cleared independently,
--                       per journey, when that one finishes.
--
-- A journey can be past the gate and still held; it can also be held by
-- nothing and still locked. Both must be clear before a step activates.
-- One flag cannot express that, and the failure would be silent — a
-- journey starting the moment prerequisites clear, ignoring the
-- dependency it was supposed to wait for.
--
-- If the client never bought the dependency's product the dependency is
-- vacuous and this column is simply NULL (§5.5). That is resolved at
-- instantiation, not by a join at read time.
--
-- `uq_ob_journeys_client_product` IS "one per client per product AMONG
-- NON-ARCHIVED" (plan §4), the third use of the generated-column
-- partial-index idiom after `ob_client_contacts.is_primary_key` (A-101)
-- and `ob_journey_templates.active_key` (A-103). `live_key` is 1 while
-- `archived_at` is NULL; the unique index ignores NULLs, so a client may
-- accumulate archived journeys for a product they re-onboarded onto while
-- never holding two live ones.
--
-- THE COMPOSITE FK TO THE PURCHASE IS WHY THERE IS NO `application_id`.
--
-- `ob_client_applications` is already UNIQUE (ob_client_id, product_id)
-- (A-101), so (ob_client_id, product_id) resolves to exactly one purchase
-- and can be a foreign key straight to it. Carrying a separate
-- `application_id` beside these two would be a third column that can
-- disagree with the other two, and nothing would notice which was right.
-- A journey therefore cannot exist for a product the client did not buy —
-- enforced, not asserted.
--
-- `template_id` PINS THE VERSION. It is NOT NULL and never changes: the
-- journey renders from its own snapshot, and this says which template
-- version produced it. RESTRICT on delete follows from that — a template
-- version with journeys behind it is exactly the row A-103 said would be
-- retired rather than deleted.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journeys (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  ob_client_id        BIGINT       NOT NULL,
  product_id          BIGINT       NOT NULL,
  -- The pinned template version. Provenance, never the display source.
  template_id         BIGINT       NOT NULL,
  -- The prerequisite gate (§5.3). Clears for every journey at once.
  gate_status         VARCHAR(10)  NOT NULL DEFAULT 'LOCKED',   -- LOCKED|OPEN
  gate_opened_at      DATETIME(6)  NULL,
  gate_opened_by      BIGINT       NULL,
  -- The service-level dependency (§5.5). Cleared per journey.
  held_by_journey_id  BIGINT       NULL,
  released_at         DATETIME(6)  NULL,
  started_at          DATETIME(6)  NULL,
  completed_at        DATETIME(6)  NULL,
  archived_at         DATETIME(6)  NULL,
  -- 1 while live, NULL once archived. See the note above.
  live_key            TINYINT(1)
      GENERATED ALWAYS AS (IF(archived_at IS NULL, 1, NULL)) STORED,
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_journeys_client_product (ob_client_id, product_id, live_key),
  KEY ix_ob_journeys_client (ob_client_id, id),
  KEY ix_ob_journeys_gate (gate_status, id),
  KEY ix_ob_journeys_held_by (held_by_journey_id),
  KEY ix_ob_journeys_template (template_id),
  KEY ix_ob_journeys_product (product_id),
  -- Straight to the purchase. A journey for an unbought product is
  -- unrepresentable rather than merely discouraged.
  CONSTRAINT fk_ob_journeys_application
    FOREIGN KEY (ob_client_id, product_id)
      REFERENCES ob_client_applications (ob_client_id, product_id),
  CONSTRAINT fk_ob_journeys_template
    FOREIGN KEY (template_id) REFERENCES ob_journey_templates (id),
  -- Another journey of the same client. Same-client-ness is the service's
  -- to enforce: a composite key would need (ob_client_id, id) on both
  -- sides, and the dependency is resolved once at instantiation from the
  -- template graph rather than being re-pointed later.
  CONSTRAINT fk_ob_journeys_held_by
    FOREIGN KEY (held_by_journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_journeys_gate_opened_by
    FOREIGN KEY (gate_opened_by) REFERENCES users (id),
  CONSTRAINT ck_ob_journeys_gate_status
    CHECK (gate_status IN ('LOCKED', 'OPEN')),
  -- A gate that is OPEN has a moment it opened at. Cheap, and it is the
  -- timestamp every "time to gate" report will read.
  CONSTRAINT ck_ob_journeys_gate_opened_at
    CHECK (gate_status = 'LOCKED' OR gate_opened_at IS NOT NULL)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_journey_steps — a Service on a running journey.
--
-- Snapshotted from `ob_journey_template_steps` at instantiation. See the
-- header: `name`, `tat_days` and `requires_signoff` are copies, and
-- `template_step_id` is provenance.
--
-- `depends_on_step_id` REPEATS A-103's COMPOSITE-KEY TRICK, scoped to the
-- journey instead of the template: `uq_ob_journey_steps_scope
-- (journey_id, id)` gives the foreign key a target, so a step can only
-- depend on a step of the SAME journey. Without it, a snapshot bug could
-- point one client's step at another client's — which would leak the
-- existence of another client's journey through a blocked-by message, and
-- would not be caught by any test that only ever builds one journey.
--
-- NULL still means **parallel** (§5.6), not "first".
--
-- `status` — PENDING covers both "gate still locked" and "dependency not
-- met"; the difference is visible in `depends_on_step_id` and in the
-- journey's two holds, and a step does not need a fourth waiting state of
-- its own.
--
-- BLOCKED and WAITING_ON_CLIENT are separate because only one stops the
-- clock (§5.7). A-105's clock events are what actually pause; this column
-- says why. `blocked_reason_code` is mandatory for BLOCKED — plan §1.1
-- item 5, "blocked-with-reason", which is what powers "where is it
-- stuck". Enforced below rather than left to the service, because a block
-- with no reason is invisible in exactly the report that exists to find it.
--
-- `due_at` is calendar-aware and computed by the service from `tat_days`
-- through the working calendar (CLAUDE.md: a Friday-18:00 start with a
-- 4-hour SLA must not breach on Saturday morning). Stored rather than
-- derived because the scanner sweeps it and the working calendar can
-- change underneath a running step; the stored value is what was promised.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_steps (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  journey_id            BIGINT       NOT NULL,
  -- Provenance only. What the step says today is in this row.
  template_step_id      BIGINT       NULL,
  sequence              INT          NOT NULL,
  name                  VARCHAR(200) NOT NULL,
  description           TEXT         NULL,
  tat_days              INT          NOT NULL DEFAULT 1,
  owner_user_id         BIGINT       NULL,
  backup_owner_user_id  BIGINT       NULL,
  requires_signoff      TINYINT(1)   NOT NULL DEFAULT 0,
  depends_on_step_id    BIGINT       NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                        -- PENDING|IN_PROGRESS|BLOCKED|WAITING_ON_CLIENT|DONE|SKIPPED
  blocked_reason_code   VARCHAR(40)  NULL,
  blocked_note          VARCHAR(500) NULL,
  -- Mandatory reason when a step is skipped (§5, admin override).
  skip_reason           VARCHAR(500) NULL,
  skipped_by            BIGINT       NULL,
  started_at            DATETIME(6)  NULL,
  finished_at           DATETIME(6)  NULL,
  -- Working-calendar aware, computed by the service from tat_days.
  due_at                DATETIME(6)  NULL,
  created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                         ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_journey_steps_seq (journey_id, sequence),
  -- The composite FK's target, exactly as A-103's `_scope` key.
  UNIQUE KEY uq_ob_journey_steps_scope (journey_id, id),
  KEY ix_ob_journey_steps_owner (owner_user_id, status),
  KEY ix_ob_journey_steps_template_step (template_step_id),
  KEY ix_ob_journey_steps_skipped_by (skipped_by),
  -- The scanner's sweep: open steps with a due date, oldest first.
  KEY ix_ob_journey_steps_due (status, due_at),
  -- RESTRICT, following A-103: cascading a journey delete into steps
  -- collides with the self-FK below the moment any step depends on
  -- another, and would fail naming the wrong table. Journeys are archived
  -- (`archived_at`), not deleted.
  CONSTRAINT fk_ob_journey_steps_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  -- Same journey only. See the note above on why this matters across
  -- clients rather than merely being tidy.
  CONSTRAINT fk_ob_journey_steps_depends_on
    FOREIGN KEY (journey_id, depends_on_step_id)
      REFERENCES ob_journey_steps (journey_id, id),
  CONSTRAINT fk_ob_journey_steps_template_step
    FOREIGN KEY (template_step_id) REFERENCES ob_journey_template_steps (id),
  CONSTRAINT fk_ob_journey_steps_owner
    FOREIGN KEY (owner_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_journey_steps_backup_owner
    FOREIGN KEY (backup_owner_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_journey_steps_skipped_by
    FOREIGN KEY (skipped_by) REFERENCES users (id),
  CONSTRAINT ck_ob_journey_steps_status
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED',
                      'WAITING_ON_CLIENT', 'DONE', 'SKIPPED')),
  CONSTRAINT ck_ob_journey_steps_tat
    CHECK (tat_days > 0),
  -- "Blocked-with-reason" (§1.1 item 5) held by the database, because a
  -- block with no reason is invisible in the report that exists to find it.
  CONSTRAINT ck_ob_journey_steps_blocked_reason
    CHECK (status <> 'BLOCKED' OR blocked_reason_code IS NOT NULL),
  -- A skip is an override and always carries its reason.
  CONSTRAINT ck_ob_journey_steps_skip_reason
    CHECK (status <> 'SKIPPED' OR skip_reason IS NOT NULL)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_journey_step_items — the Task List on a running step.
--
-- §5.8: a service completes only when every item is answered, and **False
-- requires a remark**.
--
-- The second half is enforced here, in `ck_ob_journey_step_items_remark`,
-- because it is a same-row rule and therefore one of the few things a
-- CHECK can actually hold. `answer` is NULL until somebody answers, 1 for
-- True, 0 for False; 0 with no remark is refused.
--
-- The first half — *every* item answered — is not a same-row rule and
-- stays with the completion gate in the service (C-106), the same split
-- as A-103's "earlier step".
--
-- `label` is snapshotted for the reason the header gives. `template_item_id`
-- is provenance and is nullable, because an admin may add an ad-hoc item
-- to one client's step that no template ever carried.
-- ---------------------------------------------------------------------
CREATE TABLE ob_journey_step_items (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  step_id           BIGINT        NOT NULL,
  template_item_id  BIGINT        NULL,
  sequence          INT           NOT NULL,
  label             VARCHAR(300)  NOT NULL,
  -- NULL = unanswered, 1 = True, 0 = False.
  answer            TINYINT(1)    NULL,
  remark            VARCHAR(500)  NULL,
  answered_by       BIGINT        NULL,
  answered_at       DATETIME(6)   NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_journey_step_items_seq (step_id, sequence),
  KEY ix_ob_journey_step_items_template (template_item_id),
  KEY ix_ob_journey_step_items_answered_by (answered_by),
  CONSTRAINT fk_ob_journey_step_items_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id) ON DELETE CASCADE,
  CONSTRAINT fk_ob_journey_step_items_template_item
    FOREIGN KEY (template_item_id)
      REFERENCES ob_journey_template_step_items (id),
  CONSTRAINT fk_ob_journey_step_items_answered_by
    FOREIGN KEY (answered_by) REFERENCES users (id),
  CONSTRAINT ck_ob_journey_step_items_answer
    CHECK (answer IS NULL OR answer IN (0, 1)),
  -- §5.8's second half, and the half a CHECK can hold.
  CONSTRAINT ck_ob_journey_step_items_remark
    CHECK (answer IS NULL OR answer = 1
           OR (remark IS NOT NULL AND remark <> ''))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
