-- =====================================================================
-- B-030 · correction — `import_batches.status` held a second, private
-- vocabulary that no client could ever read.
--
-- Table: import_batches (created by A-006, V20260805_1530)
--
-- WHAT WAS WRONG
--
-- Two vocabularies were written for one column, and nothing mapped
-- between them:
--
--   the column     PENDING | VALIDATING | COMMITTING | DONE | FAILED
--   the contract   QUEUED  | RUNNING    | COMPLETED  | FAILED
--
-- `ImportBatchResponse.status` in contracts/openapi.yaml is the second
-- set, and it is the only one a caller can observe. A row defaulting to
-- 'PENDING' therefore serialises as a value the generated TypeScript
-- union does not contain — the frontend's own zod schema would reject
-- the response the backend just produced.
--
-- Found in B-030 while reading the schema against the contract, before
-- either had a consumer. No import has ever run in any environment, so
-- this corrects a definition rather than data.
--
-- THE FIX
--
-- The contract wins, per CLAUDE.md: the blueprint is the authority on
-- behaviour and the contract is how behaviour is stated to clients. The
-- private vocabulary loses nothing, because two of its states were
-- unreachable by design:
--
--   PENDING / VALIDATING  — the step-4 dry run writes NOTHING (blueprint
--                           §4B.3). No row exists to hold either state;
--                           the first INSERT happens at commit.
--   COMMITTING → RUNNING   } renamed
--   DONE       → COMPLETED }
--
-- So the lifecycle is exactly the contract's four: a batch is born
-- QUEUED at commit, goes RUNNING while the job works, and ends
-- COMPLETED or FAILED.
--
-- The CHECK is what stops the two sets diverging a second time. B-023
-- learned this on `ck_working_calendar_weekly_off`: a documented
-- vocabulary drifts, a constrained one cannot.
--
-- Not an edit to V20260805_1530 — that file is applied and Flyway has
-- checksummed it; a correction is a new migration (SEED-MANIFEST.md §3).
-- `import_batches` is not one of the four append-only tables, so this
-- does not need Stream A's review by CLAUDE.md's rule — but it does
-- alter a table A-006 created, so it is flagged for Shivendra rather
-- than slipped in.
-- =====================================================================

-- Defensive, not corrective: no import_batches row exists in any
-- environment (the engine ships with B-030). An ALTER that adds a CHECK
-- while assuming that silently is how a deploy breaks at 2am.
UPDATE import_batches SET status = 'QUEUED'    WHERE status IN ('PENDING', 'VALIDATING');
UPDATE import_batches SET status = 'RUNNING'   WHERE status = 'COMMITTING';
UPDATE import_batches SET status = 'COMPLETED' WHERE status = 'DONE';

ALTER TABLE import_batches
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
    COMMENT 'QUEUED|RUNNING|COMPLETED|FAILED — the commit job lifecycle. The dry run writes nothing, so no state precedes QUEUED.',
  ADD CONSTRAINT ck_import_batches_status
    CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'));
