-- =====================================================================
-- B-004 · Seed — 3 workflow templates + their stages (the ribbon)
--
-- Tables seeded: workflow_templates, workflow_stages
-- Source: blueprint §4A.1 (stage table, owner roles, default SLAs,
--         loop-back table), §4A.9 (which stages belong to which
--         template, which task types map to each)
--
-- This file only ever touches workflow_templates and workflow_stages.
-- It does not touch statuses or workflow_transitions (B-003's territory)
-- or ticket_stage_transitions (append-only, service-layer writes only).
--
-- STAGE vs STATUS, restated from B-003: a stage is "who owns it right
-- now?" (the ribbon); a status is "is work moving?". A ticket can be
-- IN_PROGRESS while sitting in the QA stage.
--
-- sla_hours is WORKING hours per stage, read by Stream D's stage-SLA
-- scanner (§4A.7 "stuck in stage" alert). Figures below are taken
-- verbatim from the §4A.1 stage table, not invented here. Development's
-- row reads "Per SLA policy" in that table — meaning it is NOT a fixed
-- template-level figure, it resolves from the project × task-type × level
-- SLA matrix (B-018) instead — so DEV.sla_hours is seeded NULL on
-- purpose, not a missing value.
--
-- can_return_to is populated straight from the §4A.1 loop-back table
-- (QA -> DEV = REWORK, Deployment -> DEV = DEPLOY_FAILED, Verification
-- -> DEV = VERIFY_FAILED, Sign-off -> DEV = SIGNOFF_REJECTED, Development
-- -> Triage = CLARIFICATION). OVERRIDE (any -> any, PM/Admin only) is
-- deliberately NOT encoded here — it is a service-layer bypass of this
-- column, not a whitelisted target.
--
-- Two decisions below are inferred rather than spec-cited, flagged again
-- at point of use:
--   - is_default: the blueprint marks the column but never says which of
--     the three templates defaults. Standard Dev Flow (all 8 stages) is
--     seeded as the default — it is the template every other template
--     is a reduction of.
--   - Sign-off owner_role: §4A.1 lists "PM / Support Desk" as one cell,
--     but owner_role is a single role. Split by flow: PM owns sign-off on
--     Standard Dev Flow (internal engineering work); Support owns it on
--     Support Fast-Track (client-facing tickets close through the desk
--     that raised them). Closed then reuses whichever role signed off,
--     for the same reason — the schema requires a non-null owner_role
--     even though §4A.1's Closed row reads "-" (terminal, no real owner).
--
-- Infra Flow has neither a Development nor a Sign-off stage, so two of
-- the four loop-back rows in §4A.1 don't apply as literally written
-- (both target "Development"). Rerouted, flagged inline: Deployment
-- failure falls back to Triage (replan — there is no dev stage to
-- return to); Verification failure falls back to Deployment (redeploy).
--
-- owner_role = 'SUPPORT', not 'SUPPORT_DESK'. V20260807_1030 renamed the
-- role code and calls this file out by name as depending on it — a stage
-- owned by a role code that no longer exists in `roles` would mean no
-- Support user could ever act on their own Intake or Sign-off segment.
-- owner_role carries no FK to roles.code (plain VARCHAR(20), see the
-- baseline_workflow.sql schema), so a stale value here would not fail
-- loudly — it would just silently never match a caller's role at
-- handoff time.
-- =====================================================================


-- ---------------------------------------------------------------------
-- workflow_templates — 3 rows.
-- ---------------------------------------------------------------------
INSERT INTO workflow_templates (name, description, is_default, is_active) VALUES
  ('Standard Dev Flow',
   'All 8 stages. Production Bug, Change Request, Future Release. Blueprint §4A.9.',
   1, 1),
  ('Support Fast-Track',
   'Intake -> Triage -> Development -> Sign-off -> Closed. Client Request, Browser Issue. Blueprint §4A.9.',
   0, 1),
  ('Infra Flow',
   'Intake -> Triage -> Deployment -> Verification -> Closed. Server Issue, Network Issue. Blueprint §4A.9.',
   0, 1);


-- ---------------------------------------------------------------------
-- Standard Dev Flow — all 8 stages, §4A.1 verbatim.
-- ---------------------------------------------------------------------
INSERT INTO workflow_stages
  (template_id, seq, stage_code, display_name, owner_role, sla_hours, is_optional, can_return_to, icon)
VALUES
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   10, 'INTAKE',  'Intake',              'SUPPORT',      2.00, 0, NULL,           'inbox'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   20, 'TRIAGE',  'Triage / Planning',   'PM',           4.00, 0, NULL,           'list-checks'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   30, 'DEV',     'Development',         'DEVELOPER',    NULL, 0, '["TRIAGE"]',   'code-2'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   40, 'QA',      'QA / Testing',        'QA',           8.00, 0, '["DEV"]',      'flask-conical'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   50, 'DEPLOY',  'Deployment',          'DEPLOYMENT',   4.00, 0, '["DEV"]',      'rocket'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   60, 'VERIFY',  'Verification',        'DEVELOPER',    4.00, 0, '["DEV"]',      'check-check'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   70, 'SIGNOFF', 'Sign-off',            'PM',           8.00, 0, '["DEV"]',      'clipboard-check'),
  ((SELECT id FROM workflow_templates WHERE name = 'Standard Dev Flow'),
   80, 'CLOSED',  'Closed',              'PM',           NULL, 0, NULL,           'circle-check-big');


-- ---------------------------------------------------------------------
-- Support Fast-Track — 5 stages, §4A.9. Sign-off owner_role = SUPPORT
-- (inferred split of §4A.1's "PM / Support Desk" cell, see header).
-- ---------------------------------------------------------------------
INSERT INTO workflow_stages
  (template_id, seq, stage_code, display_name, owner_role, sla_hours, is_optional, can_return_to, icon)
VALUES
  ((SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track'),
   10, 'INTAKE',  'Intake',              'SUPPORT',      2.00, 0, NULL,           'inbox'),
  ((SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track'),
   20, 'TRIAGE',  'Triage / Planning',   'PM',           4.00, 0, NULL,           'list-checks'),
  ((SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track'),
   30, 'DEV',     'Development',         'DEVELOPER',    NULL, 0, '["TRIAGE"]',   'code-2'),
  ((SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track'),
   40, 'SIGNOFF', 'Sign-off',            'SUPPORT',      8.00, 0, '["DEV"]',      'clipboard-check'),
  ((SELECT id FROM workflow_templates WHERE name = 'Support Fast-Track'),
   50, 'CLOSED',  'Closed',              'SUPPORT',      NULL, 0, NULL,           'circle-check-big');


-- ---------------------------------------------------------------------
-- Infra Flow — 5 stages, §4A.9. No Development or Sign-off stage exists
-- here, so DEPLOY and VERIFY reroute their loop-backs (see header note).
-- ---------------------------------------------------------------------
INSERT INTO workflow_stages
  (template_id, seq, stage_code, display_name, owner_role, sla_hours, is_optional, can_return_to, icon)
VALUES
  ((SELECT id FROM workflow_templates WHERE name = 'Infra Flow'),
   10, 'INTAKE',  'Intake',              'SUPPORT',      2.00, 0, NULL,           'inbox'),
  ((SELECT id FROM workflow_templates WHERE name = 'Infra Flow'),
   20, 'TRIAGE',  'Triage / Planning',   'PM',           4.00, 0, NULL,           'list-checks'),
  ((SELECT id FROM workflow_templates WHERE name = 'Infra Flow'),
   30, 'DEPLOY',  'Deployment',          'DEPLOYMENT',   4.00, 0, '["TRIAGE"]',   'rocket'),
  ((SELECT id FROM workflow_templates WHERE name = 'Infra Flow'),
   40, 'VERIFY',  'Verification',        'DEVELOPER',    4.00, 0, '["DEPLOY"]',   'check-check'),
  ((SELECT id FROM workflow_templates WHERE name = 'Infra Flow'),
   50, 'CLOSED',  'Closed',              'PM',           NULL, 0, NULL,           'circle-check-big');
