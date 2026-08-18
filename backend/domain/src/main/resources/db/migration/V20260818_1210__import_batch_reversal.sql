-- =====================================================================
-- B-037 · `import_batches` traceability — a bad import, reversed as a
-- set, and the reversal itself on the record.
--
-- Blueprint §4B.3's closing validation rule, and §17's row for "Client
-- Excel import silently corrupts the master": *"every import writes an
-- `import_batch` row so a bad import can be identified and reversed as a
-- set."* B-035 wrote the row and stamped `clients.import_batch_id`;
-- nothing has ever read either. This adds what a reversal has to record
-- about itself.
--
-- Table: import_batches (created by A-006, V20260805_1530; its status
-- vocabulary corrected by B-030, V20260810_2010).
--
-- Not `tickets` and not one of the three append-only tables, so CLAUDE.md
-- asks for no Stream A review — but it alters a table A-006 created, so
-- it is flagged for Shivendra rather than slipped in, exactly as
-- V20260810_2010 was.
-- =====================================================================
-- Why reversal is four columns and NOT a fifth status
-- ---------------------------------------------------------------------
-- `REVERSED` was the obvious shape and it is wrong, because `status`
-- answers a different question. It records **how the run ended** —
-- QUEUED, RUNNING, COMPLETED, FAILED — and `ImportBatchStatus` is
-- explicit that COMPLETED "is not a synonym for no rejections": a run
-- that refused half the file and wrote the rest completed, and B-036's
-- error report is how the user recovers the other half.
--
-- Reversal is a separate, later fact about a run that has already ended.
-- Overwriting the status with it destroys the only record of the
-- outcome: "COMPLETED with 6 rejections, later reversed" and "FAILED
-- after 314 rows, later reversed" collapse into one word, and the error
-- report still sitting in `error_report_key` would then belong to a run
-- whose status no longer explains why it has one.
--
-- The two facts are also independent in the other direction: a FAILED
-- run is the one most likely to be reversed, and a reversal that had to
-- move the status would have to decide which of the two truths to keep.
--
-- It also avoids rewriting `ck_import_batches_status`, which B-030 added
-- precisely so the column's vocabulary and the contract's could not
-- drift a second time. A constraint that has to be dropped and recreated
-- every time the lifecycle grows is a constraint on its way to being
-- deleted.
-- =====================================================================
-- Why `retained_rows` is stored beside `reversed_rows`
-- ---------------------------------------------------------------------
-- A reversal is not guaranteed to be total, and the difference is the
-- thing an operator needs after the screen that ran it has been closed.
--
-- `tickets.client_id` REFERENCES clients(id) with no ON DELETE — MySQL's
-- default RESTRICT — so a client the import created and which has since
-- been named on a ticket cannot be deleted. That is the correct outcome
-- rather than a limitation: the alternatives are failing the entire
-- reversal because one client got used, or destroying a ticket's client.
-- The row is kept, and it is counted here.
--
-- `reversed_rows + retained_rows` is therefore what the run created, and
-- a `retained_rows` above zero is the batch saying "I am reversed, and
-- these are still here" long after the response that first said so.
--
-- Neither is derivable afterwards. Once the clients are deleted the
-- `import_batch_id` that pointed at this run is gone with them, so a
-- COUNT reconstructs nothing: an unreversed batch and a fully reversed
-- one both answer zero.
-- =====================================================================
-- What is deliberately NOT recorded
-- ---------------------------------------------------------------------
-- The rows themselves. A reversal deletes clients the import created,
-- and a "before image" table would let an undo of the undo exist —
-- which is a bigger promise than the blueprint makes and one nothing in
-- the product could keep, because the deletion cascades to
-- `client_contacts` and `client_projects`.
--
-- What the blueprint asks for is that a bad import be *identifiable* and
-- *reversible as a set*, and `import_batch_id` on the row plus these
-- four columns on the batch is exactly that. Restoring deleted master
-- data is a backup's job.
-- =====================================================================

ALTER TABLE import_batches
  -- NULL is the whole state machine: a batch is un-reversed until this
  -- is stamped, and it is what the service reads to refuse a second
  -- reversal. A boolean beside a timestamp would be two columns that can
  -- disagree.
  ADD COLUMN reversed_at    DATETIME(6) NULL
    COMMENT 'B-037 — when this run was reversed as a set. NULL means never; a reversal is not repeatable.',
  -- The actor, kept scalar and unconstrained by a foreign key for the
  -- reason `imported_by` beside it is: see domain/imports/package-info.
  ADD COLUMN reversed_by    BIGINT      NULL
    COMMENT 'B-037 — who reversed it. Best-effort, like imported_by: an unauthenticated dev-noauth caller records NULL rather than blocking the operation.',
  ADD COLUMN reversed_rows  INT         NOT NULL DEFAULT 0
    COMMENT 'B-037 — rows this run created that the reversal deleted. Never includes rows it merely updated: there is no before image, so an update is not undone.',
  ADD COLUMN retained_rows  INT         NOT NULL DEFAULT 0
    COMMENT 'B-037 — rows this run created that the reversal could NOT delete, because something else now references them (a ticket). Kept rather than destroyed.';

-- The history panel's sort, and the reversal service's own read.
--
-- `ix_import_batches_entity (entity, created_at)` already exists from
-- A-006 and serves `findByEntityOrderByCreatedAtDesc` — the ordering is
-- the index rather than a filesort, which is why the panel's query is
-- shaped that way. Nothing further is needed for the list.
--
-- What is needed is the other direction: `clients.import_batch_id` is
-- already indexed by `ix_clients_import_batch` (A-006), so finding every
-- row one run created is an index lookup rather than a scan of the
-- client master. Recorded here because it is the only reason that index
-- exists and B-037 is its first reader.
