-- =====================================================================
-- C-040 · Close/resolve dialog (S-23) — two columns `ticket_cycles` did
-- not carry.
--
-- `resolution_summary` already exists (A-004) — "how THIS cycle was
-- closed". S-23's other two per-cycle facts have nowhere to land:
--
--   root_cause_category            — S-23's "root cause category", a free
--                                     label rather than a closed vocabulary
--                                     (no master backs it yet; matches the
--                                     precedent CLAUDE.md §4 records for
--                                     other unmastered free-text fields).
--   client_verification_requested  — S-23's "optional client verification
--                                     request" checkbox. Recorded so the
--                                     close is reproducible from the row
--                                     alone; Stream D has no consumer for
--                                     it yet (flagged in the PR).
--
-- Both nullable/defaulted and additive — no existing row is touched, no
-- read of `ticket_cycles` changes shape.
--
-- `final_effort_hours` (S-23's fourth field) deliberately gets NO column
-- here. It is a confirmation of hours already in `ticket_effort_logs`,
-- not a new figure — a column that could disagree with the ledger is the
-- exact drift `total_effort_hrs`'s own comment warns about. CloseService
-- writes it as a FIELD_CHANGED history row instead, the same instrument
-- C-067 uses for every other field with no dedicated column.
--
-- Needs Stream A's review before merge (CLAUDE.md: any migration
-- touching a ticket-lifecycle table) — flagged rather than assumed, the
-- same precedent C-036 (`ticket_pct_complete`) and C-065
-- (`product_modules`) set.
-- =====================================================================

ALTER TABLE ticket_cycles
  ADD COLUMN root_cause_category           VARCHAR(100)  NULL     AFTER resolution_summary,
  ADD COLUMN client_verification_requested TINYINT(1)    NOT NULL DEFAULT 0
                                                                   AFTER root_cause_category;
