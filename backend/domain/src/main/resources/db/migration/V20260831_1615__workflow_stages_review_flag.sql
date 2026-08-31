-- =====================================================================
-- Dashboard Rework Dev 1, PR 5 · workflow_stages.is_review_stage
--
-- Table altered: workflow_stages (one column, backfilled for the two
--                codes that mean it today)
-- Source: docs/Dashboard-Rework-Plan.md ("Pending Review resolves review
--         stages from the stage master — never hardcode VERIFY/SIGNOFF"),
--         needed by GET /tickets' new `pendingReview` param (PR 5) and by
--         TodayProgressService's Pending Review card (PR 6).
--
-- WHY A COLUMN AND NOT A HARDCODED LIST IN CODE
--
-- The obvious shortcut is `stageCode IN ("VERIFY", "SIGNOFF")` in Java,
-- and the plan explicitly rules it out. The reason is the same one
-- B-039 gave for `statuses.category` (row 56 of this file's own
-- manifest): a project that renames its sign-off stage, or a future
-- template with a differently-named review step, would silently stop
-- counting — a filter that quietly returns fewer rows than it should,
-- which nobody notices because the card still shows a number.
--
-- WHY "VERIFY" AND "SIGNOFF", NOT "QA"
--
-- Blueprint's own wording for the Pending Review card is "RESOLVED but
-- not CLOSED, plus tickets in verify/sign-off stages" — QA is testing
-- in progress, not a stage waiting on someone else's review, and belongs
-- to the WIP figures instead. Matched by `stage_code` across every
-- template rather than by name or `owner_role`, since the three seeded
-- templates do not agree on either: Standard Dev Flow's SIGNOFF is owned
-- by PM, Support Fast-Track's by SUPPORT, and Infra Flow has no SIGNOFF
-- stage at all — only VERIFY.
--
-- NOT NULL WITH A DEFAULT, B-042's precedent (`is_deprecated`, row 68)
--
-- Every one of B-004's seeded stages already has a correct answer — VERIFY
-- and SIGNOFF are review stages, the other six are not — so this is a
-- state with a known initial value, not a judgement to phase in nullable
-- first. A stage created after this file defaults to "not a review
-- stage", which is the right default: a template author naming a new
-- review step opts it in explicitly on B's designer, the same way
-- `is_optional` and `can_return_to` are already authored rather than
-- inferred.
--
-- NO INDEX
--
-- Eighteen rows, the same call B-042's own migration made for the
-- identical reason: `GET /tickets`' `pendingReview` filter reads this
-- table once for the (small, cacheable) set of review stage codes, not
-- per ticket — the predicate that runs per ticket is `current_stage IN
-- (...)`, against `tickets`, which is a different table's index question.
--
-- WORKFLOW_STAGES IS STREAM B'S TABLE
--
-- Flagged for Stream B's sign-off rather than made quietly (CLAUDE.md,
-- code ownership) — the same precedent rows 65–70 of this manifest set
-- for Stream C editing Stream B's seed data when it blocked their own
-- work.
-- =====================================================================


-- 1. The flag. Every existing row gets a real answer in the same
-- statement that adds it — see "NOT NULL WITH A DEFAULT" above.
ALTER TABLE workflow_stages
  ADD COLUMN is_review_stage TINYINT(1) NOT NULL DEFAULT 0 AFTER deprecated_at;


-- 2. The two stage codes the blueprint names, across every template that
-- has them — see "WHY VERIFY AND SIGNOFF" above.
UPDATE workflow_stages
   SET is_review_stage = 1
 WHERE stage_code IN ('VERIFY', 'SIGNOFF');
