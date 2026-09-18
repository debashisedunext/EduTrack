-- =====================================================================
-- ob_signoffs — the withdrawal record, so cancelObSignoff's mandatory
-- reason has somewhere to live.
--
-- Source:  contracts/openapi.yaml, `cancelObSignoff` — "The row is **not**
--          deleted, and the status is `CANCELLED` rather than the row
--          disappearing, because 'we asked and then withdrew' is a fact a
--          client may remember and ask about. Deleting it would leave the
--          staff side unable to answer."
--          `ObSignoffCancelRequest`, which makes `reason` required with
--          minLength 1.
--
-- WHAT WAS WRONG
--
-- The contract has required that reason since A-118 drafted the route,
-- and `ob_signoffs` has never had a column to put it in. The route had no
-- controller, so nothing noticed. Implementing it without this migration
-- would mean accepting a mandatory field and discarding it — which is
-- worse than not asking for it, because the operator who typed it has
-- every reason to believe it was kept, and the contract's own argument
-- for keeping the row at all is that staff can answer the question
-- later. "We withdrew it" without "why" answers half of what a client
-- actually asks.
--
-- WHY A COLUMN AND NOT THE AUDIT TRAIL
--
-- `AuditInterceptor` records every mutating route by method and pattern,
-- which is why no onboarding service writes a bespoke audit row. But it
-- constructs its `AuditEntry` with `oldValue` and `newValue` both null —
-- it deliberately does not read request bodies — so a reason routed
-- there would not be stored anywhere at all. Checked rather than assumed.
--
-- WHY NOT ob_step_history
--
-- That journal is append-only, hash-chained and the right home for a
-- STEP sign-off's withdrawal. It is the wrong home for a GO_LIVE one:
-- a go-live sign-off hangs off a journey and names no step, so half the
-- cancellations this route accepts would have no row to write. A record
-- that exists for one `kind` and not the other is the sort of asymmetry
-- somebody later reads as data loss. One column, both kinds.
--
-- WHY THESE THREE AND NOT ONE
--
-- `cancellation_reason` alone would say why without saying when or by
-- whom, and `status = 'CANCELLED'` carries no timestamp of its own —
-- `updated_at` moves on any write and cannot be trusted to mean the
-- withdrawal. `signed_at`/`objected_at` already establish the shape for
-- the two decisions a client can make; this is the same shape for the
-- one the organisation can make.
--
-- NOTHING IS PUT ON THE WIRE BY THIS MIGRATION.
-- `ObSignoff` and `ObSignoffDetail` are the contract's shapes and neither
-- gains a field here. These columns are the record, read by whoever has
-- to answer for the withdrawal, not a fifth thing for OB-05 to render.
-- Adding them to a response would be a contract change, which is a
-- reviewed decision and not this migration's to make.
--
-- WHY THIS IS SAFE WITHOUT STREAM A'S REVIEW.
-- `ob_signoffs` is not `tickets`, `ticket_history`, `ticket_effort_logs`
-- or `ticket_stage_transitions` — CLAUDE.md's four protected tables —
-- and V20260909_1830 added three columns to this same table on exactly
-- this reasoning.
-- =====================================================================

ALTER TABLE ob_signoffs
  ADD COLUMN cancelled_at DATETIME(6) NULL
    COMMENT 'When staff withdrew the request. NULL until then; set together with cancellation_reason.'
    AFTER objection_note,
  ADD COLUMN cancelled_by BIGINT NULL
    COMMENT 'Which user withdrew it. NULL for a row no staff member cancelled.'
    AFTER cancelled_at,
  ADD COLUMN cancellation_reason VARCHAR(2000) NULL
    COMMENT 'Why it was withdrawn. Required by cancelObSignoff, kept so the staff side can answer a client who remembers being asked.'
    AFTER cancelled_by;

-- ON DELETE SET NULL, not CASCADE: a withdrawal is a fact about the
-- sign-off, and deleting the user who made it must not delete the record
-- that it happened. `requested_by` is declared the same way for the same
-- reason.
ALTER TABLE ob_signoffs
  ADD CONSTRAINT fk_ob_signoffs_cancelled_by
    FOREIGN KEY (cancelled_by) REFERENCES users (id) ON DELETE SET NULL;

-- Timestamp and reason move together, on ck_ob_signoffs_csat's own
-- precedent for a pair that is meaningless apart. `cancelled_by` is
-- deliberately outside the check — it is nullable independently, both
-- because the FK above can null it and because a cancellation made by
-- something other than a named user is a state this should not refuse.
ALTER TABLE ob_signoffs
  ADD CONSTRAINT ck_ob_signoffs_cancelled
    CHECK ((cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL));
