-- =====================================================================
-- B-026 · S-33 — the Client Master create/edit form's missing columns.
--
-- **`clients` is A-006's table — FLAGGED FOR STREAM A.** CLAUDE.md only
-- mandates their review for `tickets` and the three append-only tables,
-- and this touches none of those; the flag is the same courtesy B-011
-- extended for `users` and B-016 for `projects`. Five columns added, one
-- CHECK added, nothing dropped, nothing renamed, no existing value
-- rewritten.
--
-- Blueprint §4B.2's "Client Master fields (screen S-32 / S-33)" table
-- names six field groups. B-025 shipped the grid over the columns that
-- existed; this is what S-33's form needs and the table does not have.
--
--   Identity   → logo_url
--   Commercial → billing_reference, billing_email
--   Notes      → tags        (notes itself is already a TEXT column)
--
-- =====================================================================
-- Why `tags` is JSON and not a join table
-- ---------------------------------------------------------------------
-- The same call `users.skills` made in V20260811_1520, for the same
-- reason. A `tags` + `client_tags` pair would be two tables and a master
-- screen to maintain a free-text vocabulary that nothing queries across
-- clients — the account notes' neighbour, not a dimension. If a report
-- ever needs to group by tag, that is the moment to normalise, and the
-- migration is a backfill rather than a redesign.
--
-- The CHECK constrains **shape, not vocabulary**: an array of strings.
-- B-019 drew that distinction for `projects.mandatory_fields` and it is
-- the right one again here — pinning the values would mean an admin
-- cannot invent a tag without a migration, which is the whole point of
-- the field.
--
-- =====================================================================
-- ck_clients_status, and the third state
-- ---------------------------------------------------------------------
-- Blueprint §4B.2's Identity group names **Active / Inactive /
-- Prospect** and the column has been an unconstrained VARCHAR(20)
-- carrying two of them. B-026 is the first writer of the third, which is
-- B-016's rule — land the CHECK at the column's first writer, before
-- anything can store a fourth value, rather than after.
--
-- Safe on existing data: `ReferenceDataFixture` writes 'ACTIVE', B-025's
-- two status setters write 'ACTIVE'/'INACTIVE', and no seed migration
-- inserts a client row at all. Nothing else has ever written the column.
--
-- The service validates the vocabulary *before* the database sees it, so
-- this CHECK should be unreachable from the API — which matters, because
-- B-011 established that a MySQL CHECK violation (error 3819, SQL state
-- HY000) is translated to `UncategorizedSQLException` and surfaces as a
-- 500 rather than a field-keyed 400. The constraint is the backstop for
-- the writers that do not go through the service: B-035's import, and a
-- hand-run UPDATE.
-- =====================================================================

ALTER TABLE clients
  -- S-33 Identity. A URL rather than a BLOB: the logo is rendered by the
  -- browser from wherever it is hosted, and putting image bytes in the
  -- row every client grid page reads would be paying for it on every
  -- request that does not display one.
  ADD COLUMN logo_url          VARCHAR(500)  NULL AFTER short_name,

  -- S-33 Commercial. The client's own reference — a PO number, a
  -- contract id, whatever their finance team quotes back at us. Free
  -- text on purpose; it is their vocabulary, not ours.
  ADD COLUMN billing_reference VARCHAR(60)   NULL AFTER contract_end,

  -- S-33 Commercial. Distinct from `primary_email` (the account
  -- contact) and from `support_email` (which D-039 matches inbound mail
  -- against). Invoices go somewhere else at most organisations, and
  -- collapsing the three would mean a support auto-reply reaching
  -- accounts payable.
  ADD COLUMN billing_email     VARCHAR(150)  NULL AFTER billing_reference,

  -- S-33 Notes group. See the header: shape-constrained, not
  -- vocabulary-constrained.
  ADD COLUMN tags              JSON          NULL AFTER notes;

ALTER TABLE clients
  ADD CONSTRAINT ck_clients_tags
    CHECK (tags IS NULL
           OR (JSON_TYPE(tags) = 'ARRAY'
               AND JSON_SCHEMA_VALID(
                     '{"type":"array","items":{"type":"string"}}',
                     tags)));

ALTER TABLE clients
  ADD CONSTRAINT ck_clients_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'PROSPECT'));
