-- =====================================================================
-- C-036 · `tickets.pct_complete` — S-21 Quick Update's progress slider.
--
-- Declared on the contract's `Ticket`, `QuickUpdateRequest` and
-- `TicketPatchRequest` schemas since D-001, with nowhere on the row to
-- land: `tickets` carries no percent-complete column at all. Needs
-- Stream A's review before merge (CLAUDE.md — any migration touching
-- `tickets`).
--
-- NOT NULL DEFAULT 0 rather than nullable: the contract's own schema
-- marks it `integer, minimum: 0, maximum: 100` with no `null` in the
-- type union, unlike `plannedCloseDate` and the "Where it happened"
-- fields beside it, which spell out `[integer, 'null']` when null is a
-- real state. Zero is also the honest starting value for every ticket
-- already in the table — nothing has been reported "done" on any of
-- them, so backfilling 0 states a fact rather than guessing one.
-- =====================================================================

ALTER TABLE tickets
  ADD COLUMN pct_complete SMALLINT NOT NULL DEFAULT 0
    COMMENT 'S-21 Quick Update. 0-100, resource-reported.'
    AFTER total_effort_hrs,
  ADD CONSTRAINT ck_tickets_pct_complete
    CHECK (pct_complete BETWEEN 0 AND 100);
