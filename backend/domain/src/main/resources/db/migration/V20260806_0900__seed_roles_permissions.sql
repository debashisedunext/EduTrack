-- =====================================================================
-- B-001 · Seed — roles + the full permission matrix
--
-- Tables seeded: roles, permissions, role_permissions
-- Source: blueprint §2 (Role Model & Permission Matrix)
--
-- Six system roles, all is_system = 1 (non-deletable — S-09, blueprint
-- §2: "Admin | ❌ | ❌ ... Master data" only Admin manages roles at all,
-- and none of the six may be removed through the Role Master).
--
-- Permission codes are dotted capability strings consumed by @PreAuthorize
-- (A-033), one row per capability in the §2 matrix. A ✅(own) / "Limited"
-- / "Own perf." qualifier in the blueprint is a ROW-SCOPE concern, not a
-- permission-grant concern — CLAUDE.md's row-scoping rule puts that
-- filtering in ScopeResolver, server-side, never here. This table only
-- answers "can this role do the thing at all".
--
-- history.edit_delete is seeded with ZERO grants, deliberately. Blueprint
-- §2: "Edit / delete history or ribbon — ❌ (nobody can)". This is the
-- same append-only guarantee CLAUDE.md calls "the guarantee that erodes
-- first" for ticket_history / ticket_effort_logs / ticket_stage_transitions
-- — the permission exists so the Role & Permission Master (S-09) can
-- render it, greyed out, rather than silently omitting it.
-- =====================================================================


-- ---------------------------------------------------------------------
-- roles — the six system roles of blueprint §2.
-- ---------------------------------------------------------------------
INSERT INTO roles (code, name, description, is_system, is_active) VALUES
  ('ADMIN',        'Admin',        'Full system access: masters, roles, audit log, all tickets.', 1, 1),
  ('PM',           'PM',           'Owns projects; assigns, escalates and closes within them.',   1, 1),
  ('SUPPORT_DESK', 'Support Desk', 'Intake, assignment and closure within assigned projects.',     1, 1),
  ('DEVELOPER',    'Developer',    'Works assigned tickets through the ribbon.',                   1, 1),
  ('QA',           'QA',           'Verifies assigned tickets through the ribbon.',                1, 1),
  ('DEPLOYMENT',   'Deployment',   'Deploys assigned tickets through the ribbon.',                 1, 1);


-- ---------------------------------------------------------------------
-- permissions — one row per capability row in the §2 matrix (18 rows).
-- ---------------------------------------------------------------------
INSERT INTO permissions (code, name, category, description) VALUES
  ('resource.manage',      'Manage resources, roles, reporting manager', 'admin',    'Create/edit resources, roles and reporting-manager assignment.'),
  ('project.manage',       'Manage projects',                            'admin',    'Create/edit projects; map resources to a project.'),
  ('ticket.create',        'Create ticket',                              'ticket',   'Raise a new ticket.'),
  ('ticket.assign',        'Assign / reassign ticket',                   'ticket',   'Assign or reassign a ticket to a resource.'),
  ('ticket.handoff',       'Hand off to next stage',                     'ticket',   'Move a ticket forward along the ribbon.'),
  ('ticket.rework',        'Send back for rework',                       'ticket',   'Return a ticket to a prior stage for rework.'),
  ('ticket.skip_stage',    'Skip a stage',                               'ticket',   'Skip a ribbon stage, with a mandatory reason.'),
  ('ticket.force_move',    'Force-move ribbon backwards',                'ticket',   'Force-move the ribbon backwards outside the normal flow.'),
  ('ticket.view_all',      'View all tickets',                           'ticket',   'View tickets beyond only those assigned to the caller.'),
  ('ticket.view_assigned', 'View only assigned tickets',                 'ticket',   'View tickets assigned to the caller.'),
  ('ticket.update_progress', 'Update status/effort on assigned ticket',  'ticket',   'Update status or log effort on an assigned ticket.'),
  ('ticket.close',         'Close ticket',                               'ticket',   'Close a ticket.'),
  ('ticket.reopen',        'Reopen ticket',                              'ticket',   'Reopen a closed ticket.'),
  ('history.edit_delete',  'Edit / delete history or ribbon',            'history',  'Mutate ticket_history, ticket_effort_logs or ticket_stage_transitions. Granted to nobody — see header.'),
  ('history.view_team',    'View team member history',                  'history',  'View ticket history belonging to other team members.'),
  ('reports.view',         'Reports section',                            'reports',  'View the reports section, scope resolved per role.'),
  ('master.write',         'Master data',                                'master',   'Create/edit task types, SLA, workflow, holidays and other masters.'),
  ('audit.view',           'Audit log viewer',                           'audit',    'View the audit log.');


-- ---------------------------------------------------------------------
-- role_permissions — the §2 matrix, transcribed one grant per ✅ / ✅(own)
-- / Limited / Own perf. cell. "—" and "❌" cells are simply absent rows.
-- ---------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (
  SELECT 'ADMIN' AS role_code, 'resource.manage' AS permission_code
  UNION ALL SELECT 'ADMIN', 'project.manage'
  UNION ALL SELECT 'ADMIN', 'ticket.create'
  UNION ALL SELECT 'ADMIN', 'ticket.assign'
  UNION ALL SELECT 'ADMIN', 'ticket.handoff'
  UNION ALL SELECT 'ADMIN', 'ticket.rework'
  UNION ALL SELECT 'ADMIN', 'ticket.skip_stage'
  UNION ALL SELECT 'ADMIN', 'ticket.force_move'
  UNION ALL SELECT 'ADMIN', 'ticket.view_all'
  UNION ALL SELECT 'ADMIN', 'ticket.update_progress'
  UNION ALL SELECT 'ADMIN', 'ticket.close'
  UNION ALL SELECT 'ADMIN', 'ticket.reopen'
  UNION ALL SELECT 'ADMIN', 'history.view_team'
  UNION ALL SELECT 'ADMIN', 'reports.view'
  UNION ALL SELECT 'ADMIN', 'master.write'
  UNION ALL SELECT 'ADMIN', 'audit.view'

  UNION ALL SELECT 'PM', 'project.manage'
  UNION ALL SELECT 'PM', 'ticket.create'
  UNION ALL SELECT 'PM', 'ticket.assign'
  UNION ALL SELECT 'PM', 'ticket.handoff'
  UNION ALL SELECT 'PM', 'ticket.rework'
  UNION ALL SELECT 'PM', 'ticket.skip_stage'
  UNION ALL SELECT 'PM', 'ticket.force_move'
  UNION ALL SELECT 'PM', 'ticket.view_all'
  UNION ALL SELECT 'PM', 'ticket.update_progress'
  UNION ALL SELECT 'PM', 'ticket.close'
  UNION ALL SELECT 'PM', 'ticket.reopen'
  UNION ALL SELECT 'PM', 'history.view_team'
  UNION ALL SELECT 'PM', 'reports.view'

  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.create'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.assign'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.handoff'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.rework'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.view_all'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.update_progress'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.close'
  UNION ALL SELECT 'SUPPORT_DESK', 'ticket.reopen'
  UNION ALL SELECT 'SUPPORT_DESK', 'history.view_team'
  UNION ALL SELECT 'SUPPORT_DESK', 'reports.view'

  UNION ALL SELECT 'DEVELOPER', 'ticket.create'
  UNION ALL SELECT 'DEVELOPER', 'ticket.handoff'
  UNION ALL SELECT 'DEVELOPER', 'ticket.rework'
  UNION ALL SELECT 'DEVELOPER', 'ticket.view_assigned'
  UNION ALL SELECT 'DEVELOPER', 'ticket.update_progress'
  UNION ALL SELECT 'DEVELOPER', 'reports.view'

  UNION ALL SELECT 'QA', 'ticket.create'
  UNION ALL SELECT 'QA', 'ticket.handoff'
  UNION ALL SELECT 'QA', 'ticket.rework'
  UNION ALL SELECT 'QA', 'ticket.view_assigned'
  UNION ALL SELECT 'QA', 'ticket.update_progress'
  UNION ALL SELECT 'QA', 'reports.view'

  UNION ALL SELECT 'DEPLOYMENT', 'ticket.create'
  UNION ALL SELECT 'DEPLOYMENT', 'ticket.handoff'
  UNION ALL SELECT 'DEPLOYMENT', 'ticket.rework'
  UNION ALL SELECT 'DEPLOYMENT', 'ticket.view_assigned'
  UNION ALL SELECT 'DEPLOYMENT', 'ticket.update_progress'
  UNION ALL SELECT 'DEPLOYMENT', 'reports.view'
) grants
JOIN roles r ON r.code = grants.role_code
JOIN permissions p ON p.code = grants.permission_code;
