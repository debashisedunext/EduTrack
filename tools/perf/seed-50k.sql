-- =====================================================================
-- A-073 · the 50,000-ticket load dataset
--
-- PLAN.md §6 M6 exit: "dashboard first paint under 1.5 s on a seeded
-- 50,000-ticket dataset". PLAN.md §8: "k6 — dashboard and ticket-list p95
-- on a 50,000-ticket dataset". Neither sentence is checkable without the
-- dataset, so this file is the first half of A-073 and everything else in
-- tools/perf/ reads from what it writes.
--
-- WHY THIS IS NOT B-007
--
-- B-007's corpus (api/feature/fixtures/) is 200 tickets walked through
-- their real workflow templates via WorkingHoursService, one @Transactional
-- boundary per ticket. That shape is the whole point of it — D's SLA
-- scanner and C's ribbon learn what real durations look like from it — and
-- it is exactly why it cannot be turned up to 50,000: ~15 ORM-inserted rows
-- per ticket across 50,000 separate transactions is hours of runtime for a
-- dataset nobody asserts behaviour against.
--
-- So this is a second corpus with a different job, and the two must not be
-- confused:
--
--   B-007      correctness fixture — real journeys, real working-calendar
--              durations, small. Assert behaviour against this.
--   this file  volume fixture — realistic *cardinality* on every column the
--              dashboard and ticket list filter, sort and scope by, at
--              50,000 rows. Assert *plans and latency* against this. Never
--              behaviour.
--
-- Every row it writes is titled "Perf corpus #N" so the distinction is
-- visible in any screen or query result rather than remembered.
--
-- WHY SQL AND NOT A JAVA SEEDER
--
-- A profile-gated ApplicationRunner is how B-007 stays out of production,
-- and it works. But it works by being a promise the jar carries. This is
-- set-based SQL that lives in tools/ and is never compiled, so a
-- 50,000-row generator cannot reach a real environment even by accident —
-- there is nothing to activate. It is also about two orders of magnitude
-- faster, which is what makes re-seeding cheap enough to do between index
-- experiments.
--
-- WHAT IS DELIBERATELY NOT REAL HERE
--
--   * Durations are wall-clock, not working-calendar. B-024's
--     WorkingHoursService is not reachable from SQL and re-implementing it
--     here would be a second, drifting copy of the rule CLAUDE.md says must
--     have one home. Nothing in tools/perf/ asserts an SLA outcome, so the
--     cost is confined to this file — but it is the reason no SLA test may
--     ever point at this corpus.
--   * Hash-chain columns are untouched: this writes no ticket_history,
--     ticket_effort_logs or ticket_stage_transitions rows at all. A-040's
--     chain is per-ticket and computed in Java; 50,000 forged chains would
--     be 50,000 rows A-044's verifier then reports as broken every night.
--     The append-only tables stay at B-007's scale, which is the right
--     scale for them — they are not what the dashboard or the list reads.
--   * The corpus adds no clients, users, projects, task types or workflow
--     templates. It layers on B-007's reference data and fails loudly
--     without it, so there is exactly one definition of "the fixture
--     company" and both corpora describe the same one.
--
-- HOW THE MAPPINGS ARE DERIVED RATHER THAN RESTATED
--
-- task type -> workflow template is read out of the tickets B-007 already
-- wrote (SELECT DISTINCT task_type_id, workflow_template_id), not copied
-- from ReferenceDataFixture.TEMPLATE_BY_TASK_TYPE. A copy is a second
-- source of truth that goes stale silently the first time B changes the
-- mapping; deriving it means this corpus agrees with that one by
-- construction. The same argument the fixtures README makes about
-- TicketCodeAllocator duplicating C-011 being a cost worth naming.
--
-- DETERMINISTIC, NOT RANDOM
--
-- Every draw is CRC32 of a labelled string and the row number — never
-- RAND(). Two runs against a fresh database produce identical rows, which
-- is what lets an index experiment be compared against the run before it.
-- B-007 fixes its Random seed for the same reason.
--
-- RUN IT
--
--   docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack < tools/perf/seed-50k.sql
--
-- THEN RUN reset-stats.sql. This is not optional and it is not tidiness.
-- This file writes tickets and deliberately touches none of A-050's three
-- summary tables, which is what the dashboard actually reads — so until
-- they are cleared and rebuilt, the dashboard serves figures computed for
-- B-007's 200 tickets while appearing entirely normal. A-051's worker
-- cannot recover from it on its own; reset-stats.sql explains exactly why
-- and is the repair its own javadoc prescribes.
--
-- Idempotent: it tops the table up to the target and is a no-op once
-- reached. To start over, drop the schema and re-run Flyway plus the
-- fixtures profile — this file never deletes anything, because a DELETE
-- here would be one typo away from a DELETE on a real tickets table.
-- =====================================================================

SET SESSION cte_max_recursion_depth = 200000;

DROP PROCEDURE IF EXISTS perf_seed_50k;

DELIMITER $$

CREATE PROCEDURE perf_seed_50k(IN target_total INT, IN batch_size INT)
BEGIN
    DECLARE existing        INT;
    DECLARE to_generate     INT;
    DECLARE done_so_far     INT DEFAULT 0;
    DECLARE this_batch      INT;

    -- ── guard: B-007's reference data, or nothing ────────────────────
    --
    -- Every ticket below points at a project, a member, a task type and a
    -- template that this corpus does not create. Without them the INSERT
    -- would fail on a foreign key several thousand rows in, having already
    -- committed earlier batches. Refusing up front is the difference
    -- between "nothing happened" and "the table is half seeded".
    IF NOT EXISTS (SELECT 1 FROM projects WHERE project_code = 'CRM') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
            'B-007 reference data is missing. Run the API once with the local,fixtures profiles first.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM tickets WHERE task_type_id IS NOT NULL
                                           AND workflow_template_id IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT =
            'No existing ticket carries both a task type and a workflow template, so the type-to-template mapping cannot be derived. Load the B-007 corpus first.';
    END IF;

    SELECT COUNT(*) INTO existing FROM tickets;
    SET to_generate = target_total - existing;

    IF to_generate <= 0 THEN
        SELECT CONCAT('Already at ', existing, ' tickets — nothing to do.') AS result;
    ELSE

    -- ── reference tables, each with a dense 0-based ordinal ──────────
    --
    -- Dense ordinals are what let the big INSERT pick a project, an
    -- assignee, a client and a stage with a modulo instead of a
    -- correlated subquery per row. At 50,000 rows the difference is
    -- seconds versus minutes.
    DROP TEMPORARY TABLE IF EXISTS perf_ref_project;
    CREATE TEMPORARY TABLE perf_ref_project (
        ord           INT NOT NULL PRIMARY KEY,
        project_id    BIGINT NOT NULL,
        project_code  VARCHAR(10) NOT NULL,
        base_seq      BIGINT NOT NULL,
        manager_id    BIGINT NULL,
        member_count  INT NOT NULL,
        client_count  INT NOT NULL
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_project (ord, project_id, project_code, base_seq, manager_id, member_count, client_count)
    SELECT ROW_NUMBER() OVER (ORDER BY p.id) - 1,
           p.id,
           p.project_code,
           p.ticket_seq,
           p.manager_id,
           (SELECT COUNT(*) FROM project_members pm WHERE pm.project_id = p.id),
           (SELECT COUNT(*) FROM client_projects cp WHERE cp.project_id = p.id)
      FROM projects p
     WHERE EXISTS (SELECT 1 FROM project_members pm WHERE pm.project_id = p.id);

    DROP TEMPORARY TABLE IF EXISTS perf_ref_member;
    CREATE TEMPORARY TABLE perf_ref_member (
        project_id BIGINT NOT NULL,
        ord        INT NOT NULL,
        user_id    BIGINT NOT NULL,
        PRIMARY KEY (project_id, ord)
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_member (project_id, ord, user_id)
    SELECT pm.project_id,
           ROW_NUMBER() OVER (PARTITION BY pm.project_id ORDER BY pm.user_id) - 1,
           pm.user_id
      FROM project_members pm;

    -- perf_ref_member is joined twice in the INSERT below — once for the
    -- assignee and once for the reporter — and MySQL refuses to open one
    -- TEMPORARY table twice in a statement. A second table with the same
    -- contents is the documented way round it, and cheap at 18 rows.
    DROP TEMPORARY TABLE IF EXISTS perf_ref_reporter;
    CREATE TEMPORARY TABLE perf_ref_reporter (
        project_id BIGINT NOT NULL,
        ord        INT NOT NULL,
        user_id    BIGINT NOT NULL,
        PRIMARY KEY (project_id, ord)
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_reporter (project_id, ord, user_id)
    SELECT project_id, ord, user_id FROM perf_ref_member;

    -- A client is reachable from a ticket only through client_projects —
    -- the same path B-007 uses. Its primary contact comes along, because
    -- tickets.client_contact_id has a foreign key of its own and a
    -- contact belonging to a different client would be referentially
    -- valid and factually wrong.
    DROP TEMPORARY TABLE IF EXISTS perf_ref_client;
    CREATE TEMPORARY TABLE perf_ref_client (
        project_id BIGINT NOT NULL,
        ord        INT NOT NULL,
        client_id  BIGINT NOT NULL,
        contact_id BIGINT NULL,
        PRIMARY KEY (project_id, ord)
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_client (project_id, ord, client_id, contact_id)
    SELECT cp.project_id,
           ROW_NUMBER() OVER (PARTITION BY cp.project_id ORDER BY cp.client_id) - 1,
           cp.client_id,
           (SELECT cc.id FROM client_contacts cc
             WHERE cc.client_id = cp.client_id
             ORDER BY cc.is_primary DESC, cc.id LIMIT 1)
      FROM client_projects cp;

    -- Derived from B-007's own tickets, not restated from Java. See the
    -- file header.
    DROP TEMPORARY TABLE IF EXISTS perf_ref_type;
    CREATE TEMPORARY TABLE perf_ref_type (
        ord          INT NOT NULL PRIMARY KEY,
        task_type_id INT NOT NULL,
        template_id  BIGINT NOT NULL,
        type_name    VARCHAR(80) NOT NULL
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_type (ord, task_type_id, template_id, type_name)
    SELECT ROW_NUMBER() OVER (ORDER BY m.task_type_id) - 1, m.task_type_id, m.template_id, tt.name
      FROM (SELECT task_type_id, MIN(workflow_template_id) AS template_id
              FROM tickets
             WHERE task_type_id IS NOT NULL AND workflow_template_id IS NOT NULL
             GROUP BY task_type_id) m
      JOIN task_types tt ON tt.id = m.task_type_id;

    -- The stages an OPEN ticket can sit in: neither INTAKE (which is
    -- where a NEW ticket goes, handled separately) nor CLOSED (which is
    -- where a closed one goes). A stage drawn from the wrong template
    -- would render a ribbon highlighting a stage the template does not
    -- contain — the kind of nonsense that gets noticed months later and
    -- blamed on the ribbon.
    DROP TEMPORARY TABLE IF EXISTS perf_ref_stage;
    CREATE TEMPORARY TABLE perf_ref_stage (
        template_id BIGINT NOT NULL,
        ord         INT NOT NULL,
        stage_code  VARCHAR(20) NOT NULL,
        PRIMARY KEY (template_id, ord)
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_stage (template_id, ord, stage_code)
    SELECT ws.template_id,
           ROW_NUMBER() OVER (PARTITION BY ws.template_id ORDER BY ws.seq) - 1,
           ws.stage_code
      FROM workflow_stages ws
     WHERE ws.stage_code NOT IN ('INTAKE', 'CLOSED');

    DROP TEMPORARY TABLE IF EXISTS perf_ref_template;
    CREATE TEMPORARY TABLE perf_ref_template (
        template_id     BIGINT NOT NULL PRIMARY KEY,
        mid_stage_count INT NOT NULL
    ) ENGINE = InnoDB;

    INSERT INTO perf_ref_template (template_id, mid_stage_count)
    SELECT template_id, COUNT(*) FROM perf_ref_stage GROUP BY template_id;

    SET @project_count := (SELECT COUNT(*) FROM perf_ref_project);
    SET @type_count    := (SELECT COUNT(*) FROM perf_ref_type);

    -- ── generate, in batches ─────────────────────────────────────────
    --
    -- One 49,800-row INSERT would work and would hold a single
    -- transaction and undo log for its whole duration. Batching keeps
    -- each commit small and, more usefully, makes an interrupted run
    -- resumable: the procedure tops up to the target, so re-running it
    -- after a Ctrl-C continues rather than duplicates.
    WHILE done_so_far < to_generate DO
        SET this_batch = LEAST(batch_size, to_generate - done_so_far);
        SET @from_n = done_so_far + 1;
        SET @to_n   = done_so_far + this_batch;

        INSERT INTO tickets (
            ticket_code, project_id, title, description, task_type_id,
            level, original_level, status, environment,
            date_reported, reported_by, assigned_to, assigned_by,
            estimated_effort_hrs, total_effort_hrs, pct_complete,
            planned_close_date, actual_close_date,
            is_reopened, reopen_count, current_cycle_no,
            is_delayed, delayed_since,
            workflow_template_id, current_stage, current_iteration, rework_count,
            stage_entered_at, created_at, updated_at,
            client_id, client_contact_id, is_client_raised)
        WITH RECURSIVE
        seq (n) AS (
            SELECT @from_n
             UNION ALL
            SELECT n + 1 FROM seq WHERE n < @to_n
        ),
        -- Level 1: the raw draws. Every one is CRC32 over a *labelled*
        -- string, so the streams are independent — CRC32('lvl:7') and
        -- CRC32('day:7') are unrelated, where n % 4 and n % 100 are not.
        draw AS (
            SELECT s.n,
                   (s.n - 1) % @project_count                           AS proj_ord,
                   FLOOR((s.n - 1) / @project_count) + 1                AS idx_in_project,
                   CRC32(CONCAT('min:', s.n)) % 540                     AS minute_of_day,
                   CRC32(CONCAT('lvl:', s.n)) % 100                     AS r_level,
                   CRC32(CONCAT('opn:', s.n)) % 1000                    AS r_open,
                   CRC32(CONCAT('day:', s.n)) % 548                     AS age_if_closed,
                   CRC32(CONCAT('rec:', s.n)) % 100                     AS r_recency,
                   CRC32(CONCAT('new:', s.n)) % 21                      AS age_if_recent,
                   CRC32(CONCAT('agd:', s.n)) % 220                     AS age_if_aged,
                   CRC32(CONCAT('dur:', s.n)) % 100                     AS r_duration,
                   CRC32(CONCAT('sts:', s.n)) % 100                     AS r_status,
                   CRC32(CONCAT('rop:', s.n)) % 100                     AS r_reopen,
                   CRC32(CONCAT('cli:', s.n)) % 100                     AS r_client,
                   CRC32(CONCAT('env:', s.n)) % 100                     AS r_env,
                   CRC32(CONCAT('eff:', s.n)) % 40                      AS r_effort,
                   CRC32(CONCAT('typ:', s.n))                           AS r_type,
                   CRC32(CONCAT('asg:', s.n))                           AS r_assignee,
                   CRC32(CONCAT('rep:', s.n))                           AS r_reporter,
                   CRC32(CONCAT('cln:', s.n))                           AS r_clientpick,
                   CRC32(CONCAT('stg:', s.n))                           AS r_stage
              FROM seq s
        ),
        -- Level 2: the facts those draws decide, computed once so the
        -- INSERT's select list can use them instead of repeating the
        -- expressions five times each.
        -- Level 2: state first, THEN an age drawn from the distribution
        -- that state actually has.
        --
        -- The first version of this file drew one age uniformly across
        -- eighteen months and decided open-or-closed from it. That put
        -- 21,275 of 50,000 rows in `is_delayed`, and the reason is worth
        -- keeping: planned_close_date is reported_at + the level's SLA
        -- hours (8/24/72/120 — B-007's convention, and the same one
        -- SingleTicketFixture uses), so an open ticket more than a few
        -- days old is late *by definition*. A uniform age therefore does
        -- not produce a slightly-too-high delay rate, it produces a
        -- backlog in which being late is the normal state.
        --
        -- Real backlogs are not shaped like that: closed work spreads
        -- across the whole history, open work clusters in the last few
        -- weeks with a thin tail of genuinely stuck tickets. Two
        -- populations, two age distributions.
        --
        -- The delay rate that falls out is still high — most of the open
        -- set breaches — and that is left alone rather than tuned away.
        -- It is a consequence of the SLA convention, not a drawn number,
        -- and a corpus with no breaches in it is no use to the queries
        -- that go looking for them.
        fact AS (
            SELECT d.*,
                   CASE WHEN d.r_open >= 120     THEN d.age_if_closed
                        WHEN d.r_recency < 85    THEN d.age_if_recent
                        ELSE 21 + d.age_if_aged END                     AS age_days,
                   CASE WHEN d.r_level < 30 THEN 'LOW'
                        WHEN d.r_level < 70 THEN 'MEDIUM'
                        WHEN d.r_level < 90 THEN 'HIGH'
                        ELSE 'CRITICAL' END                             AS lvl,
                   (d.r_open >= 120)                                    AS is_closed
              FROM draw d
        ),
        stamped AS (
            SELECT f.*,
                   TIMESTAMPADD(MINUTE, 540 + f.minute_of_day,
                       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL f.age_days DAY))) AS reported_at
              FROM fact f
        ),
        -- Level 3: the two values that are functions of the level and the
        -- reported date. planned_close_date decides is_delayed and
        -- delayed_since as well, and computing it three times in the
        -- select list is how those three drift apart later.
        row_plan AS (
            SELECT f.*,
                   TIMESTAMPADD(HOUR, CASE f.lvl WHEN 'CRITICAL' THEN 8
                                                 WHEN 'HIGH'     THEN 24
                                                 WHEN 'MEDIUM'   THEN 72
                                                 ELSE 120 END, f.reported_at) AS planned_close,
                   -- Most closed tickets went quickly; ~15% dragged for
                   -- weeks. The tail is what puts anything in
                   -- daily_ticket_stats' aging_8_30 and aging_31_plus
                   -- buckets — without it widget 12 renders two bars and
                   -- two empty ones at every scale, which looks like a
                   -- broken chart rather than a fast team.
                   --
                   -- Capped at now, because the long tail applied to a
                   -- ticket raised yesterday would close it next month:
                   -- a CLOSED row with a close date in the future, which
                   -- every "closed in this period" query would then
                   -- disagree about depending on when it ran.
                   CASE WHEN NOT f.is_closed  THEN NULL
                        WHEN f.r_duration < 85
                             THEN LEAST(TIMESTAMPADD(HOUR, 2 + (f.r_effort * 3), f.reported_at), NOW(6))
                        ELSE LEAST(TIMESTAMPADD(DAY, 5 + (f.r_duration % 35), f.reported_at), NOW(6))
                        END                                             AS actual_close
              FROM stamped f
        )
        SELECT
            CONCAT(p.project_code, '-26-', LPAD(p.base_seq + f.idx_in_project, 5, '0')),
            p.project_id,
            CONCAT('Perf corpus #', f.n, ' — ', t.type_name),
            CONCAT('Synthetic row written by tools/perf/seed-50k.sql for A-073. ',
                   'Not a real ticket and not behaviourally meaningful. ',
                   'Raised against ', p.project_code, ' as a ', t.type_name, '.'),
            t.task_type_id,
            f.lvl,
            f.lvl,
            CASE WHEN f.is_closed             THEN 'CLOSED'
                 WHEN f.r_status < 22         THEN 'NEW'
                 WHEN f.r_status < 62         THEN 'IN_PROGRESS'
                 WHEN f.r_status < 74         THEN 'ON_HOLD'
                 WHEN f.r_status < 86         THEN 'AWAITING_INFO'
                 ELSE 'RESOLVED' END,
            CASE WHEN f.r_env < 55 THEN 'PRODUCTION'
                 WHEN f.r_env < 80 THEN 'STAGING'
                 ELSE 'DEVELOPMENT' END,
            f.reported_at,
            rep.user_id,
            -- ~8% unassigned, so the list's `unassigned` filter and D's
            -- unassigned-ticket alert both have something to find.
            CASE WHEN f.r_assignee % 100 < 8 THEN NULL ELSE asg.user_id END,
            CASE WHEN f.r_assignee % 100 < 8 THEN NULL ELSE p.manager_id END,
            ROUND(1 + f.r_effort / 4, 2),
            CASE WHEN f.is_closed THEN ROUND(1 + f.r_effort / 3, 2) ELSE ROUND(f.r_effort / 8, 2) END,
            CASE WHEN f.is_closed THEN 100 ELSE LEAST(95, (f.r_status % 20) * 5) END,
            -- Wall-clock, not working-calendar. Named in the header; the
            -- reason no SLA assertion may point here.
            f.planned_close,
            f.actual_close,
            CASE WHEN NOT f.is_closed AND f.r_reopen < 9 THEN 1 ELSE 0 END,
            CASE WHEN NOT f.is_closed AND f.r_reopen < 9 THEN 1 ELSE 0 END,
            CASE WHEN NOT f.is_closed AND f.r_reopen < 9 THEN 2 ELSE 1 END,
            -- is_delayed is derived, never drawn: a ticket is late when it
            -- is open and its planned close is behind us. Drawing it
            -- independently would put rows in the grid flagged late with a
            -- due date next week, and the first person to notice would
            -- rightly stop trusting the flag.
            CASE WHEN NOT f.is_closed AND f.planned_close < NOW(6) THEN 1 ELSE 0 END,
            CASE WHEN NOT f.is_closed AND f.planned_close < NOW(6) THEN f.planned_close END,
            t.template_id,
            CASE WHEN f.is_closed        THEN 'CLOSED'
                 WHEN f.r_status < 22    THEN 'INTAKE'
                 ELSE COALESCE(st.stage_code, 'INTAKE') END,
            1,
            0,
            f.reported_at,
            f.reported_at,
            f.reported_at,
            cl.client_id,
            cl.contact_id,
            CASE WHEN cl.client_id IS NULL THEN 0 ELSE 1 END
          FROM row_plan f
          JOIN perf_ref_project  p   ON p.ord = f.proj_ord
          JOIN perf_ref_type     t   ON t.ord = f.r_type % @type_count
          JOIN perf_ref_member   asg ON asg.project_id = p.project_id
                                    AND asg.ord = f.r_assignee % p.member_count
          JOIN perf_ref_reporter rep ON rep.project_id = p.project_id
                                    AND rep.ord = f.r_reporter % p.member_count
          LEFT JOIN perf_ref_template tp ON tp.template_id = t.template_id
          LEFT JOIN perf_ref_stage    st ON st.template_id = t.template_id
                                        AND st.ord = f.r_stage % NULLIF(tp.mid_stage_count, 0)
          -- ~40% client-attributed, matching B-007's proportion so the
          -- client widgets and the client-raised filter see the same shape
          -- at both scales.
          LEFT JOIN perf_ref_client   cl ON cl.project_id = p.project_id
                                        AND f.r_client < 40
                                        AND cl.ord = f.r_clientpick % NULLIF(p.client_count, 0);

        SET done_so_far = done_so_far + this_batch;
    END WHILE;

    -- The sequence has to move with the corpus. Leaving it behind would
    -- hand the next real ticket a code this corpus already used, and
    -- uq_tickets_code would reject it — a confusing failure a long way
    -- from its cause. Set from the codes actually present rather than by
    -- adding a count, so a re-run after a partial seed still lands right.
    UPDATE projects p
       SET p.ticket_seq = GREATEST(p.ticket_seq, COALESCE(
               (SELECT MAX(CAST(SUBSTRING_INDEX(t2.ticket_code, '-', -1) AS UNSIGNED))
                  FROM tickets t2 WHERE t2.project_id = p.id), 0));

    DROP TEMPORARY TABLE IF EXISTS perf_ref_project;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_member;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_reporter;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_client;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_type;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_stage;
    DROP TEMPORARY TABLE IF EXISTS perf_ref_template;

    SELECT CONCAT('Seeded ', to_generate, ' tickets — ', target_total, ' total.') AS result;

    END IF;
END$$

DELIMITER ;

CALL perf_seed_50k(50000, 5000);

DROP PROCEDURE perf_seed_50k;
