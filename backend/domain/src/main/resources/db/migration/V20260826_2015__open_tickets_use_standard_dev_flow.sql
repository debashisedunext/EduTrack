-- =====================================================================
-- ⚠ TOUCHES `tickets` — STREAM A REVIEW REQUIRED (CLAUDE.md, "Database
-- migrations"). One column, `workflow_template_id`, on open tickets only.
-- None of the three append-only tables is read or written here.
--
-- The companion to V20260826_1930. That file routed every *task type* to
-- Standard Dev Flow, so tickets created from then on draw the full
-- eight-stage ribbon. It deliberately left existing tickets alone, and
-- the result was the obvious one: a ticket created twelve minutes before
-- it still drew five segments, which reads as stages "disappearing" on a
-- ticket somebody is looking at right now.
--
-- WHY THIS IS SAFE HERE, AND WOULD NOT BE IN GENERAL.
--
-- `tickets.workflow_template_id` is stamped at creation, and every
-- `ticket_stage_transitions` row already written names a stage code
-- resolved against that template. Repointing a ticket at a template
-- missing one of those codes would leave the ribbon with history it
-- cannot place — segments with no hop, hops with no segment.
--
-- That cannot happen for these three templates, because Standard Dev
-- Flow's stage codes are a strict superset of the other two:
--
--   Standard Dev Flow   INTAKE TRIAGE DEV QA DEPLOY VERIFY SIGNOFF CLOSED
--   Support Fast-Track  INTAKE TRIAGE DEV          SIGNOFF CLOSED
--   Infra Flow          INTAKE TRIAGE        DEPLOY VERIFY CLOSED
--
-- Verified against the data as well as the seed: every distinct
-- `to_stage` across the whole of `ticket_stage_transitions` is one of
-- those eight. So no hop can be orphaned by this move.
--
-- WHAT DOES CHANGE, AND IS ACCEPTED.
--
--   * The next stage. `TransitionService.nextStageAfter` reads the
--     template, so an Infra ticket standing in Triage now advances to
--     Development rather than straight to Deployment. That is the point
--     of the change, not a side effect of it.
--
--   * Segments that read Pending behind the current one. A ticket
--     already in Verification on Infra Flow has no Development or QA hop
--     and never will, so those two render Pending to the left of a
--     Current segment. Odd to look at, harmless to the data, and it
--     resolves itself as tickets move on. Nothing is invented: a stage
--     with no hop is exactly what Pending means.
--
-- WHAT IS DELIBERATELY EXCLUDED.
--
--   * CLOSED tickets. Their cycles are sealed, and a sealed cycle's
--     ribbon is a record of what happened, not a view that should
--     acquire two new Pending segments because a mapping changed a
--     month later. `ticket_cycles.is_sealed` means finished.
--
--   * Tickets with no template at all (`workflow_template_id IS NULL`).
--     They predate B-043's designer and `RibbonAssembler` answers them
--     with an empty segment list on purpose. Giving them a ribbon of
--     eight Pending segments would invent a journey nobody recorded.
--
--   * A reopened ticket's earlier sealed cycles do move, because the
--     column is per-ticket rather than per-cycle. There is no way to
--     scope it more finely without a schema change, and the ticket
--     itself is open — flagged here rather than left to be discovered.
--
-- TO REVERT, restore per-task-type routing in a new migration and
-- repoint these tickets back by task type — never by editing this file.
-- =====================================================================

UPDATE tickets t
  JOIN workflow_templates standard
    ON standard.name = 'Standard Dev Flow'
   SET t.workflow_template_id = standard.id
 WHERE t.status <> 'CLOSED'
   AND t.workflow_template_id IS NOT NULL
   AND t.workflow_template_id <> standard.id;
