-- EduTrack — database users
--
-- Two users by design (PLAN.md §3.5, task A-010):
--   edutrack_app     runtime. NO DDL. INSERT+SELECT ONLY on the append-only
--                    tables, so even a compromised app cannot rewrite history.
--   edutrack_migrate Flyway only, used by the deploy step.
--
-- WHY THIS FILE CANNOT FINISH THE JOB
--
-- MySQL's Docker entrypoint creates MYSQL_USER and grants it ALL PRIVILEGES
-- on MYSQL_DATABASE *before* any script in /docker-entrypoint-initdb.d runs.
-- Privileges in MySQL are cumulative and never subtractive, so a later
-- GRANT of a narrower set adds nothing and restricts nothing:
--
--     GRANT SELECT, INSERT, UPDATE, DELETE ON edutrack.*           -- db level
--     GRANT SELECT, INSERT              ON edutrack.ticket_history -- "narrower"
--     -> DELETE FROM ticket_history is still permitted
--
-- The only way to hold a table to fewer privileges is to grant NOTHING at
-- database level and grant table by table instead. Table-level grants need
-- the tables to exist (GRANT on a missing table is ERROR 1146), and the
-- tables do not exist until Flyway has run.
--
-- So this file does one thing: strip the entrypoint's blanket grant and
-- leave edutrack_app with no access at all. The real grants are applied
-- after migration by docker/grants/apply-app-grants.sql — `make grants`.
--
-- Failing closed is deliberate. An app that cannot read is a loud, obvious
-- failure; an app silently holding DROP is the hole this task exists to close.

CREATE USER IF NOT EXISTS 'edutrack_migrate'@'%' IDENTIFIED BY 'edutrack_migrate';

-- Migration user: full DDL on the one schema it owns, and nothing outside it.
-- Deliberately WITHOUT GRANT OPTION, so migrations cannot hand out privileges.
GRANT ALL PRIVILEGES ON edutrack.* TO 'edutrack_migrate'@'%';

-- Runtime user: reset to zero. Re-granted per table by `make grants` once the
-- schema exists. Without that step the application cannot connect to anything,
-- which is the intended fail-closed behaviour.
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'edutrack_app'@'%';

FLUSH PRIVILEGES;
