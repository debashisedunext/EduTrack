-- =====================================================================
-- A-105 · Client Onboarding — the step clock
--
-- Tables: ob_step_clock_events   (+ its immutability triggers)
--
-- Source:  docs/Onboarding-Module-Plan.md §5.7 (clock states), §5.10
--          (utilized hours derived from this table at read time), §14
--          (TAT disputes), §1.1 item 1 (the waiting-on-client clock)
--
-- EVENTS, NOT INTERVALS, AND THE NAME IS THE DESIGN.
--
-- The obvious alternative is an interval row — `started_at` plus a
-- nullable `ended_at` sealed on close — which is exactly what
-- `ticket_stage_transitions` does, and it would make the roll-up query a
-- single SUM. It is rejected here for one reason: sealing is a mutation,
-- and a mutation needs the seal-only trigger of PLAN.md §3.6, which is
-- forty lines of NULL-safe column comparison that has to be got right and
-- kept right. Append-only events need no such thing — there is no update
-- path to guard, so there is no update path to get wrong.
--
-- The cost is that "utilized so far" is a fold over paired events rather
-- than a SUM. That is C-120's query to write once, against a table that
-- cannot lie to it.
--
-- ────────────────────────────────────────────────────────────────────
-- DELIBERATE TIGHTENING, FLAGGED RATHER THAN SILENT
--
-- Plan §4 lists five instance tables and annotates only two as append-only:
-- `ob_step_communications` and `ob_step_history` (A-106). It does not
-- annotate this one. **This file protects it anyway**, with the same
-- BEFORE UPDATE / BEFORE DELETE triggers, and that is a deviation from the
-- plan's letter which CLAUDE.md requires be stated rather than assumed.
--
-- The argument: this table *is* the TAT record. §5.10 makes utilized hours
-- derived from it at read time precisely so no stored aggregate can
-- disagree with its parts — but that only moves the trust, it does not
-- create it. If a row here can be edited, then every TAT figure, every
-- breach, every escalation and every "waiting on client" attribution is
-- editable too, and §14 names TAT disputes as a live risk whose
-- mitigation is this clock. An editable clock is not a mitigation.
--
-- It is also the direction the asymmetry favours. Dropping a trigger later
-- is one migration. Retrofitting immutability onto a table whose rows have
-- already been edited is not a migration at all — it is a data forensics
-- exercise with no correct answer.
--
-- This is the same reasoning PLAN.md §3.6 used to tighten blueprint §4A.5,
-- and it is raised for review rather than presented as settled.
--
-- NOT HASH-CHAINED, and that is not an oversight. The chain in A-106 costs
-- a pessimistic lock on the parent row per append (PLAN.md §3.7). Clock
-- events are written by the scanner across many steps at once; the chain's
-- value is tamper-evidence for the narrative record, which is
-- `ob_step_history`'s job — and every clock event worth disputing writes a
-- history row beside it, inside the chain.
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_step_clock_events — APPEND ONLY (see the header for the tightening)
--
-- One row per transition of the clock. The clock's state at any moment is
-- the fold of the events before it; there is no current-state column here,
-- because a denormalised state and its own event log are two things that
-- can disagree.
--
-- §5.7 — WAITING_ON_CLIENT pauses the clock; internal BLOCKED does NOT.
-- That is the whole reason `pause_reason` exists as its own column rather
-- than being inferred from the step's status: a step can be BLOCKED and
-- still burning TAT, which is correct and is frequently the thing being
-- argued about. Only a PAUSED event stops the clock, and a PAUSED event
-- always says why.
--
-- `attributed_to` carries the answer to the argument. §1.1 item 1: the
-- wait is attributed to the client, and §14's mitigation for TAT disputes
-- is exactly this attribution. Recording it per event rather than deriving
-- it from `pause_reason` at report time means the attribution is fixed at
-- the moment it was made, by whoever made it, and cannot be re-derived
-- differently six months later by a changed mapping.
--
-- `occurred_at` is separate from `created_at`. They are the same instant
-- for a live transition and are NOT for a scanner catching up after an
-- outage, or for a correction. The clock reads `occurred_at`; the audit
-- reads both, and a gap between them is itself informative.
-- ---------------------------------------------------------------------
CREATE TABLE ob_step_clock_events (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  step_id        BIGINT       NOT NULL,
  -- Denormalised from the step. C-120 rolls utilized hours up per journey
  -- for one client's page; without this that read joins every event row
  -- back through the step to find its journey.
  journey_id     BIGINT       NOT NULL,
  event_type     VARCHAR(20)  NOT NULL,   -- STARTED|PAUSED|RESUMED|STOPPED
  -- Only on PAUSED, and mandatory there. WAITING_ON_CLIENT is the only
  -- reason that stops the clock today (§5.7); the column is wider than
  -- that so a second pausing reason does not need a migration to name.
  pause_reason   VARCHAR(30)  NULL,       -- WAITING_ON_CLIENT|…
  -- Who the elapsed time belongs to. §1.1 item 1, §14.
  attributed_to  VARCHAR(10)  NOT NULL DEFAULT 'INTERNAL',  -- INTERNAL|CLIENT
  -- When the clock actually moved. See the note above on why this is not
  -- `created_at`.
  occurred_at    DATETIME(6)  NOT NULL,
  actor_id       BIGINT       NULL,       -- NULL = SYSTEM (the scanner)
  actor_type     VARCHAR(10)  NOT NULL DEFAULT 'USER',       -- USER|SYSTEM
  note           VARCHAR(500) NULL,
  created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The fold: one step's events in order. Also the index C-120's roll-up
  -- walks, which is why it leads with step rather than journey.
  KEY ix_ob_clock_step (step_id, occurred_at, id),
  KEY ix_ob_clock_journey (journey_id, occurred_at),
  KEY ix_ob_clock_actor (actor_id),
  CONSTRAINT fk_ob_clock_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_clock_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_clock_actor
    FOREIGN KEY (actor_id) REFERENCES users (id),
  CONSTRAINT ck_ob_clock_event_type
    CHECK (event_type IN ('STARTED', 'PAUSED', 'RESUMED', 'STOPPED')),
  CONSTRAINT ck_ob_clock_attributed_to
    CHECK (attributed_to IN ('INTERNAL', 'CLIENT')),
  CONSTRAINT ck_ob_clock_actor_type
    CHECK (actor_type IN ('USER', 'SYSTEM')),
  -- A pause always says why. A pause with no reason is a gap in the TAT
  -- record that nobody can defend in the dispute this table exists for.
  CONSTRAINT ck_ob_clock_pause_reason
    CHECK (event_type <> 'PAUSED' OR pause_reason IS NOT NULL),
  -- And only a pause carries one, so the column cannot come to mean two
  -- things depending on which row you read.
  CONSTRAINT ck_ob_clock_reason_only_on_pause
    CHECK (event_type = 'PAUSED' OR pause_reason IS NULL)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The triggers. Layer 4 of the same four layers A-008 describes:
--   layer 1  no update()/delete() on the service        (Stream C)
--   layer 2  no PUT/PATCH/DELETE route registered       (Stream C)
--   layer 3  edutrack_app holds INSERT, SELECT here     (A-109/A-010)
--   layer 4  these
--
--
-- MESSAGE_TEXT IS CAPPED AT 128 CHARACTERS, and going over does not
-- truncate — MySQL replaces the whole SIGNAL with ERROR 1648 "Data too
-- long for condition item 'MESSAGE_TEXT'". The write is still refused, so
-- the guarantee holds, but the caller is told nothing about immutability
-- and the SQLSTATE is 22001 rather than the 45000 declared below. Found by
-- exercising these triggers rather than by reading them: three of the six
-- messages here were 139-162 characters on the first draft. A-008's are
-- all under 105, which is why it never surfaced there. Keep them short.
-- MySQL needs two triggers where PostgreSQL allowed BEFORE UPDATE OR
-- DELETE in one, and has no shared trigger function, so the bodies repeat.
-- That is why this is verbose; it is not refactorable.
-- ---------------------------------------------------------------------
DELIMITER $$

CREATE TRIGGER trg_ob_clock_no_update BEFORE UPDATE ON ob_step_clock_events
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_clock_events cannot be updated. Correct the clock by appending a compensating event.';
END$$

CREATE TRIGGER trg_ob_clock_no_delete BEFORE DELETE ON ob_step_clock_events
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_clock_events rows cannot be deleted.';
END$$

DELIMITER ;
