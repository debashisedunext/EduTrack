-- =====================================================================
-- B-003 · Seed — 8 statuses + the workflow_transitions matrix
--
-- Tables seeded: statuses, workflow_transitions
-- Source: blueprint §3.1/§3.2 (status vs stage, lifecycle), §2 (role
--         permission matrix), §12.1 (colour tokens), PLAN.md §5 (G-1, G-3)
--
-- STATUS is "is work moving?" — separate from the ribbon/STAGE ("who owns
-- it right now?", seeded in B-004). A ticket can be In Progress while
-- sitting in the QA stage. This file only ever touches `statuses` and
-- `workflow_transitions`; it does not touch `workflow_stages`.
--
-- Reconciling the lifecycle diagram (§3.2) with the 8-status seed list
-- named in this task and in CLAUDE.md: the diagram also shows ASSIGNED
-- and IN REVIEW, neither of which is one of the 8. Interpretation used
-- here — flagged for review, not silently assumed:
--   - ASSIGNED is not a status. Who a ticket is assigned to is the
--     `assignee` field plus a history row; the ticket stays NEW until
--     someone actually starts work.
--   - IN REVIEW is not a status either. It is the ribbon's QA stage
--     (B-004's territory), not a "is work moving" state.
--
-- workflow_transitions is a WHITELIST — a missing (from, to, role) row
-- means that move is impossible for that role, independent of anything
-- the frontend renders. from_status = NULL means "on ticket creation".
--
-- requires_effort follows G-1 (PLAN.md §5): effort logging is blocking
-- by default, applied here to every transition that represents a chunk
-- of work being claimed complete (* -> RESOLVED). requires_reason marks
-- the moves surprising enough to demand an explanation (pausing,
-- blocking, rejecting, reopening, withdrawing) versus routine ones
-- (starting, resuming, finishing) — a judgement call, not a blueprint
-- citation, same as the effort/reason split on any one row below unless
-- otherwise noted.
--
-- Two rows are inferred rather than spec-cited, flagged individually
-- below: NEW -> RESOLVED (the diagram's unlabelled "cancel" path) and
-- RESOLVED -> REWORK (which roles constitute the "quality gate").
--
-- Two rows are locked by governance decisions, not open to
-- interpretation:
--   - RESOLVED -> CLOSED: Admin/PM/Support Desk only. G-3 — closure
--     belongs to the Sign-off stage owner; Developer/QA/Deployment
--     never get a row here, on purpose.
--   - CLOSED -> REOPENED: Admin/PM/Support Desk only, per the §2
--     permission matrix ("Reopen ticket" — same three, same exclusion).
-- =====================================================================


-- ---------------------------------------------------------------------
-- statuses — 8 rows.
--
-- Colours are drawn only from tokens that already exist in blueprint
-- §12.1 (base tokens + the chart palette) — never a new hex. Where §12.1
-- names a status literally (--success "Closed, on-time", --info
-- "In progress") that mapping is used as-is; the rest is chosen for
-- maximum distinctness across 8 chips, all of which also carry an icon
-- and text label per the §12.1 accessibility rule (colour is never the
-- only signal).
-- ---------------------------------------------------------------------
INSERT INTO statuses (code, name, colour, seq, is_open, is_terminal) VALUES
  ('NEW',            'New',            '#4F46E5', 10, 1, 0),  -- --primary
  ('IN_PROGRESS',    'In Progress',    '#3B82F6', 20, 1, 0),  -- --info, per §12.1
  ('ON_HOLD',        'On Hold',        '#F59E0B', 30, 1, 0),  -- --warning
  ('AWAITING_INFO',  'Awaiting Info',  '#6B7280', 40, 1, 0),  -- --text-secondary
  ('REWORK',         'Rework',         '#8B5CF6', 50, 1, 0),  -- chart palette
  ('RESOLVED',       'Resolved',       '#14B8A6', 60, 1, 0),  -- chart palette
  ('CLOSED',         'Closed',         '#10B981', 70, 0, 1),  -- --success, per §12.1
  ('REOPENED',       'Reopened',       '#EF4444', 80, 1, 0);  -- --danger


-- ---------------------------------------------------------------------
-- workflow_transitions — 14 moves x role. Grouped by move, one INSERT
-- per group so each stays readable and independently reviewable.
-- ---------------------------------------------------------------------

-- 1. Creation. All six roles may raise a ticket (blueprint §2).
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  (NULL, 'NEW', 'ADMIN',        0, 0),
  (NULL, 'NEW', 'PM',           0, 0),
  (NULL, 'NEW', 'SUPPORT_DESK', 0, 0),
  (NULL, 'NEW', 'DEVELOPER',    0, 0),
  (NULL, 'NEW', 'QA',           0, 0),
  (NULL, 'NEW', 'DEPLOYMENT',   0, 0);

-- 2. Work starts. The assignee (any role) moves it forward.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('NEW', 'IN_PROGRESS', 'ADMIN',        0, 0),
  ('NEW', 'IN_PROGRESS', 'PM',           0, 0),
  ('NEW', 'IN_PROGRESS', 'SUPPORT_DESK', 0, 0),
  ('NEW', 'IN_PROGRESS', 'DEVELOPER',    0, 0),
  ('NEW', 'IN_PROGRESS', 'QA',           0, 0),
  ('NEW', 'IN_PROGRESS', 'DEPLOYMENT',   0, 0);

-- 3. Withdrawn before work starts — the diagram's unlabelled "cancel"
-- path. INFERRED, not directly spec-cited. Restricted to the roles who
-- can close/manage a ticket, same set as row 12/13 below.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('NEW', 'RESOLVED', 'ADMIN',        1, 0),
  ('NEW', 'RESOLVED', 'PM',           1, 0),
  ('NEW', 'RESOLVED', 'SUPPORT_DESK', 1, 0);

-- 4. Pause. Must say why.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('IN_PROGRESS', 'ON_HOLD', 'ADMIN',        1, 0),
  ('IN_PROGRESS', 'ON_HOLD', 'PM',           1, 0),
  ('IN_PROGRESS', 'ON_HOLD', 'SUPPORT_DESK', 1, 0),
  ('IN_PROGRESS', 'ON_HOLD', 'DEVELOPER',    1, 0),
  ('IN_PROGRESS', 'ON_HOLD', 'QA',           1, 0),
  ('IN_PROGRESS', 'ON_HOLD', 'DEPLOYMENT',   1, 0);

-- 5. Resume from hold.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('ON_HOLD', 'IN_PROGRESS', 'ADMIN',        0, 0),
  ('ON_HOLD', 'IN_PROGRESS', 'PM',           0, 0),
  ('ON_HOLD', 'IN_PROGRESS', 'SUPPORT_DESK', 0, 0),
  ('ON_HOLD', 'IN_PROGRESS', 'DEVELOPER',    0, 0),
  ('ON_HOLD', 'IN_PROGRESS', 'QA',           0, 0),
  ('ON_HOLD', 'IN_PROGRESS', 'DEPLOYMENT',   0, 0);

-- 6. Blocked on the reporter. Must say what's needed.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('IN_PROGRESS', 'AWAITING_INFO', 'ADMIN',        1, 0),
  ('IN_PROGRESS', 'AWAITING_INFO', 'PM',           1, 0),
  ('IN_PROGRESS', 'AWAITING_INFO', 'SUPPORT_DESK', 1, 0),
  ('IN_PROGRESS', 'AWAITING_INFO', 'DEVELOPER',    1, 0),
  ('IN_PROGRESS', 'AWAITING_INFO', 'QA',           1, 0),
  ('IN_PROGRESS', 'AWAITING_INFO', 'DEPLOYMENT',   1, 0);

-- 7. Info received.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('AWAITING_INFO', 'IN_PROGRESS', 'ADMIN',        0, 0),
  ('AWAITING_INFO', 'IN_PROGRESS', 'PM',           0, 0),
  ('AWAITING_INFO', 'IN_PROGRESS', 'SUPPORT_DESK', 0, 0),
  ('AWAITING_INFO', 'IN_PROGRESS', 'DEVELOPER',    0, 0),
  ('AWAITING_INFO', 'IN_PROGRESS', 'QA',           0, 0),
  ('AWAITING_INFO', 'IN_PROGRESS', 'DEPLOYMENT',   0, 0);

-- 8. Work claimed complete. G-1: effort logging is blocking.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('IN_PROGRESS', 'RESOLVED', 'ADMIN',        0, 1),
  ('IN_PROGRESS', 'RESOLVED', 'PM',           0, 1),
  ('IN_PROGRESS', 'RESOLVED', 'SUPPORT_DESK', 0, 1),
  ('IN_PROGRESS', 'RESOLVED', 'DEVELOPER',    0, 1),
  ('IN_PROGRESS', 'RESOLVED', 'QA',           0, 1),
  ('IN_PROGRESS', 'RESOLVED', 'DEPLOYMENT',   0, 1);

-- 9. Quality gate rejects it. INFERRED role set — Admin/PM/QA are the
-- roles who verify quality; Support Desk and the execution roles
-- (Developer/Deployment) do not get a row here.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('RESOLVED', 'REWORK', 'ADMIN', 1, 0),
  ('RESOLVED', 'REWORK', 'PM',    1, 0),
  ('RESOLVED', 'REWORK', 'QA',    1, 0);

-- 10. Assignee starts the fix.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('REWORK', 'IN_PROGRESS', 'ADMIN',        0, 0),
  ('REWORK', 'IN_PROGRESS', 'PM',           0, 0),
  ('REWORK', 'IN_PROGRESS', 'SUPPORT_DESK', 0, 0),
  ('REWORK', 'IN_PROGRESS', 'DEVELOPER',    0, 0),
  ('REWORK', 'IN_PROGRESS', 'QA',           0, 0),
  ('REWORK', 'IN_PROGRESS', 'DEPLOYMENT',   0, 0);

-- 11. Fixed again. G-1 applies the same as row 8.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('REWORK', 'RESOLVED', 'ADMIN',        0, 1),
  ('REWORK', 'RESOLVED', 'PM',           0, 1),
  ('REWORK', 'RESOLVED', 'SUPPORT_DESK', 0, 1),
  ('REWORK', 'RESOLVED', 'DEVELOPER',    0, 1),
  ('REWORK', 'RESOLVED', 'QA',           0, 1),
  ('REWORK', 'RESOLVED', 'DEPLOYMENT',   0, 1);

-- 12. Sign-off. G-3, LOCKED: no Developer/QA/Deployment row, ever.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('RESOLVED', 'CLOSED', 'ADMIN',        0, 0),
  ('RESOLVED', 'CLOSED', 'PM',           0, 0),
  ('RESOLVED', 'CLOSED', 'SUPPORT_DESK', 0, 0);

-- 13. Reopen. Blueprint §2 "Reopen ticket": same three roles as row 12,
-- same exclusion. Must say why.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('CLOSED', 'REOPENED', 'ADMIN',        1, 0),
  ('CLOSED', 'REOPENED', 'PM',           1, 0),
  ('CLOSED', 'REOPENED', 'SUPPORT_DESK', 1, 0);

-- 14. New cycle begins.
INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort) VALUES
  ('REOPENED', 'IN_PROGRESS', 'ADMIN',        0, 0),
  ('REOPENED', 'IN_PROGRESS', 'PM',           0, 0),
  ('REOPENED', 'IN_PROGRESS', 'SUPPORT_DESK', 0, 0),
  ('REOPENED', 'IN_PROGRESS', 'DEVELOPER',    0, 0),
  ('REOPENED', 'IN_PROGRESS', 'QA',           0, 0),
  ('REOPENED', 'IN_PROGRESS', 'DEPLOYMENT',   0, 0);
