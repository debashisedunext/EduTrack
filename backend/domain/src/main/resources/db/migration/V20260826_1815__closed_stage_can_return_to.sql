-- =====================================================================
-- The desk can send a signed-off ticket back.
--
-- Table touched: workflow_stages (can_return_to only). B-004's seed data,
-- like V20260826_1520 beside it — flagged for Stream B in the pull
-- request rather than changed quietly. Not one of the four append-only
-- tables, so no Stream A gate.
--
-- V20260826_1520 put the Closed stage in Support's hands. This is the
-- other half of that: a desk handed a ticket to close needs to be able
-- to refuse it, and §4A.1's mechanism for refusing is a backward move to
-- a `can_return_to` target. Closed was seeded with can_return_to NULL —
-- correct while the stage was terminal-and-nobody's, and wrong now that
-- somebody stands in it and has a decision to make. Without a target
-- here `ReworkService.requireReturnTargetAllowed` answers 422
-- (StageMayNotReturnToException) for every backward move out of Closed,
-- so the only exit is closure.
--
-- WHY NOT A NEW CYCLE. "Reopen" is the word a user reaches for, but a
-- ticket that is RESOLVED has not been closed, and its cycle has not
-- been sealed. TicketNotClosedException's own javadoc calls this out by
-- name: "Accepting RESOLVED here would increment the wrong counter and
-- seal a cycle that had not finished — §4A.2's two counters, confused in
-- the one place it costs most." So the desk's refusal is an ITERATION
-- inside the open cycle (rework_count, iteration_no), not a CYCLE
-- (reopen_count, cycle_no). Closure is still the only thing a real
-- reopen follows.
--
-- WHICH TARGET. §4A.1's loop-back table sends every rejection to the
-- stage that does the work, and Sign-off's own row is ["DEV"] — the
-- desk rejecting a sign-off wants the same place the PM rejecting one
-- does. Templates with no Development stage fall back to Triage, which
-- is exactly the reroute V20260807_1700's header already documents for
-- Infra Flow ("replan — there is no dev stage to return to"). Resolved
-- by what the template actually contains rather than by template name,
-- so a renamed or newly-authored template is handled on its own shape.
--
-- The derived table is materialised before the UPDATE runs, which is
-- what makes reading workflow_stages while writing to it legal in MySQL
-- — a bare correlated subquery over the target table is not.
--
-- Only rows that have no target yet are touched, so a template whose
-- designer (B-043) has since authored one keeps it, and re-running this
-- against a database that already has it changes no rows.
-- =====================================================================

UPDATE workflow_stages ws
  LEFT JOIN (
    SELECT DISTINCT template_id
      FROM workflow_stages
     WHERE stage_code = 'DEV'
  ) dev ON dev.template_id = ws.template_id
   SET ws.can_return_to = IF(dev.template_id IS NULL, '["TRIAGE"]', '["DEV"]')
 WHERE ws.stage_code = 'CLOSED'
   AND ws.can_return_to IS NULL;
