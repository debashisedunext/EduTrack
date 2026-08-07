-- =====================================================================
-- B-002 · Seed — 11 task types + 4 priorities
--
-- Tables seeded: task_types, priorities
-- Source: blueprint §4B.1 (priority dropdown), S-11/S-12 (masters),
--         §12.1 (colour tokens)
--
-- priorities.colour is transcribed exactly from §12.1's "Level chips":
-- Low #10B981 · Medium #3B82F6 · High #F59E0B · Critical #EF4444.
-- is_escalation_trigger = 1 on CRITICAL only — §6: "the level the SLA
-- engine escalates TO on breach".
--
-- Everything else below is NOT given a number or a name by the blueprint
-- and is designed here, same as A-007 had to design the columns for this
-- table in the first place:
--
--   - task_types.colour has no per-type mapping in the spec. Cycled from
--     the §12.1 "chart palette (colour-blind safe)" — the same eight
--     colours the Task Type Distribution and Priority Split dashboard
--     widgets already use, so a type's colour matches its own chart bar.
--   - task_types.icon: lucide-react names (frontend's icon library,
--     `frontend/package.json`). TaskTypeIcon is a bare nullable string in
--     the API contract — no enum to satisfy.
--   - default_level: the blueprint pins exactly two of eleven (Production
--     Bug -> High, Future Release -> Low, both in the A-007 migration
--     comment). The remaining nine are a judgement call, reviewed with
--     Ayush: bug/outage-shaped types skew High/Critical, request/cosmetic
--     types skew Low/Medium.
--   - default_sla_hours (both tables): no figure anywhere in the spec at
--     this granularity. The blueprint's only numeric SLAs are ribbon
--     STAGE SLAs (Triage 4h, QA 8h — a different table, B-004) and the
--     "4-hour SLA" working-calendar example, which is illustrative, not a
--     seeded value. Chosen to roughly halve going down each priority
--     step, consistent with the Critical/4h example already in the docs.
--
-- These are master-data seed values, not schema — Admin can edit every
-- one of them after go-live via S-11/S-12 without a release.
-- =====================================================================


-- ---------------------------------------------------------------------
-- task_types — S-11, seq preserves the blueprint's listed order.
-- ---------------------------------------------------------------------
INSERT INTO task_types (code, name, icon, colour, default_level, default_sla_hours, seq) VALUES
  ('CHANGE_REQUEST',     'Change Request',     'file-edit',       '#4F46E5', 'MEDIUM',   48,  10),
  ('PRODUCTION_BUG',     'Production Bug',     'flame',           '#06B6D4', 'HIGH',      8,  20),
  ('CLIENT_REQUEST',     'Client Request',     'message-square',  '#10B981', 'MEDIUM',   24,  30),
  ('FUTURE_RELEASE',     'Future Release',     'rocket',          '#F59E0B', 'LOW',     120,  40),
  ('INTERNAL_BUG',       'Internal Bug',       'bug',             '#EF4444', 'MEDIUM',   24,  50),
  ('CLIENT_BUG',         'Client Bug',         'alert-triangle',  '#8B5CF6', 'HIGH',     12,  60),
  ('SERVER_ISSUE',       'Server Issue',       'server-crash',    '#EC4899', 'CRITICAL',  4,  70),
  ('NETWORK_ISSUE',      'Network Issue',      'wifi-off',        '#14B8A6', 'HIGH',      8,  80),
  ('BROWSER_ISSUE',      'Browser Issue',      'monitor',         '#4F46E5', 'LOW',      48,  90),
  ('PERFORMANCE_ISSUE',  'Performance Issue',  'gauge',           '#06B6D4', 'MEDIUM',   24, 100),
  ('OTHER',              'Other',              'help-circle',     '#10B981', 'LOW',      48, 110);


-- ---------------------------------------------------------------------
-- priorities — S-12, blueprint §4B.1 + §12.1 (exact colours).
-- ---------------------------------------------------------------------
INSERT INTO priorities (code, name, colour, default_sla_hours, is_escalation_trigger, seq) VALUES
  ('LOW',      'Low',      '#10B981', 72, 0, 10),
  ('MEDIUM',   'Medium',   '#3B82F6', 24, 0, 20),
  ('HIGH',     'High',     '#F59E0B',  8, 0, 30),
  ('CRITICAL', 'Critical', '#EF4444',  4, 1, 40);
