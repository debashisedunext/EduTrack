-- ---------------------------------------------------------------------------
-- OB-07 · a Module Service task names one person, and nothing else
--
-- Drops `owner_role` and `backup_owner_user_id` from
-- `ob_journey_template_steps`, leaving `owner_user_id` — the **implementor** —
-- as the single answer to "who does this task".
--
-- Why each one goes
-- -----------------
-- `owner_role` was the fallback for a service authored before anyone knew who
-- would do the work. It never worked as one: `ObJourneyInstantiationService`'s
-- own class javadoc records that it is *never consulted*, because there is no
-- per-client role→user resolver anywhere in the module and OB-08's
-- "Responsibility" admin was never built. So a task carrying only a role
-- instantiated onto nobody and landed on the Manager's unassigned list — a
-- column that looked like an answer and was an intention.
--
-- What replaces it is a person rather than a better intention: a task with no
-- `owner_user_id` now falls back at instantiation to the project's own
-- `ob_projects.implementor_user_id`, and to `ob_projects.created_by` after
-- that. A service is authored once and boarded for many projects, so the
-- project is where "who, for this client" is actually known.
--
-- `backup_owner_user_id` was leave coverage, which is a fact about a live
-- journey and not about a plan: who covers for somebody depends on who is on
-- leave in March, and a template has no March. The column **stays on
-- `ob_journey_steps`**, where it is set per client, read by the step lifecycle
-- and joined by every scope query in the module. Only the template's copy —
-- the seed value nobody was setting — goes.
--
-- Both columns are NULLable and neither is indexed, so this is a metadata-only
-- ALTER on a table that holds template definitions rather than client data.
-- The FK on the backup owner has to go first: MySQL 8.4 refuses to drop a
-- column an existing foreign key names.
--
-- Not reversible by re-adding the columns: the values go with them. That is
-- deliberate and they were not carrying anything — `owner_role` was seeded on
-- three demo rows and set by the designer's own picker, and nothing ever read
-- either one after instantiation.
-- ---------------------------------------------------------------------------

ALTER TABLE ob_journey_template_steps
  DROP FOREIGN KEY fk_ob_journey_template_steps_backup_owner;

ALTER TABLE ob_journey_template_steps
  DROP COLUMN backup_owner_user_id,
  DROP COLUMN owner_role;
