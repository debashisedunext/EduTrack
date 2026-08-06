-- =====================================================================
-- A-008 · Immutability triggers
--
-- This file is where the append-only guarantee stops being a convention
-- and becomes something the database enforces.
--
-- Source: PLAN.md §3.5 (fully immutable tables) and §3.6 (the exited_at
--         sealing exception — a deliberate tightening of blueprint §4A.5)
--
-- MySQL needs TWO triggers where PostgreSQL allowed BEFORE UPDATE OR
-- DELETE in one, and has no shared trigger function, so each body is
-- repeated. That is why this file is long and repetitive; it is not
-- refactorable.
--
-- These triggers are LAYER 4 of 4. Alone they are not sufficient:
--   layer 1  no update()/delete() method exists on the service   (A-040)
--   layer 2  no PUT/PATCH/DELETE route is registered              (A-040)
--   layer 3  edutrack_app holds INSERT, SELECT only here          (A-010)
--   layer 4  these triggers                                       (A-008)
--
-- Three limits of trigger protection, and how each is closed (PLAN §3.5):
--   1. Triggers do not fire on TRUNCATE  -> app user has no DROP privilege
--   2. Triggers do not survive DROP/recreate -> app user has no DDL
--   3. A SUPER user can drop them        -> the hash chain is the backstop;
--      tampering that bypasses a trigger still breaks the chain and the
--      nightly verifier (A-044) reports it
--
-- A-013 proves all of this with negative tests that attempt UPDATE and
-- DELETE and assert the exception. Until A-013 exists, this file is
-- unverified.
-- =====================================================================

DELIMITER $$

-- ---------------------------------------------------------------------
-- ticket_history — fully immutable. No exceptions, for anyone.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_hist_no_update BEFORE UPDATE ON ticket_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be updated. A correction is a new row with is_correction = 1.';
END$$

CREATE TRIGGER trg_hist_no_delete BEFORE DELETE ON ticket_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be deleted.';
END$$


-- ---------------------------------------------------------------------
-- ticket_effort_logs — fully immutable.
--
-- An over-logged 8 hours is reversed by inserting -8 with
-- is_correction = 1 and corrects_entry_id pointing at the original,
-- exactly as an accounting reversal (A-043). The original row stays.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_effort_no_update BEFORE UPDATE ON ticket_effort_logs
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_effort_logs rows cannot be updated. Log a compensating entry instead.';
END$$

CREATE TRIGGER trg_effort_no_delete BEFORE DELETE ON ticket_effort_logs
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_effort_logs rows cannot be deleted.';
END$$


-- ---------------------------------------------------------------------
-- ticket_stage_transitions — immutable except for ONE sealing update.
--
-- When a stage closes: exited_at goes NULL -> timestamp, duration_mins is
-- computed, is_current drops to 0. Nothing else may ever change.
--
-- The blueprint (§4A.5) creates only a BEFORE DELETE trigger here and
-- says "a CHECK/rule prevents changing a non-NULL exited_at" without
-- specifying it — which would leave every other column updatable by a
-- stray query or a Hibernate dirty-check. Since stage duration is the
-- exact number the whole ribbon exists to report, PLAN.md §3.6 tightens
-- it to what follows.
--
-- <=> is MySQL's NULL-safe equality. Plain = returns NULL when both sides
-- are NULL, and a NULL condition is not TRUE, so the guard would silently
-- pass for every nullable column. This single operator is what makes the
-- check correct.
--
-- NOTE: created_at is included in the immutable set below. PLAN.md §3.6's
-- listing omits it, which would have left the row's own creation
-- timestamp rewritable during a seal. Treating that as an oversight in
-- the plan rather than an intentional allowance — raised for review.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_stage_seal_only BEFORE UPDATE ON ticket_stage_transitions
FOR EACH ROW
BEGIN
  IF OLD.exited_at IS NOT NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Stage transition already sealed; it cannot be modified.';
  END IF;

  IF NOT (NEW.id           <=> OLD.id           AND NEW.ticket_id    <=> OLD.ticket_id
      AND NEW.cycle_no     <=> OLD.cycle_no     AND NEW.iteration_no <=> OLD.iteration_no
      AND NEW.seq_no       <=> OLD.seq_no       AND NEW.from_stage   <=> OLD.from_stage
      AND NEW.to_stage     <=> OLD.to_stage     AND NEW.from_user_id <=> OLD.from_user_id
      AND NEW.to_user_id   <=> OLD.to_user_id   AND NEW.action_code  <=> OLD.action_code
      AND NEW.handoff_note <=> OLD.handoff_note AND NEW.reason       <=> OLD.reason
      AND NEW.entered_at   <=> OLD.entered_at   AND NEW.prev_hash    <=> OLD.prev_hash
      AND NEW.row_hash     <=> OLD.row_hash     AND NEW.created_at   <=> OLD.created_at) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Only exited_at, duration_mins and is_current may be set on seal.';
  END IF;
END$$

CREATE TRIGGER trg_stage_no_delete BEFORE DELETE ON ticket_stage_transitions
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_stage_transitions rows cannot be deleted. The ribbon can never be rewritten.';
END$$

DELIMITER ;
