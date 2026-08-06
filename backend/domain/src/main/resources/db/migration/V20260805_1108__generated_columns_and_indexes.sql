-- =====================================================================
-- A-009 · Generated columns, indexes and full-text search
--
-- Source: PLAN.md §3.3 (partial indexes) and §3.8 (full-text)
--
-- MySQL has no partial indexes. PostgreSQL's
--     CREATE INDEX ... ON t (col) WHERE <predicate>
-- is replaced by a STORED generated column that evaluates to NULL when
-- the predicate is false, plus a plain index on that column.
--
-- InnoDB does index NULLs, so excluded rows still occupy index entries —
-- but they cluster at one end and never fall inside a scanned range,
-- which is the property the partial index was bought for.
--
-- Kept separate from A-004/A-005 so all index and search work lands in
-- one reviewable place rather than scattered across the table DDL.
-- =====================================================================


-- ---------------------------------------------------------------------
-- tickets.pcd_open — replaces PLAN.md §3.3 / blueprint §8.2:
--     CREATE INDEX ix_tickets_pcd_open ON tickets(planned_close_date)
--       WHERE actual_close_date IS NULL;
--
-- This is the index that makes the 15-minute SLA scan O(breaches) rather
-- than O(all tickets). Stream D's scanner queries WHERE pcd_open < ? and
-- the optimiser takes a range scan; closed tickets evaluate to NULL and
-- are never in range.
--
-- At 50,000 tickets (the A-073 load target) the difference is a range
-- scan over the open set versus a full scan every 15 minutes, forever.
-- ---------------------------------------------------------------------
ALTER TABLE tickets
  ADD COLUMN pcd_open DATETIME(6)
    GENERATED ALWAYS AS (IF(actual_close_date IS NULL, planned_close_date, NULL)) STORED,
  ADD INDEX ix_tickets_pcd_open (pcd_open);


-- ---------------------------------------------------------------------
-- ticket_stage_transitions.current_ticket_id — replaces §4A.5's
--     CREATE INDEX ix_stage_current ON ticket_stage_transitions(ticket_id)
--       WHERE is_current = TRUE;
--
-- "Where is this ticket right now?" is asked on every ticket detail page
-- load, every ribbon render and every stage-SLA scan. Exactly one row per
-- ticket is current, so this index stays small no matter how long the
-- transition history grows.
--
-- The column recomputes when the seal trigger (A-008) drops is_current
-- to 0. That is automatic and is why the trigger's immutable-column list
-- does not — and must not — mention it.
-- ---------------------------------------------------------------------
ALTER TABLE ticket_stage_transitions
  ADD COLUMN current_ticket_id BIGINT
    GENERATED ALWAYS AS (IF(is_current = 1, ticket_id, NULL)) STORED,
  ADD INDEX ix_stage_current (current_ticket_id);


-- ---------------------------------------------------------------------
-- Full-text search — PLAN.md §3.8.
--
-- The blueprint's phase-1 plan is PostgreSQL full-text. InnoDB FULLTEXT
-- is weaker (limited stemming, no tsvector ranking control) but adequate
-- for the specified use: global search over ticket title and description.
--
-- Ticket-code lookup (CRM-26-00347) deliberately does NOT go through
-- this index — it resolves on uq_tickets_code. That is the dominant
-- search by volume and it must be exact and instant.
--
-- If relevance quality becomes a complaint, OpenSearch is the phase-4
-- escape hatch the blueprint already anticipates.
-- ---------------------------------------------------------------------
ALTER TABLE tickets
  ADD FULLTEXT INDEX ftx_tickets (title, description);
