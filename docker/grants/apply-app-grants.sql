-- =====================================================================
-- A-010 · Runtime grants for edutrack_app
--
-- Run AFTER Flyway, as a user holding GRANT OPTION (root locally, a DBA
-- role in deployment):
--
--     make grants
--     # or
--     docker exec -i edutrack-mysql mysql -uroot -prootpw \
--       < docker/grants/apply-app-grants.sql
--
-- WHY THIS IS NOT A FLYWAY MIGRATION
--
--   1. GRANT on a table that does not exist fails with ERROR 1146, so this
--      cannot run as part of the migration that creates the tables.
--   2. edutrack_migrate has no GRANT OPTION, so it cannot execute GRANT at
--      all — deliberately, per docker/mysql-init/01-users.sql.
--   3. Grants are server state, not schema state. A Testcontainers run
--      creating a throwaway schema has no edutrack_app to grant to, and a
--      migration attempting it would fail the build.
--
--   PLAN.md §3.5 already frames this correctly: "Flyway migrations run
--   under a separate migration user in a deploy step." Grants belong to
--   that deploy step, alongside A-075's runbook.
--
-- WHY IT IS GENERATED RATHER THAN A FIXED LIST
--
-- The table set grows with every migration. A hardcoded list means the
-- first table someone adds is silently ungranted, and the failure appears
-- in production as `command denied` on a code path nobody tested. The
-- cursor below reads information_schema, so new tables are covered the
-- next time this runs.
--
-- THIS IS LAYER 3 OF THE FOUR-LAYER IMMUTABILITY GUARANTEE
--   layer 1  no update()/delete() method on the service       (A-040)
--   layer 2  no PUT/PATCH/DELETE route registered             (A-040)
--   layer 3  these grants                                     (A-010)
--   layer 4  the BEFORE UPDATE/DELETE triggers                (A-008)
--
-- Layer 3 is what stops TRUNCATE. Triggers do not fire on TRUNCATE, and
-- TRUNCATE requires DROP — which edutrack_app must therefore never hold.
-- =====================================================================

DELIMITER $$

DROP PROCEDURE IF EXISTS edutrack.apply_app_grants $$

CREATE PROCEDURE edutrack.apply_app_grants()
SQL SECURITY INVOKER          -- runs with the caller's privileges, not the definer's
BEGIN
  DECLARE v_done  INT DEFAULT 0;
  DECLARE v_table VARCHAR(64);
  DECLARE v_sql   TEXT;

  DECLARE cur CURSOR FOR
    SELECT table_name
      FROM information_schema.tables
     WHERE table_schema = 'edutrack'
       AND table_type   = 'BASE TABLE'
     ORDER BY table_name;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  -- Reset to zero before rebuilding. Plain `REVOKE ... ON edutrack.*` would
  -- only drop the database-level row and leave stale table-level grants
  -- behind in mysql.tables_priv; this form clears everything.
  REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'edutrack_app'@'%';

  OPEN cur;
  grant_loop: LOOP
    FETCH cur INTO v_table;
    IF v_done THEN LEAVE grant_loop; END IF;

    IF v_table IN ('ticket_history', 'ticket_effort_logs') THEN
      -- Fully append-only. No UPDATE, no DELETE, for any reason. A
      -- correction is a new row (A-043).
      SET v_sql = CONCAT('GRANT SELECT, INSERT ON edutrack.`', v_table,
                         '` TO ''edutrack_app''@''%''');

    ELSEIF v_table = 'ticket_stage_transitions' THEN
      -- UPDATE is required here and only here: sealing a stage sets
      -- exited_at, duration_mins and is_current (PLAN.md §3.6). The grant
      -- cannot express "only those three columns" — that is what the
      -- trg_stage_seal_only trigger is for. Layer 3 permits the statement;
      -- layer 4 constrains it.
      SET v_sql = CONCAT('GRANT SELECT, INSERT, UPDATE ON edutrack.`', v_table,
                         '` TO ''edutrack_app''@''%''');

    ELSEIF v_table = 'flyway_schema_history' THEN
      -- Readable for diagnostics; never written by the application.
      SET v_sql = CONCAT('GRANT SELECT ON edutrack.`', v_table,
                         '` TO ''edutrack_app''@''%''');

    ELSE
      -- Everything else: ordinary DML, never DDL. No DROP means no
      -- TRUNCATE, which is what keeps the trigger layer from being
      -- side-stepped.
      SET v_sql = CONCAT('GRANT SELECT, INSERT, UPDATE, DELETE ON edutrack.`', v_table,
                         '` TO ''edutrack_app''@''%''');
    END IF;

    SET @grant_stmt = v_sql;
    PREPARE stmt FROM @grant_stmt;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END LOOP;
  CLOSE cur;

  FLUSH PRIVILEGES;
END$$

DELIMITER ;

CALL edutrack.apply_app_grants();

-- The procedure is left in place so a later migration can re-apply grants
-- without re-reading this file. Re-running it is safe and idempotent.

SELECT CONCAT('edutrack_app now holds grants on ', COUNT(DISTINCT table_name), ' tables')
       AS result
  FROM mysql.tables_priv
 WHERE db = 'edutrack' AND user = 'edutrack_app';
