-- =====================================================================
-- B-011 · the five S-08 fields `users` has no column for.
--
-- Source: blueprint §7.4 S-08, the Resource Master create/edit form.
--
-- A-003 built `users` for authentication and scoping, and it carries
-- every field those two need. S-08 is a different reader: it is the
-- Admin's view of a person as an employee rather than as a principal,
-- and five of its fields have nowhere to go.
--
--   Personal · Date of joining, Profile photo
--   Org      · Location
--   Work     · Weekly off pattern, Skills/tags
--
-- Mobile, Department, Designation, Daily capacity and Time zone are
-- already there and are not touched.
--
-- FLAGGED FOR STREAM A. `users` is A-003's table and Stream A is the
-- schema arbiter. CLAUDE.md mandates A's review for `tickets` and the
-- three append-only tables and this is neither, but the identity table
-- is close enough to the security core that it should not change on a
-- Stream B branch unremarked. Five nullable columns, no existing column
-- altered, no index dropped, no constraint on an existing column
-- changed — additive in the sense that a rollback is five DROPs.
--
-- WHY WEEKLY_OFF IS PER-RESOURCE AND NULLABLE
--
-- B-023 already stores the org weekly-off pattern in
-- `working_calendar`, and its header says exactly this: "blueprint
-- §7.4's per-resource 'Weekly off pattern' is a field on the resource
-- record (S-08 / B-011), a narrower override that can land later
-- without changing this shape." This is that field, landing later.
--
-- NULL means "inherit the org calendar" and is the default for
-- everybody. It is NOT the same as `[]`, which means "this person has
-- no weekly off" — a real answer for a support rota, and one an empty
-- array is the only way to say. A NOT NULL column defaulting to the org
-- pattern would freeze today's org week into every row and stop
-- tracking a later change to it, which is the whole reason the org
-- pattern is stored once.
--
-- **B-024 does not read this column yet, and that is a known gap.**
-- `WorkingHoursService` resolves the weekly-off pattern from
-- `working_calendar` alone, so a resource with an override still has
-- their SLA computed against the org week. Wiring it in is a change to
-- a service every SLA figure routes through, and doing it in the same
-- change as a form would put a calculation defect and a screen behind
-- one review. Raised as a follow-up rather than left implicit.
--
-- ISO-8601 DAY NUMBERS, 1=Mon … 7=Sun.
--
-- The same CHECK as `ck_working_calendar_weekly_off`, for the same
-- reason and deliberately not a looser one. B-023's note records what a
-- second numbering cost: the contract said "ISO" while constraining to
-- 0–6 and the mock sent `[0, 6]`, which read by a backend using
-- `DayOfWeek` makes Sunday a working day and every weekend-spanning SLA
-- short by a day. A per-resource override storing a `0` would
-- reintroduce exactly that defect one row at a time, in the harder-to-
-- notice place. `maxItems: 6` for the same reason it is there: a
-- seven-day weekend sends `addWorkingHours` looking for a working day
-- it can never find.
--
-- SKILLS IS JSON, NOT A JOIN TABLE.
--
-- S-08 calls it "Skills/tags" and nothing in the blueprint reads it:
-- there is no skill-based routing, no skill report, no skill filter.
-- A `skills` + `user_skills` pair would be two tables, two masters and
-- a screen to maintain them, all to serve a text field somebody types
-- into. If skill-based auto-assignment is ever specified, normalising
-- then is a migration over data that exists; normalising now is a guess
-- about a feature nobody has asked for. The CHECK keeps it an array of
-- strings so that migration would have something regular to read.
-- =====================================================================

ALTER TABLE users
  -- Personal · S-08. DATE, not DATETIME(6): a joining date is a
  -- calendar fact with no instant attached, and giving it a time would
  -- invite the timezone shift B-023 hit with TIME.
  ADD COLUMN date_of_joining DATE          NULL AFTER mobile,

  -- Personal · S-08 "Profile photo". A URL, not a blob — the file lives
  -- wherever A-016's attachment storage puts it, and a 500-char column
  -- holds a signed S3 URL with room to spare. `UserRef.avatarUrl` in
  -- the contract has always promised this field; until now nothing
  -- could populate it and every response omitted it.
  ADD COLUMN avatar_url      VARCHAR(500)  NULL AFTER date_of_joining,

  -- Org · S-08. Free text, not an FK to an office master: no such
  -- master is specified, and inventing one to hold "Pune" would be a
  -- table nobody maintains.
  ADD COLUMN location        VARCHAR(120)  NULL AFTER designation,

  -- Work · S-08. NULL = inherit `working_calendar`. See the header.
  ADD COLUMN weekly_off      JSON          NULL AFTER daily_capacity_hrs,

  -- Work · S-08. See the header for why this is not two tables.
  ADD COLUMN skills          JSON          NULL AFTER weekly_off,

  ADD CONSTRAINT ck_users_weekly_off
    CHECK (weekly_off IS NULL
           OR JSON_SCHEMA_VALID(
             '{"type":"array",
               "items":{"type":"integer","minimum":1,"maximum":7},
               "uniqueItems":true,
               "maxItems":6}',
             weekly_off)),

  ADD CONSTRAINT ck_users_skills
    CHECK (skills IS NULL
           OR JSON_SCHEMA_VALID(
             '{"type":"array",
               "items":{"type":"string","minLength":1,"maxLength":60},
               "uniqueItems":true,
               "maxItems":30}',
             skills));


-- ---------------------------------------------------------------------
-- project_members.role_in_project — constrain the vocabulary.
--
-- A-003 created the column as a free VARCHAR(30) with the comment
-- "Per-project role assignment (S-07). May differ from users.role_id:
-- a Developer globally can be mapped as QA on one project." B-011 is
-- the first writer, so this is the moment the set of legal values gets
-- decided, and the moment to write it down before three spellings of
-- "developer" exist.
--
-- THE VOCABULARY IS NOT `RoleCode`, AND THE DIFFERENCE IS THE POINT.
--
-- Blueprint §7.4 S-10 lists the project roles as "PM / Dev / Support /
-- QA / Deploy / Viewer". Two of them do not line up with the six global
-- roles in §2:
--
--   VIEWER is a project role and not a global one. Read-only access to
--   one project's tickets is a per-project grant; there is no global
--   "viewer" in the permission matrix, and adding one would give
--   somebody read-only access to everything, which is the opposite of
--   what a project viewer is.
--
--   ADMIN is a global role and not a project one. An Admin already sees
--   every project by A-034's scope rule, so an `ADMIN` row here would
--   be a grant that changes nothing — and a grant that changes nothing
--   is one somebody will later assume does something.
--
-- Reusing the contract's `RoleCode` enum for this field would therefore
-- have been wrong in both directions at once. It is `ProjectRoleCode`
-- in the contract for exactly this reason.
--
-- NULL stays legal. Rows already exist (B-007's fixture corpus writes
-- memberships) and a membership with no stated project role means
-- "whatever their global role is", which is the common case and should
-- not be forced to restate itself.
-- ---------------------------------------------------------------------
ALTER TABLE project_members
  ADD CONSTRAINT ck_project_members_role
    CHECK (role_in_project IS NULL
           OR role_in_project IN ('PM', 'DEVELOPER', 'SUPPORT', 'QA', 'DEPLOYMENT', 'VIEWER'));
