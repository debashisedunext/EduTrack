-- =====================================================================
-- B-119 · CSAT — the one-question go-live survey, and its storage.
--
-- Adds `csat_score`, `csat_comment` and `csat_submitted_at` to
-- `ob_signoffs`.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-119 — "CSAT — a public
--            one-question page, storage, and a summary. The design
--            fires a toast saying the survey was sent and there is
--            nothing behind it; this is that nothing."
--          contracts/openapi.yaml `submitObCsat`, `ObCsatRequest`
--            (POST /public/onboarding/signoff/csat)
--          PHASE-2-BUILD-PLAN §3 #9
--
-- WHY THIS RIDES ob_signoffs RATHER THAN A NEW TABLE.
-- The survey is offered on the same session `verifyObSignoffOtp` minted
-- for a GO_LIVE acceptance, and answers exactly one question about that
-- acceptance. It is 1:1 with the signoff row the client accepted through
-- — the same relationship `signed_name`/`signed_at` already have to this
-- table (see V20260909_1120) — so a second table with a foreign key back
-- here would be the same fact, filed twice, for no join this feature
-- ever needs.
--
-- WHY `csat_score` IS A CHECKED RANGE RATHER THAN LEFT TO THE SERVICE.
-- `ObCsatRequest.score` is `minimum: 1, maximum: 5` on the contract.
-- Bean Validation enforces it on the way in; the CHECK enforces it on
-- every future writer that is not that one controller, which is the
-- same reasoning `ck_ob_signoffs_objection` and `ck_ob_signoffs_signed`
-- already apply to this table twice.
--
-- WHY `csat_submitted_at` IS THE "ALREADY SURVEYED" GUARD AND NOT A
-- BOOLEAN.
-- `submitObCsat` needs the same fact `ObSignoffOtpService.csatOffered`
-- needs before it — "has this client already answered" — and an instant
-- answers both a boolean question and "when", which the summary report
-- wants too. A second boolean column next to it would be a value that
-- can disagree with the timestamp it duplicates.
--
-- WHY `csat_score` AND `csat_submitted_at` ARE BOUND TOGETHER AND
-- `csat_comment` IS NOT.
-- `ck_ob_signoffs_signed` draws the identical line one column set over:
-- a submitted survey has a score and a moment, both or neither, and a
-- row with one and not the other is a state the database should refuse
-- rather than a state the application has to remember never to write.
-- The comment is optional on the contract (`nullable`, no minLength) —
-- a client can rate without a remark — so binding it into the CHECK
-- would reject the single most common honest answer.
--
-- WHY NULLABLE, NOT NOT NULL WITH A BACKFILL.
-- Every existing STEP sign-off, and every GO_LIVE sign-off nobody has
-- surveyed yet, has none of this and never will — CSAT is optional by
-- construction ("a client who closes the tab has still gone live"). A
-- NOT NULL column would need a sentinel meaning "not surveyed", which
-- is what NULL already means.
--
-- WHY THIS IS SAFE WITHOUT STREAM A'S REVIEW.
-- `ob_signoffs` is not `tickets`, `ticket_history`, `ticket_effort_logs`
-- or `ticket_stage_transitions` — CLAUDE.md's four protected tables —
-- and every other Stream B `ob_*` migration in this backlog has landed
-- the same way.
-- =====================================================================

ALTER TABLE ob_signoffs
  ADD COLUMN csat_score TINYINT NULL
    COMMENT 'B-119 · the one-question go-live survey, 1-5. Set together with csat_submitted_at.'
    AFTER pdf_storage_key,
  ADD COLUMN csat_comment VARCHAR(2000) NULL
    COMMENT 'B-119 · optional remark alongside the score.'
    AFTER csat_score,
  ADD COLUMN csat_submitted_at DATETIME(6) NULL
    COMMENT 'B-119 · when the survey was answered. NULL means not yet (or never, for a STEP sign-off) — the "already surveyed" guard for submitObCsat''s 422.'
    AFTER csat_comment;

ALTER TABLE ob_signoffs
  ADD CONSTRAINT ck_ob_signoffs_csat
    CHECK ((csat_score IS NULL AND csat_submitted_at IS NULL)
        OR (csat_score IS NOT NULL AND csat_submitted_at IS NOT NULL
            AND csat_score BETWEEN 1 AND 5));
