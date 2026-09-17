-- =====================================================================
-- The manager review gate — a verdict per check-list row, and one new
-- task status to hold the task while those verdicts are recorded.
--
-- Tables: ob_journey_steps, ob_journey_step_items,
--         ob_journey_template_steps
--
-- WHAT THIS ADDS, IN ONE SENTENCE
--
--   An implementor marking a task complete no longer closes it. The task
--   lands in PENDING_REVIEW; an OB Manager sets every row to VERIFIED or
--   REJECTED; all VERIFIED closes the task, any REJECTED sends it back to
--   its implementor with only the rejected rows reopened.
--
-- TWO LEDGERS ON ONE ROW, AND WHY THEY ARE SEPARATE COLUMNS
--
-- `ob_journey_step_items.answer` is the implementor's: did I do the
-- thing. `review_state` is the manager's: does it hold. They are not the
-- same fact and must not share a column — a single column would mean the
-- verdict overwrites the claim it is judging, and nobody could then see
-- what was asserted before it was rejected.
--
-- THE REJECTION REASON RIDES ON `remark`, AND THAT IS A DELIBERATE REUSE
--
-- There is no `review_remark`. The row already has one text field and it
-- is the field both people write in: the implementor while the row is
-- theirs, the manager only on a row they have rejected. A second column
-- would be empty on nine rows in ten.
--
-- `ck_ob_journey_step_items_reject_reason` below is what makes that
-- reuse safe: a REJECTED row always carries a reason. Note what this
-- means for the service — while a row sits REJECTED and the implementor
-- is reworking it, they cannot blank the remark, because the row is
-- still rejected. ObJourneyStepLifecycleService#answerItem refuses that
-- edit with `ob-step-reject-reason-required` rather than letting the
-- constraint surface as a 500. The CHECK is the backstop, not the
-- error message.
--
-- DO NOT CONFUSE THIS WITH D-17.
--
-- V20260916_1520 dropped `ck_ob_journey_step_items_remark`, which made
-- the *implementor's* remark optional on a False answer — PLAN.md §4,
-- D-17, a recorded deviation from blueprint §5.8. This migration does
-- not resurrect it. Two rules, opposite directions, same column:
--
--   answer = 0        remark OPTIONAL   (D-17, dropped, stays dropped)
--   review_state = 2  remark MANDATORY  (this file)
--
-- A row answered False with no remark is still perfectly legal. Only a
-- row the manager has rejected must say why.
--
-- `requires_review` DEFAULTS TO ON, INCLUDING FOR WORK ALREADY RUNNING
--
-- Every existing template step and every in-flight task gets
-- `requires_review = 1`. That is a live behaviour change and is meant:
-- a task an implementor completes tomorrow goes to PENDING_REVIEW
-- rather than DONE. Turning it off per template is a template edit, not
-- a migration. Nothing already DONE is touched — this only affects the
-- transition, and closed tasks have already taken it.
--
-- WHY THE STATUS CHECK IS DROPPED AND REBUILT
--
-- MySQL has no "ALTER CHECK"; a named CHECK is replaced by dropping and
-- re-adding it. `status` is VARCHAR(20) and 'PENDING_REVIEW' is 14
-- characters, so the column itself does not change.
--
-- STREAM A REVIEW — this file alters `ob_journey_steps`, which is the
-- table the onboarding module's whole lifecycle turns on. It adds
-- columns and widens one CHECK; it drops nothing and rewrites no row.
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_journey_template_steps — the flag a task snapshots at instantiation.
--
-- Placed on the template for the same reason `requires_signoff` is: the
-- decision "does this kind of work need checking" belongs to whoever
-- designs the journey, not to whoever happens to run one.
-- ---------------------------------------------------------------------
ALTER TABLE ob_journey_template_steps
  ADD COLUMN requires_review TINYINT(1) NOT NULL DEFAULT 1
    COMMENT 'Completing this step submits it for OB Manager review rather than closing it';


-- ---------------------------------------------------------------------
-- ob_journey_steps — the new status, and the review bookkeeping.
--
-- `review_round` counts *returns*, not submissions: 0 until the first
-- rejection, 1 after it. The screen shows round = review_round + 1,
-- because a person counts the attempt they are on rather than the
-- number of times they have been sent back.
-- ---------------------------------------------------------------------
ALTER TABLE ob_journey_steps
  ADD COLUMN requires_review TINYINT(1)  NOT NULL DEFAULT 1
    COMMENT 'Snapshot of the template step flag — never read live from the template',
  ADD COLUMN submitted_at    DATETIME(6) NULL
    COMMENT 'When the implementor last sent this task for review',
  ADD COLUMN submitted_by    BIGINT      NULL,
  ADD COLUMN reviewed_at     DATETIME(6) NULL
    COMMENT 'When the manager last closed a review on this task',
  ADD COLUMN reviewed_by     BIGINT      NULL,
  ADD COLUMN review_round    INT         NOT NULL DEFAULT 0
    COMMENT 'Times this task has been sent back; 0 means never',
  ADD CONSTRAINT fk_ob_journey_steps_submitted_by
    FOREIGN KEY (submitted_by) REFERENCES users (id),
  ADD CONSTRAINT fk_ob_journey_steps_reviewed_by
    FOREIGN KEY (reviewed_by) REFERENCES users (id);

-- The status vocabulary gains PENDING_REVIEW. Dropped and re-added
-- rather than altered, because MySQL offers no other way.
ALTER TABLE ob_journey_steps
  DROP CHECK ck_ob_journey_steps_status;

ALTER TABLE ob_journey_steps
  ADD CONSTRAINT ck_ob_journey_steps_status
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'BLOCKED',
                      'WAITING_ON_CLIENT', 'PENDING_REVIEW', 'DONE', 'SKIPPED'));


-- ---------------------------------------------------------------------
-- ob_journey_step_items — the manager's ledger.
--
-- NOT_REVIEWED is the default and the state a row returns to when it is
-- resubmitted. VERIFIED is terminal for the row: the service refuses
-- every later write to it, by either person, which is what makes "only
-- the rejected rows reopen" true rather than merely drawn.
-- ---------------------------------------------------------------------
ALTER TABLE ob_journey_step_items
  ADD COLUMN review_state VARCHAR(16) NOT NULL DEFAULT 'NOT_REVIEWED'
    COMMENT 'NOT_REVIEWED | VERIFIED | REJECTED — the manager''s verdict on this row',
  ADD COLUMN reviewed_by  BIGINT      NULL,
  ADD COLUMN reviewed_at  DATETIME(6) NULL,
  ADD CONSTRAINT fk_ob_journey_step_items_reviewed_by
    FOREIGN KEY (reviewed_by) REFERENCES users (id),
  ADD CONSTRAINT ck_ob_journey_step_items_review_state
    CHECK (review_state IN ('NOT_REVIEWED', 'VERIFIED', 'REJECTED')),
  -- A rejection always says why. See the header on why this rides on
  -- `remark` and what the service must therefore refuse.
  ADD CONSTRAINT ck_ob_journey_step_items_reject_reason
    CHECK (review_state <> 'REJECTED' OR (remark IS NOT NULL AND remark <> ''));

-- The manager's read is "every row of this step, and which still need a
-- verdict", so the index leads with the step and carries the state.
CREATE INDEX ix_ob_journey_step_items_review
  ON ob_journey_step_items (step_id, review_state);
