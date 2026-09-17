-- =====================================================================
-- The check-list row becomes the unit of work, on both sides.
--
-- Tables: ob_journey_step_items, ob_implementor_daily_stats
--
-- WHAT CHANGES, AND WHY THE ROW RATHER THAN THE TASK
--
-- Until now a review was a property of the *task*: the implementor
-- submitted a whole check list, the manager read the whole check list,
-- and `ob_journey_steps.status = 'PENDING_REVIEW'` was what froze the
-- rows in between. That shape cannot express either of the two things
-- people actually do — finishing two rows of five and wanting those two
-- looked at now, or reading three of the five that arrived and leaving
-- the rest for after lunch.
--
-- So `row_state` moves the state machine down one level:
--
--     DRAFT     the implementor's  — answer it, then send it
--     SENT      the manager's      — record a verdict, then send it back
--     VERIFIED  nobody's           — closed for good
--     REJECTED  the implementor's  — fix it, then send it again
--
-- `review_state` keeps its meaning and narrows its scope: it is the
-- manager's verdict, *draft* while the row is SENT and final once the
-- row has been sent back. Two columns because they answer two different
-- questions — whose desk is this on, and what did the reviewer decide —
-- and collapsing them would make "rejected but not yet sent back"
-- unsayable, which is the state a reviewer is in for as long as they are
-- typing the reason.
--
-- `ob_journey_steps.status` is not dropped and does not become
-- decorative. It stays the authority for PENDING / BLOCKED /
-- WAITING_ON_CLIENT / DONE / SKIPPED, and PENDING_REVIEW is now
-- *materialised* from the rows: any row SENT and the task reads
-- PENDING_REVIEW, otherwise IN_PROGRESS. The completion gate and
-- `activateEligibleSteps` both read that column, and giving them a
-- derived value to read is much cheaper than teaching each of them to
-- aggregate rows.
--
-- THE BACKFILL IS NOT A GUESS
--
-- Every live row's position is recoverable exactly, because the two
-- facts that determine it are already stored:
--
--   * a row carrying a final verdict is where that verdict put it —
--     VERIFIED or REJECTED;
--   * anything else inside a task that is PENDING_REVIEW is on the
--     manager's desk — SENT;
--   * everything else is with its implementor — DRAFT.
--
-- Order matters in the statements below: the verdict cases are written
-- first and the SENT case excludes them, so a row verified in an earlier
-- round of a task that is under review again is not dragged back onto
-- the manager's desk.
--
-- `submitted_at` / `submitted_by` are left NULL for the backfilled SENT
-- rows rather than copied from `ob_journey_steps.submitted_at`. The task
-- stamp is when the *task* was submitted and every row would inherit the
-- same instant, which would read as a per-row fact that was never
-- recorded. NULL says "sent before this was tracked", which is true.
--
-- `outcome_seen_at` answers "has the implementor looked at this
-- outcome yet" and is what stops the approval banner either shouting for
-- ever or forgetting on refresh. Backfilled to `reviewed_at` for rows
-- already decided — an outcome that predates the signal existing has
-- been seen for every practical purpose, and leaving it NULL would greet
-- everybody with a banner about work they finished last week.
--
-- THE FOUR COUNTERS
--
-- `ob_implementor_daily_stats` is already keyed (stat_date, user_id),
-- which is the exact grain both dashboard cards need, so this is four
-- columns rather than a new table — and no `make grants` run, because
-- no table is created.
--
--   reviews_pending    rows waiting on this user *as a manager*
--   sent_for_review    rows this user has out with their manager
--   reviews_approved   rows of theirs approved today
--   reviews_rejected   rows of theirs sent back today
--
-- Written only by ObStatsRefreshWorker. Never counted live behind a
-- dashboard (CLAUDE.md).
-- =====================================================================

ALTER TABLE ob_journey_step_items
  ADD COLUMN row_state       VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' AFTER review_state,
  ADD COLUMN submitted_at    DATETIME(6)   NULL     AFTER row_state,
  ADD COLUMN submitted_by    BIGINT        NULL     AFTER submitted_at,
  ADD COLUMN outcome_seen_at DATETIME(6)   NULL     AFTER reviewed_at;

ALTER TABLE ob_journey_step_items
  ADD CONSTRAINT ck_ob_journey_step_items_row_state
  CHECK (row_state IN ('DRAFT', 'SENT', 'VERIFIED', 'REJECTED'));

-- Final verdicts first; the SENT sweep below excludes whatever these set.
UPDATE ob_journey_step_items
   SET row_state = 'VERIFIED',
       outcome_seen_at = COALESCE(outcome_seen_at, reviewed_at)
 WHERE review_state = 'VERIFIED';

UPDATE ob_journey_step_items
   SET row_state = 'REJECTED',
       outcome_seen_at = COALESCE(outcome_seen_at, reviewed_at)
 WHERE review_state = 'REJECTED';

-- Undecided rows inside a task that is under review are on the manager's desk.
UPDATE ob_journey_step_items i
   JOIN ob_journey_steps s ON s.id = i.step_id
   SET i.row_state = 'SENT'
 WHERE s.status = 'PENDING_REVIEW'
   AND i.review_state = 'NOT_REVIEWED';

-- The queue reads: "what is on my desk", by task and by state.
CREATE INDEX ix_ob_journey_step_items_row_state
    ON ob_journey_step_items (row_state, step_id);

ALTER TABLE ob_implementor_daily_stats
  ADD COLUMN reviews_pending  INT NOT NULL DEFAULT 0 AFTER blocked_hours,
  ADD COLUMN sent_for_review  INT NOT NULL DEFAULT 0 AFTER reviews_pending,
  ADD COLUMN reviews_approved INT NOT NULL DEFAULT 0 AFTER sent_for_review,
  ADD COLUMN reviews_rejected INT NOT NULL DEFAULT 0 AFTER reviews_approved;
