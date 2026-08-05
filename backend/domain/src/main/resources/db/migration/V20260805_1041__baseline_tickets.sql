-- =====================================================================
-- A-004 · Flyway baseline 2/5 — tickets
--
-- Tables: tickets, ticket_cycles, ticket_history, ticket_effort_logs,
--         ticket_watchers, ticket_links
--
-- Source:  blueprint §8.2, plus the §4A.5 ALTERs folded into the CREATEs
-- Applied: PLAN.md §3.1 translation; §3.7 hash chaining
--
-- TWO OF THESE TABLES ARE APPEND-ONLY AND HASH-CHAINED — ticket_history
-- and ticket_effort_logs. No service method, HTTP route, DB grant or
-- trigger may ever permit UPDATE or DELETE on them (CLAUDE.md). The
-- triggers enforcing that are A-008; the grants are A-010. This file
-- only creates the shape.
--
-- FORWARD REFERENCES. The blueprint adds columns to `tickets` by ALTER in
-- later sections, against tables that do not exist yet. Rather than churn
-- an applied migration, the columns are declared here and the foreign key
-- is added by the migration that creates the target:
--     task_type_id         -> FK added in A-007 (task_types)
--     workflow_template_id -> FK added in A-005 (workflow_templates)
--     client_id, client_contact_id and the §4B.7 counters -> A-006
-- =====================================================================


-- ---------------------------------------------------------------------
-- tickets — blueprint §8.2 plus the §4A.5 ribbon pointers.
--
-- level vs original_level: `level` is current and may be raised by the
-- escalation engine; `original_level` is what it was raised at and never
-- changes. A-070's "born critical vs became critical" report is the whole
-- reason to keep both, and G-4 (PLAN.md §5) decides whether `level`
-- reverts on close — the recommendation is no.
--
-- current_cycle_no and current_iteration are the TWO INDEPENDENT COUNTERS
-- of §4A.2, the most misread concept in the spec:
--     iteration increments when a ticket moves BACKWARDS within a cycle
--     cycle     increments when a ticket is REOPENED after closure
--
-- pcd_open (the generated column replacing PostgreSQL's partial index,
-- PLAN.md §3.3) and the FULLTEXT index are deliberately NOT here — they
-- are A-009, so the index work lands in one reviewable place.
-- ---------------------------------------------------------------------
CREATE TABLE tickets (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_code           VARCHAR(30)   NOT NULL,             -- CRM-26-00347
  project_id            BIGINT        NOT NULL,
  title                 VARCHAR(200)  NOT NULL,
  description           TEXT          NULL,
  task_type_id          INT           NULL,                 -- FK in A-007
  level                 VARCHAR(10)   NOT NULL,             -- LOW|MEDIUM|HIGH|CRITICAL
  original_level        VARCHAR(10)   NOT NULL,             -- never mutated
  status                VARCHAR(20)   NOT NULL DEFAULT 'NEW',
  environment           VARCHAR(20)   NULL,
  date_reported         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reported_by           BIGINT        NULL,
  assigned_to           BIGINT        NULL,
  assigned_by           BIGINT        NULL,
  estimated_effort_hrs  DECIMAL(6,2)  NULL,
  total_effort_hrs      DECIMAL(8,2)  NOT NULL DEFAULT 0,   -- Σ across all cycles
  planned_close_date    DATETIME(6)   NULL,
  actual_close_date     DATETIME(6)   NULL,
  is_reopened           TINYINT(1)    NOT NULL DEFAULT 0,
  reopen_count          SMALLINT      NOT NULL DEFAULT 0,
  current_cycle_no      SMALLINT      NOT NULL DEFAULT 1,
  is_delayed            TINYINT(1)    NOT NULL DEFAULT 0,
  delayed_since         DATETIME(6)   NULL,
  -- Ribbon pointers — blueprint §4A.5
  workflow_template_id  BIGINT        NULL,                 -- FK in A-005
  current_stage         VARCHAR(20)   NULL DEFAULT 'INTAKE',
  current_iteration     SMALLINT      NOT NULL DEFAULT 1,
  rework_count          SMALLINT      NOT NULL DEFAULT 0,
  stage_entered_at      DATETIME(6)   NULL,
  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_tickets_code (ticket_code),
  KEY ix_tickets_assignee_status (assigned_to, status),
  KEY ix_tickets_project_status (project_id, status),
  KEY ix_tickets_status (status),
  KEY ix_tickets_current_stage (current_stage),
  KEY ix_tickets_reported_by (reported_by),
  KEY ix_tickets_assigned_by (assigned_by),
  KEY ix_tickets_task_type (task_type_id),
  KEY ix_tickets_workflow_template (workflow_template_id),
  CONSTRAINT fk_tickets_project
    FOREIGN KEY (project_id)  REFERENCES projects (id),
  CONSTRAINT fk_tickets_reported_by
    FOREIGN KEY (reported_by) REFERENCES users (id),
  CONSTRAINT fk_tickets_assigned_to
    FOREIGN KEY (assigned_to) REFERENCES users (id),
  CONSTRAINT fk_tickets_assigned_by
    FOREIGN KEY (assigned_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_cycles — one row per life of the ticket (§4).
--
-- Cycle 1 is created with the ticket. Each reopen seals the previous
-- cycle (is_sealed = 1) and opens the next. Effort is attributed per
-- cycle, which is what lets the §4A.4 grid report "24.5 h this cycle,
-- 38.0 h across all cycles" — the walkthrough number M4 must reconcile to.
--
-- Mutable by design: a cycle accumulates effort and is sealed on close.
-- The append-only guarantee lives on the three tables below, not here.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_cycles (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id           BIGINT        NOT NULL,
  cycle_no            SMALLINT      NOT NULL,
  start_date          DATETIME(6)   NOT NULL,
  assigned_to         BIGINT        NULL,
  assigned_by         BIGINT        NULL,
  level               VARCHAR(10)   NULL,
  planned_close_date  DATETIME(6)   NULL,
  actual_close_date   DATETIME(6)   NULL,
  effort_hrs          DECIMAL(8,2)  NOT NULL DEFAULT 0,
  reopen_reason       TEXT          NULL,   -- why THIS cycle was opened
  resolution_summary  TEXT          NULL,   -- how THIS cycle was closed
  is_sealed           TINYINT(1)    NOT NULL DEFAULT 0,
  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ticket_cycles (ticket_id, cycle_no),
  KEY ix_ticket_cycles_assignee (assigned_to),
  CONSTRAINT fk_ticket_cycles_ticket
    FOREIGN KEY (ticket_id)   REFERENCES tickets (id),
  CONSTRAINT fk_ticket_cycles_assigned_to
    FOREIGN KEY (assigned_to) REFERENCES users (id),
  CONSTRAINT fk_ticket_cycles_assigned_by
    FOREIGN KEY (assigned_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_history — APPEND ONLY · HASH CHAINED
--
-- Every field change, assignment, status move and system action, forever.
-- INSERT is the only operation that will ever be permitted:
--   layer 1  no update()/delete() method exists on the service (A-040)
--   layer 2  no PUT/PATCH/DELETE route is registered for /history
--   layer 3  edutrack_app holds INSERT, SELECT only on this table (A-010)
--   layer 4  BEFORE UPDATE and BEFORE DELETE triggers SIGNAL 45000 (A-008)
--
-- The chain is PER-TICKET, not global (PLAN.md §3.7): every append takes
-- SELECT ... FOR UPDATE on the parent ticket row first, so two concurrent
-- writes to one ticket cannot both read the same "latest" row and fork the
-- chain. ix_history_ticket_id serves that lookup.
--
-- is_correction / corrects_entry_id implement the compensating-entry
-- pattern (A-043, CLAUDE.md): a mistake is reversed by a NEW row that
-- points at the wrong one, exactly as an accounting reversal. The original
-- is never touched.
--
-- CHAR(64) COLLATE ascii_bin per PLAN.md §3.1 — hex SHA-256, exact
-- comparison, no utf8mb4 overhead.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_history (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id         BIGINT        NOT NULL,
  cycle_no          SMALLINT      NULL,
  event_type        VARCHAR(40)   NOT NULL,   -- CREATED|ASSIGNED|STATUS_CHANGED|…
  field_name        VARCHAR(60)   NULL,
  old_value         TEXT          NULL,
  new_value         TEXT          NULL,
  actor_id          BIGINT        NULL,       -- NULL = SYSTEM
  actor_type        VARCHAR(10)   NOT NULL DEFAULT 'USER',
  remarks           TEXT          NULL,
  is_correction     TINYINT(1)    NOT NULL DEFAULT 0,
  corrects_entry_id BIGINT        NULL,
  prev_hash         CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  row_hash          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_history_ticket_id (ticket_id, id),   -- chain walk: ORDER BY id DESC
  KEY ix_history_actor (actor_id),
  KEY ix_history_event (event_type),
  KEY ix_history_corrects (corrects_entry_id),
  CONSTRAINT fk_history_ticket
    FOREIGN KEY (ticket_id)         REFERENCES tickets (id),
  CONSTRAINT fk_history_actor
    FOREIGN KEY (actor_id)          REFERENCES users (id),
  CONSTRAINT fk_history_corrects
    FOREIGN KEY (corrects_entry_id) REFERENCES ticket_history (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_effort_logs — APPEND ONLY · HASH CHAINED
--
-- The §8.2 base columns, with stage_code and iteration_no folded in from
-- the §4A.5 ALTER — they are what attribute effort to a ribbon segment
-- and drive the §4A.4 grid.
--
-- DEVIATION FROM BLUEPRINT §8.2, flagged not silent: prev_hash, row_hash,
-- is_correction and corrects_entry_id are added here. §8.2 gives hash
-- columns only to ticket_history, but CLAUDE.md states all three protected
-- tables are hash-chained and PLAN.md §3.7 says the lock-and-append
-- pattern covers this table too. Without the columns, A-044's nightly
-- verifier has nothing to verify. Taking the stricter reading.
--
-- ix_effort_rollup carries cycle_no as well as stage_code and
-- iteration_no. PLAN.md §3.4 identifies a real defect in the blueprint's
-- roll-up query — it joins effort to transitions WITHOUT cycle_no, so a
-- reopened ticket re-entering the same stage at the same iteration
-- double-counts cycle 1's effort into cycle 2. The index matches the
-- corrected four-column join.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_effort_logs (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id         BIGINT        NOT NULL,
  cycle_no          SMALLINT      NOT NULL,
  stage_code        VARCHAR(20)   NULL,
  iteration_no      SMALLINT      NOT NULL DEFAULT 1,
  user_id           BIGINT        NOT NULL,
  work_date         DATE          NOT NULL,
  hours             DECIMAL(5,2)  NOT NULL,
  note              TEXT          NULL,
  is_correction     TINYINT(1)    NOT NULL DEFAULT 0,
  corrects_entry_id BIGINT        NULL,
  prev_hash         CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  row_hash          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  logged_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_effort_ticket_id (ticket_id, id),    -- chain walk
  KEY ix_effort_rollup (ticket_id, cycle_no, stage_code, iteration_no),
  KEY ix_effort_user_date (user_id, work_date),
  KEY ix_effort_corrects (corrects_entry_id),
  -- A correction may legitimately be negative, so the blueprint's
  -- CHECK (hours > 0) is relaxed for correction rows only. Zero is
  -- rejected either way: a zero-hour entry carries no information.
  CONSTRAINT ck_effort_hours CHECK (
    hours <> 0 AND hours <= 24 AND (is_correction = 1 OR hours > 0)
  ),
  CONSTRAINT fk_effort_ticket
    FOREIGN KEY (ticket_id)         REFERENCES tickets (id),
  CONSTRAINT fk_effort_user
    FOREIGN KEY (user_id)           REFERENCES users (id),
  CONSTRAINT fk_effort_corrects
    FOREIGN KEY (corrects_entry_id) REFERENCES ticket_effort_logs (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_watchers — subscribers to a ticket's notifications.
--
-- No DDL in the blueprint; named in the §8.1 ERD and in A-004's task
-- line. Designed here, minimally. Stream D reads it in M5 to decide the
-- recipient list for a mail or notification.
--
-- Deliberately NOT the same as assignment: a PM may watch a ticket they
-- do not own, and watching grants no additional row scope — visibility
-- stays with the ScopeResolver (A-034).
-- ---------------------------------------------------------------------
CREATE TABLE ticket_watchers (
  ticket_id     BIGINT        NOT NULL,
  user_id       BIGINT        NOT NULL,
  added_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  added_by      BIGINT        NULL,
  PRIMARY KEY (ticket_id, user_id),
  KEY ix_watchers_user (user_id),
  CONSTRAINT fk_watchers_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_watchers_user
    FOREIGN KEY (user_id)   REFERENCES users (id),
  CONSTRAINT fk_watchers_added_by
    FOREIGN KEY (added_by)  REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_links — relationships between tickets.
--
-- Also undocumented in the blueprint; designed here. link_type is
-- directional and read source -> target:
--     BLOCKS | BLOCKED_BY | DUPLICATES | DUPLICATE_OF | RELATES_TO | CAUSED_BY
--
-- ck_ticket_links_not_self stops a ticket linking to itself. Unlike the
-- users self-reference, this is expressible as a CHECK: neither column is
-- AUTO_INCREMENT.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_links (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  source_ticket_id  BIGINT        NOT NULL,
  target_ticket_id  BIGINT        NOT NULL,
  link_type         VARCHAR(20)   NOT NULL,
  created_by        BIGINT        NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ticket_links (source_ticket_id, target_ticket_id, link_type),
  KEY ix_ticket_links_target (target_ticket_id),
  CONSTRAINT ck_ticket_links_not_self
    CHECK (source_ticket_id <> target_ticket_id),
  CONSTRAINT fk_ticket_links_source
    FOREIGN KEY (source_ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_links_target
    FOREIGN KEY (target_ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_links_created_by
    FOREIGN KEY (created_by)       REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
