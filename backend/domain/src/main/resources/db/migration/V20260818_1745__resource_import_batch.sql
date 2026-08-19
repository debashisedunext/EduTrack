-- =====================================================================
-- B-038 · `users.import_batch_id` — the second registration's half of
-- "every import writes an `import_batch` row so a bad import can be
-- identified and reversed as a set" (blueprint §4B.3, §17).
--
-- Table: users (created by A-003, V20260805_1024; five S-08 columns
-- added by B-011, V20260811_1520).
--
-- FLAGGED FOR STREAM A, on B-011's precedent and for its reason. `users`
-- is not `tickets` and not one of the three append-only tables, so
-- CLAUDE.md asks for no review — but the identity table is close enough
-- to the security core that it should not change on a Stream B branch
-- unremarked. One nullable column, one index, one foreign key; no
-- existing column altered, no index dropped. A rollback is one DROP.
-- =====================================================================
-- Why this column has to exist for B-038 to be a registration at all
-- ---------------------------------------------------------------------
-- `ImportSchemaDefinition.reverse` is the seventh SPI method, added by
-- B-037 so that "B-038 registers resources and gets reversal without
-- writing any of it". What only a registration can supply is *which rows
-- this run created*, and the only durable answer is a stamp on the row —
-- exactly what `clients.import_batch_id` has been since A-006 laid it
-- down in V20260805_1530 with the comment that `import_batches` is
-- created first "because `clients.import_batch_id` points at it".
--
-- Without this column `ResourceImportSchema.reverse` could only return
-- `ImportReversal.none()`, and the history panel would offer a Reverse
-- button on a resource import that quietly deleted nothing. A promise
-- the UI makes and the database cannot keep is worse than the button
-- being absent.
-- ---------------------------------------------------------------------
-- Stamped on INSERT only, and the schema says so rather than trusting it
-- ---------------------------------------------------------------------
-- B-035 made this decision for clients and B-037 depends on it: a run
-- that *updated* a resource must not re-attribute that resource to
-- itself, or a reversal would delete people who existed long before the
-- spreadsheet and merely had a department corrected by it. There is no
-- before image anywhere, so an update is not a thing a reversal could
-- undo even if it wanted to.
--
-- Nothing in the DDL can enforce "insert only" — a trigger refusing an
-- UPDATE of this one column would be an immutability guard on the
-- identity table, which is a much larger promise than this task is
-- making and one Stream A should own if it is ever wanted. It is held
-- by `ResourceImportSchema.upsert` and pinned by its tests, exactly as
-- `ClientImportSchema.upsert` holds it.
-- ---------------------------------------------------------------------
-- RESTRICT, not ON DELETE SET NULL
-- ---------------------------------------------------------------------
-- MySQL's default, and the same as `fk_clients_import_batch`. The batch
-- row is the audit trail and is never deleted — not by a reversal, which
-- fills in four columns on it and leaves it standing, and not by
-- anything else. A `SET NULL` would be a rule for an event that does not
-- happen, and if it ever did happen it would silently orphan the one
-- link that makes an import traceable.
-- =====================================================================

ALTER TABLE users
  ADD COLUMN import_batch_id BIGINT NULL AFTER external_source,

  -- The reversal reads `WHERE import_batch_id = ?` over a table that
  -- will hold every person in the organisation. Without this it is a
  -- full scan per reversal, which is the same index
  -- `ix_clients_import_batch` exists for and which B-037 was the first
  -- reader of.
  ADD KEY ix_users_import_batch (import_batch_id),

  ADD CONSTRAINT fk_users_import_batch
    FOREIGN KEY (import_batch_id) REFERENCES import_batches (id);
