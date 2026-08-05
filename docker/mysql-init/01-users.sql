-- EduTrack — database users
--
-- Two users by design (PLAN.md §3.5, task A-010):
--   edutrack_app     runtime. NO DDL. INSERT+SELECT ONLY on the append-only
--                    tables, so even a compromised app cannot rewrite history.
--   edutrack_migrate Flyway only, used by the deploy step.
--
-- The per-table grants for the three append-only tables are applied by Stream A
-- in a migration, once those tables exist.

CREATE USER IF NOT EXISTS 'edutrack_migrate'@'%' IDENTIFIED BY 'edutrack_migrate';
GRANT ALL PRIVILEGES ON edutrack.* TO 'edutrack_migrate'@'%';

-- Runtime user: DML only, explicitly no DDL (no DROP, no ALTER, no CREATE).
-- Without DROP the app also cannot TRUNCATE, which is how it would otherwise
-- bypass the immutability triggers.
GRANT SELECT, INSERT, UPDATE, DELETE ON edutrack.* TO 'edutrack_app'@'%';

FLUSH PRIVILEGES;
