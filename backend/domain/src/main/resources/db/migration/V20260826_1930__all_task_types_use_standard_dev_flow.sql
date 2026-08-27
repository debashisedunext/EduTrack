-- =====================================================================
-- Every task type resolves to Standard Dev Flow — the full eight-stage
-- ribbon on every new ticket.
--
-- Table touched: workflow_template_mappings (template_id only). B-041's
-- table, seeded by V20260821_1015 — flagged for Stream B in the pull
-- request. Not one of the four append-only tables.
--
-- WHAT THIS CHANGES, PLAINLY. §4A.9 gave three templates and routed task
-- types between them: Production Bug / Change Request / Future Release to
-- Standard Dev Flow (8 stages), Client Request / Browser Issue to Support
-- Fast-Track (5), Server Issue / Network Issue to Infra Flow (5). A
-- Network Issue therefore drew a ribbon with no Development, QA or
-- Sign-off segment — correct by that spec, and the reason those stages
-- appeared to "disappear" on a newly created ticket.
--
-- Requested 26 Aug 2026: every new ticket should show all eight stages.
-- So all seven task-type rules now point at Standard Dev Flow. This is a
-- deliberate departure from §4A.9's routing table, not a correction of a
-- defect — the two five-stage templates were doing exactly what they were
-- seeded to do.
--
-- WHAT IS DELIBERATELY NOT TOUCHED.
--
--   * `workflow_templates` and `workflow_stages`. Infra Flow and Support
--     Fast-Track keep their stages and stay selectable in B-043's
--     designer; they are simply no longer the automatic answer for any
--     task type. Adding DEV/QA/SIGNOFF to them instead would have made
--     all three templates identical and left `can_return_to` pointing at
--     stages the seed's own header explains those flows do not have.
--
--   * Existing tickets. `tickets.workflow_template_id` is stamped at
--     creation and every `ticket_stage_transitions` row already written
--     names a stage from whichever template was resolved then. Repointing
--     a live ticket would leave hops referring to stages its new template
--     does not contain — the ribbon would render segments with no
--     history and history with no segment. Tickets already on Infra Flow
--     or Support Fast-Track finish there; only tickets created from now
--     on are affected.
--
--   * Project-scoped rules (`project_id IS NOT NULL`). Rungs 1 and 2 of
--     TemplateResolver's ladder are an Admin's to author per project, and
--     V20260821_1015 seeds none. If one exists it was chosen deliberately
--     for that project and still wins, exactly as the ladder says it
--     should.
--
-- This also settles a row that had drifted: BROWSER_ISSUE was reading
-- Standard Dev Flow against a committed seed that says Support
-- Fast-Track. It now matches this file rather than disagreeing with
-- V20260821_1015 for no recorded reason.
--
-- TO REVERT, put the §4A.9 routing back in a new migration — never by
-- editing this one:
--   Support Fast-Track <- CLIENT_REQUEST, BROWSER_ISSUE
--   Infra Flow         <- SERVER_ISSUE, NETWORK_ISSUE
--
-- Matched by template name, which is UNIQUE, and by `task_type_id IS NOT
-- NULL` so a catch-all row (task_type_id NULL = "any task type") is left
-- alone — that is a different rung of the ladder and not this file's to
-- decide. Re-running changes no rows once every rule already points here.
-- =====================================================================

UPDATE workflow_template_mappings m
  JOIN workflow_templates standard
    ON standard.name = 'Standard Dev Flow'
   SET m.template_id = standard.id
 WHERE m.task_type_id IS NOT NULL
   AND m.project_id IS NULL
   AND m.template_id <> standard.id;
