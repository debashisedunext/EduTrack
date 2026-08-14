-- ---------------------------------------------------------------------
-- A-043 · at most one compensating entry per corrected row.
--
-- The compensating-entry pattern (blueprint §221, PLAN.md §414) reverses a
-- mistake with a NEW row pointing at the wrong one, exactly as an accounting
-- reversal. A-040 made the pair structural in TicketJournal — is_correction
-- and corrects_entry_id are set together or not at all — and the foreign keys
-- already prove the target row exists.
--
-- What neither can express is that a row may be reversed only ONCE. Two
-- corrections against one +8 effort log net to -8 rather than 0, and the
-- §4A.4 grid then reports a negative figure for a stage somebody genuinely
-- worked. There is no reading of a double reversal that is not a mistake: it
-- is a double-submitted form, a retry after a lost response, or two people
-- correcting the same row minutes apart.
--
-- WHY A UNIQUE INDEX RATHER THAN A SERVICE CHECK
--
-- MySQL permits any number of NULLs in a unique index, so this constrains
-- exactly the correction rows and leaves every ordinary append untouched —
-- which is what makes the constraint expressible at all. A-040's boundary
-- says the journal enforces only what the database cannot; this one it can,
-- so it belongs here, in the layer a stray script cannot talk its way past.
--
-- The plain index is DROPPED because the unique index serves the same lookups
-- (finding a row's correction, and the FK's own referential check). Two
-- indexes on one column would be paid for on every insert for nothing.
--
-- ORDER MATTERS. fk_history_corrects needs an index on corrects_entry_id to
-- exist at all times; dropping ix_history_corrects before the unique index is
-- in place would fail with errno 150. Add first, then drop.
--
-- Safe to apply with data present: no correction row exists anywhere yet —
-- nothing in the codebase writes one, and A-043 is the task that introduces
-- the API. Cheap now, a data migration once it is not.
-- ---------------------------------------------------------------------

ALTER TABLE ticket_history
  ADD UNIQUE KEY uq_history_corrects (corrects_entry_id);

ALTER TABLE ticket_history
  DROP INDEX ix_history_corrects;

ALTER TABLE ticket_effort_logs
  ADD UNIQUE KEY uq_effort_corrects (corrects_entry_id);

ALTER TABLE ticket_effort_logs
  DROP INDEX ix_effort_corrects;
