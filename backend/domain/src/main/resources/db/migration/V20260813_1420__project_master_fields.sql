-- =====================================================================
-- B-016 · S-10 Project Master — the two fields the screen specifies and
-- the column had no room for, plus a vocabulary for `status`.
--
-- Table: projects (created by A-003, V20260805_1024)
--
-- FLAGGED FOR STREAM A. `projects` is A-003's table. CLAUDE.md mandates
-- their review only for `tickets` and the three append-only tables, and
-- this touches none of those — but the identity baseline should not
-- change on a masters branch unremarked, which is the precedent B-011
-- set when it added five columns to `users`.
--
-- ---------------------------------------------------------------------
-- 1. `description` — blueprint §7.5 S-10 lists it as a project field
--
-- The Project Master's form specifies Description and there was nowhere
-- to put it. VARCHAR(1000) rather than TEXT: this is a paragraph of
-- context on a master row, not a ticket body, and an off-page TEXT
-- column costs a second read on a table five other screens page through
-- to fill a picker.
--
-- ---------------------------------------------------------------------
-- 2. `auto_assign_rule` — the contract already promised it
--
-- `Project.autoAssignRule` has been in contracts/openapi.yaml since
-- D-001 with no column behind it. **The Settings tab that edits it is
-- B-019**; this migration exists so that B-016 can answer the field
-- honestly in the meantime, because the alternative was returning a
-- hardcoded 'MANUAL' that no client could distinguish from a stored
-- value — and the first admin to set the rule in B-019 would have found
-- their save had been reading a constant all along.
--
-- 'MANUAL' is the default deliberately: round-robin and least-loaded
-- both assign live work to somebody without a human deciding, and that
-- is not a behaviour a project should acquire by being created.
--
-- The Settings tab's *other* half — allowed task types — is NOT here.
-- It is a join table rather than a column, and inventing its shape from
-- this branch would be deciding B-019's design in a migration nobody
-- would think to read while building it. `ProjectWriteRequest` dropped
-- `allowedTaskTypeIds` on the same reasoning.
--
-- ---------------------------------------------------------------------
-- 3. `ck_projects_status` — three states, and the whole set
--
-- Blueprint S-10 says Active / On Hold / Closed. The column has always
-- been an unconstrained VARCHAR(20) defaulting to 'ACTIVE', and
-- `Project`'s javadoc described a *fourth* and *fifth* value —
-- "ACTIVE | ON_HOLD | COMPLETED | ARCHIVED" — that the blueprint does
-- not contain and nothing has ever written. Two vocabularies for one
-- column with nothing mapping between them is exactly what B-030 found
-- on `import_batches.status`, and the lesson there applies here before
-- the first writer rather than after: a documented vocabulary drifts, a
-- constrained one cannot. B-016 is that first writer.
--
-- Verified before constraining: `SELECT DISTINCT status FROM projects`
-- is 'ACTIVE' everywhere. The fixture corpus (B-007
-- `ReferenceDataFixture`) sets 'ACTIVE' explicitly and no other code
-- path assigns the column at all. The UPDATE below is defensive rather
-- than corrective — an ALTER that adds a CHECK while *assuming* that is
-- how a deploy breaks at 2am.
--
-- ---------------------------------------------------------------------
-- 4. `ix_projects_name` — the S-10 grid's sort order
--
-- The list pages by a keyset cursor over `(name, id)` (CONVENTIONS.md
-- §6 forbids offset paging). Without an index on the leading column
-- that is a filesort of the whole table per page.
--
-- ---------------------------------------------------------------------
-- What is deliberately NOT here
--
-- `manager_id` stays NULLable, though S-10 marks Project Manager
-- mandatory and `ProjectWriteRequest` requires it. The service is the
-- thing that refuses; narrowing the column would additionally invalidate
-- any historical row that predates the rule, and there is no migration
-- that can invent a manager for one. The rule is enforced where it can
-- give a usable error.
-- =====================================================================

-- Defensive, not corrective — see §3 above.
UPDATE projects SET status = 'ACTIVE' WHERE status NOT IN ('ACTIVE', 'ON_HOLD', 'CLOSED');

ALTER TABLE projects
  ADD COLUMN description VARCHAR(1000) NULL
    COMMENT 'S-10 free text. Not a ticket body — a paragraph of context on a master row.'
    AFTER name,
  ADD COLUMN auto_assign_rule VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
    COMMENT 'ROUND_ROBIN|LEAST_LOADED|MANUAL — who an unassigned new ticket goes to. Edited by B-019''s Settings tab; stored here so B-016 does not have to return a constant.'
    AFTER colour_tag,
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'ACTIVE|ON_HOLD|CLOSED — blueprint S-10, and the whole vocabulary. There is no delete: a project that issued ticket IDs cannot be removed without orphaning them.',
  ADD CONSTRAINT ck_projects_status
    CHECK (status IN ('ACTIVE', 'ON_HOLD', 'CLOSED')),
  ADD CONSTRAINT ck_projects_auto_assign_rule
    CHECK (auto_assign_rule IN ('ROUND_ROBIN', 'LEAST_LOADED', 'MANUAL')),
  ADD KEY ix_projects_name (name);
