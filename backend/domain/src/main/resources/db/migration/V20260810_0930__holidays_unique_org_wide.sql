-- =====================================================================
-- B-023 · correction — `uq_holidays` could not stop a duplicate org-wide
-- holiday.
--
-- Table: holidays (created by A-007, V20260805_1106)
--
-- WHAT WAS WRONG
--
-- `uq_holidays (holiday_date, project_id)` reads as though it forbids two
-- holidays on the same date for the same scope. It does not, for the
-- scope that matters most: **MySQL treats NULL as distinct in a unique
-- index**, and `project_id IS NULL` is precisely how an org-wide holiday
-- is written. So any number of org-wide holidays could share a date:
--
--     INSERT … ('2026-12-25', 'Christmas',  NULL)   -- accepted
--     INSERT … ('2026-12-25', 'Duplicate',  NULL)   -- also accepted
--
-- Found by driving `POST /masters/holidays` against a real database for
-- B-023: the second call returned 201 where the contract promises 409.
-- Project-scoped rows were never affected — a non-NULL project_id
-- compares normally — which is why the index looked like it worked.
--
-- THE FIX
--
-- A stored generated column that maps NULL to 0, and the unique index
-- over that instead. 0 is not a valid `projects.id` (AUTO_INCREMENT
-- starts at 1), so it cannot collide with a real project.
--
-- The original index is dropped and replaced rather than added to. Two
-- overlapping unique keys would leave the weaker one in place to be
-- read by the next person as the rule.
--
-- Not an edit to V20260805_1106 — that file is applied, Flyway has
-- checksummed it, and a correction is a new migration (CONTRIBUTING.md,
-- SEED-MANIFEST.md §3). `holidays` is not one of the four append-only
-- tables, so this does not need Stream A's review by CLAUDE.md's rule —
-- but it does alter a table A-007 created, so it is flagged for
-- Shivendra rather than slipped in.
-- =====================================================================

-- Existing duplicates would make the new index fail to build. There are
-- none in any environment yet (the holiday master ships with B-023), but
-- an ALTER that assumes that silently is how a deploy breaks at 2am.
-- This keeps the earliest row per (date, scope) and drops the rest.
DELETE h FROM holidays h
  JOIN holidays keep
    ON keep.holiday_date = h.holiday_date
   AND COALESCE(keep.project_id, 0) = COALESCE(h.project_id, 0)
   AND keep.id < h.id;

-- VIRTUAL, not STORED, and that is not a preference.
--
-- Adding a STORED generated column forces a table rebuild, and MySQL cannot
-- rebuild a table that is the child of a foreign key without tripping over it:
--
--     ERROR 1215 (HY000): Cannot add foreign key constraint
--
-- `holidays` has fk_holidays_project, so STORED is simply unavailable here
-- short of disabling foreign_key_checks, which is not something a migration
-- should do to a live schema. A VIRTUAL column is added in place, occupies no
-- storage, and MySQL indexes it perfectly well — the value is computed on
-- read and materialised in the index, which is exactly what this needs.
ALTER TABLE holidays
  ADD COLUMN project_scope BIGINT
      AS (COALESCE(project_id, 0)) VIRTUAL
      COMMENT 'NULL-free mirror of project_id, so the unique key can see org-wide rows',
  DROP INDEX uq_holidays,
  ADD UNIQUE KEY uq_holidays (holiday_date, project_scope);
