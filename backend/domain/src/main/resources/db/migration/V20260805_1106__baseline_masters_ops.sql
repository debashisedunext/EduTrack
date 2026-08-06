-- =====================================================================
-- A-007 · Flyway baseline 5/5 — masters & ops
--
-- Tables: task_types, priorities, statuses, workflow_transitions,
--         sla_policies, holidays, resource_leaves,
--         notification_templates, notifications,
--         chat_threads, chat_participants, chat_messages, audit_logs
--         + the deferred FK from tickets.task_type_id
--
-- NO DDL EXISTS FOR ANY OF THESE IN THE BLUEPRINT. §8.2, §4A.5 and §4B.7
-- give DDL for identity, tickets, workflow and clients only; these twelve
-- are named in the §8.1 ERD and constrained by prose. Every column below
-- is designed here, from:
--     §4B.1  priority master — colour, default SLA, escalation flag
--     §6     escalation & SLA engine, and the sla_policies signature
--     §11    the notification matrix — 23 events
--     B-002/B-003  the seed content Stream B must load into these tables
--
-- Review this file harder than the previous three. Those transcribed a
-- specification; this one interprets one.
-- =====================================================================


-- ---------------------------------------------------------------------
-- task_types — S-11. Stream B seeds 11 rows in B-002: Change Request,
-- Production Bug, Client Request, Future Release, Internal Bug, Client
-- Bug, Server Issue, Network Issue, Browser Issue, Performance Issue,
-- Other.
--
-- default_level is what §4B.1 means by "pre-filled from the task type
-- master" — a Production Bug defaults to High, a Future Release to Low.
-- It is a default only; the user may override it on the create form.
-- ---------------------------------------------------------------------
CREATE TABLE task_types (
  id                 INT           NOT NULL AUTO_INCREMENT,
  code               VARCHAR(40)   NOT NULL,
  name               VARCHAR(80)   NOT NULL,
  icon               VARCHAR(30)   NULL,
  colour             VARCHAR(7)    NULL,              -- #RRGGBB, blueprint §12.1
  default_level      VARCHAR(10)   NULL,              -- LOW|MEDIUM|HIGH|CRITICAL
  default_sla_hours  DECIMAL(6,2)  NULL,              -- working hours
  seq                SMALLINT      NOT NULL DEFAULT 0,
  is_active          TINYINT(1)    NOT NULL DEFAULT 1,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_task_types_code (code),
  KEY ix_task_types_active (is_active)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- priorities — S-12, blueprint §4B.1. Four rows seeded in B-002:
-- Low, Medium, High, Critical.
--
-- Kept as a master table rather than an enum because §4B.1 says "Admin
-- can add levels". The tickets.level column stays a VARCHAR(10) holding
-- the code, not an FK, so that a level can be retired from the master
-- without orphaning historical tickets that were raised at it.
--
-- is_escalation_trigger marks the level the SLA engine escalates TO on
-- breach — Critical, per §6.
-- ---------------------------------------------------------------------
CREATE TABLE priorities (
  id                     INT           NOT NULL AUTO_INCREMENT,
  code                   VARCHAR(10)   NOT NULL,      -- LOW|MEDIUM|HIGH|CRITICAL
  name                   VARCHAR(40)   NOT NULL,
  colour                 VARCHAR(7)    NULL,
  seq                    SMALLINT      NOT NULL DEFAULT 0,   -- display order
  default_sla_hours      DECIMAL(6,2)  NULL,
  is_escalation_trigger  TINYINT(1)    NOT NULL DEFAULT 0,
  is_active              TINYINT(1)    NOT NULL DEFAULT 1,
  created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_priorities_code (code)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- statuses — seeded in B-003: New, In Progress, On Hold, Awaiting Info,
-- Rework, Resolved, Closed, Reopened.
--
-- STATUS AND STAGE ARE SEPARATE LAYERS (blueprint §3). A ticket can be
-- "In Progress" while sitting in the "QA" stage. Confusing the two
-- corrupts the data model, which is why status lives here and stage
-- lives in workflow_stages.
--
-- is_open drives every "open tickets" count on the dashboard; is_terminal
-- marks the states from which only a reopen moves the ticket on.
-- ---------------------------------------------------------------------
CREATE TABLE statuses (
  id           INT           NOT NULL AUTO_INCREMENT,
  code         VARCHAR(20)   NOT NULL,
  name         VARCHAR(40)   NOT NULL,
  colour       VARCHAR(7)    NULL,
  seq          SMALLINT      NOT NULL DEFAULT 0,
  is_open      TINYINT(1)    NOT NULL DEFAULT 1,
  is_terminal  TINYINT(1)    NOT NULL DEFAULT 0,      -- CLOSED
  is_active    TINYINT(1)    NOT NULL DEFAULT 1,
  created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_statuses_code (code)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- workflow_transitions — the allowed status-transition matrix, per role
-- (B-003). Distinct from workflow_stages: that table is the RIBBON, this
-- one is STATUS. §3 keeps them separate on purpose.
--
-- A missing row means the transition is forbidden, so this table is a
-- whitelist. Governance decision G-3 (PLAN.md §5) — "may a Developer
-- close a ticket?", recommended no, Resolved only — is expressed here as
-- data rather than code: simply no (RESOLVED -> CLOSED, DEVELOPER) row.
--
-- from_status NULL means "on creation", covering the initial move to NEW.
-- ---------------------------------------------------------------------
CREATE TABLE workflow_transitions (
  id               INT           NOT NULL AUTO_INCREMENT,
  from_status      VARCHAR(20)   NULL,                -- NULL = on create
  to_status        VARCHAR(20)   NOT NULL,
  role_code        VARCHAR(20)   NOT NULL,
  requires_reason  TINYINT(1)    NOT NULL DEFAULT 0,
  requires_effort  TINYINT(1)    NOT NULL DEFAULT 0,  -- G-1: effort at handoff
  is_active        TINYINT(1)    NOT NULL DEFAULT 1,
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_workflow_transitions (from_status, to_status, role_code),
  KEY ix_workflow_transitions_role (role_code)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- sla_policies — NOT ON THE A-007 TASK LIST. Added deliberately.
--
-- Blueprint §6 specifies it by signature —
--   sla_policies(project_id, task_type, level, response_hrs,
--                resolution_hrs, escalate_to_l1, escalate_to_l2)
-- — and the §8.1 ERD draws projects ──< sla_policies. But it appears in
-- no A-00x task line, so it would have fallen through the gap between
-- A-006 and A-007 and only surfaced in week 6 when Stream D's scanner
-- had nothing to read. Raising it rather than omitting it.
--
-- NULL project_id is the org-wide default; a row with a project_id
-- overrides it for that project. Resolution order, most specific first:
--   (project, task_type, level) -> (project, level) -> (NULL, level)
--
-- Hours are WORKING hours, resolved through Stream B's calendar service
-- (B-021), never wall-clock.
-- ---------------------------------------------------------------------
CREATE TABLE sla_policies (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  project_id       BIGINT        NULL,                -- NULL = org-wide default
  task_type_id     INT           NULL,                -- NULL = any type
  level            VARCHAR(10)   NOT NULL,
  response_hrs     DECIMAL(6,2)  NULL,
  resolution_hrs   DECIMAL(6,2)  NOT NULL,
  escalate_to_l1   TINYINT(1)    NOT NULL DEFAULT 1,  -- reporting manager
  escalate_to_l2   TINYINT(1)    NOT NULL DEFAULT 0,  -- RM's manager, §6 48h rule
  is_active        TINYINT(1)    NOT NULL DEFAULT 1,
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_sla_policies (project_id, task_type_id, level),
  KEY ix_sla_policies_lookup (level, is_active),
  CONSTRAINT fk_sla_policies_project
    FOREIGN KEY (project_id)   REFERENCES projects (id) ON DELETE CASCADE,
  CONSTRAINT fk_sla_policies_task_type
    FOREIGN KEY (task_type_id) REFERENCES task_types (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- holidays — org and per-project non-working days.
--
-- One half of the working calendar. CLAUDE.md: "All SLA and duration
-- maths use the working calendar — weekends, org holidays, resource
-- leave. A Friday-18:00 ticket with a 4-hour SLA must not breach on
-- Saturday morning."
--
-- is_recurring covers fixed-date annual holidays; the calendar service
-- (B-021) expands them per year rather than storing a row per year.
-- ---------------------------------------------------------------------
CREATE TABLE holidays (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  holiday_date   DATE          NOT NULL,
  name           VARCHAR(120)  NOT NULL,
  project_id     BIGINT        NULL,                  -- NULL = org-wide
  is_recurring   TINYINT(1)    NOT NULL DEFAULT 0,    -- same date every year
  is_active      TINYINT(1)    NOT NULL DEFAULT 1,
  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_holidays (holiday_date, project_id),
  KEY ix_holidays_date (holiday_date, is_active),
  CONSTRAINT fk_holidays_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- resource_leaves — the other half of the working calendar.
--
-- A stage SLA must not tick while the stage owner is on leave, or every
-- ticket assigned before a holiday breaches on return. Half days are
-- flagged rather than stored as hours, because the calendar service
-- resolves them against that user's daily_capacity_hrs.
-- ---------------------------------------------------------------------
CREATE TABLE resource_leaves (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  user_id       BIGINT        NOT NULL,
  start_date    DATE          NOT NULL,
  end_date      DATE          NOT NULL,
  leave_type    VARCHAR(30)   NULL,      -- PLANNED|SICK|COMP_OFF|OTHER
  is_half_day   TINYINT(1)    NOT NULL DEFAULT 0,
  status        VARCHAR(20)   NOT NULL DEFAULT 'APPROVED',
  reason        VARCHAR(255)  NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_resource_leaves_user (user_id, start_date, end_date),
  CONSTRAINT ck_resource_leaves_range CHECK (end_date >= start_date),
  CONSTRAINT fk_resource_leaves_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- notification_templates — one row per (event, channel) in the §11
-- matrix. 23 events across 3 channels.
--
-- Templates rather than hardcoded strings so wording changes are a data
-- edit, not a deploy. Stream D renders them (Thymeleaf is already on the
-- worker classpath) and owns the mail engine that consumes them.
-- ---------------------------------------------------------------------
CREATE TABLE notification_templates (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  event_code        VARCHAR(60)   NOT NULL,   -- TICKET_ASSIGNED|HANDOFF_RECEIVED|…
  channel           VARCHAR(20)   NOT NULL,   -- POPUP|BELL|EMAIL
  subject_template  VARCHAR(255)  NULL,       -- NULL for non-email channels
  body_template     TEXT          NOT NULL,
  is_active         TINYINT(1)    NOT NULL DEFAULT 1,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_notification_templates (event_code, channel)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- notifications — the bell. One row per delivered in-app notification.
--
-- ix_notifications_unread is the hot path: the bell badge count runs on
-- every page load for every user, so (user_id, is_read, created_at) is
-- the index that keeps it off a table scan.
--
-- ticket_id is nullable — a daily digest or a system announcement
-- belongs to no single ticket.
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  user_id       BIGINT        NOT NULL,
  ticket_id     BIGINT        NULL,
  event_code    VARCHAR(60)   NOT NULL,
  title         VARCHAR(200)  NOT NULL,
  body          TEXT          NULL,
  link_url      VARCHAR(500)  NULL,       -- deep link to the ticket or screen
  is_read       TINYINT(1)    NOT NULL DEFAULT 0,
  read_at       DATETIME(6)   NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_notifications_unread (user_id, is_read, created_at),
  KEY ix_notifications_ticket (ticket_id),
  CONSTRAINT fk_notifications_user
    FOREIGN KEY (user_id)   REFERENCES users (id)   ON DELETE CASCADE,
  CONSTRAINT fk_notifications_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- chat_threads — M7. A thread hangs off a ticket, or stands alone as a
-- direct message.
--
-- thread_type ASK_STATUS is the §6 "Ask Status" flow: a manager clicks
-- the button, a thread opens, the assignee gets a bell and a chat message.
-- ---------------------------------------------------------------------
CREATE TABLE chat_threads (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id     BIGINT        NULL,       -- NULL = standalone DM
  thread_type   VARCHAR(20)   NOT NULL DEFAULT 'TICKET',  -- TICKET|DIRECT|ASK_STATUS
  subject       VARCHAR(200)  NULL,
  created_by    BIGINT        NULL,
  is_active     TINYINT(1)    NOT NULL DEFAULT 1,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_chat_threads_ticket (ticket_id),
  CONSTRAINT fk_chat_threads_ticket
    FOREIGN KEY (ticket_id)  REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_threads_created_by
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- chat_participants — membership, and the read cursor.
--
-- last_read_message_id rather than a boolean per message: unread counts
-- become "messages with a higher id", which is one indexed comparison
-- instead of a row per user per message.
-- ---------------------------------------------------------------------
CREATE TABLE chat_participants (
  thread_id             BIGINT        NOT NULL,
  user_id               BIGINT        NOT NULL,
  joined_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_read_message_id  BIGINT        NULL,
  is_muted              TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (thread_id, user_id),
  KEY ix_chat_participants_user (user_id),
  CONSTRAINT fk_chat_participants_thread
    FOREIGN KEY (thread_id) REFERENCES chat_threads (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_participants_user
    FOREIGN KEY (user_id)   REFERENCES users (id)        ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- chat_messages.
--
-- mentioned_user_ids is JSON per PLAN.md §3.1 (PostgreSQL BIGINT[] has no
-- MySQL equivalent). §3.1 notes the alternative — a child table — is what
-- to reach for if "all messages mentioning me" ever needs to be efficient.
-- It is not queried that way today; @mention only fans out a notification
-- at write time.
-- ---------------------------------------------------------------------
CREATE TABLE chat_messages (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  thread_id           BIGINT        NOT NULL,
  sender_id           BIGINT        NULL,       -- NULL = SYSTEM
  body                TEXT          NOT NULL,
  mentioned_user_ids  JSON          NULL,       -- [42, 17]
  is_system           TINYINT(1)    NOT NULL DEFAULT 0,
  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_chat_messages_thread (thread_id, id),
  CONSTRAINT ck_chat_mentions_is_array
    CHECK (mentioned_user_ids IS NULL OR JSON_TYPE(mentioned_user_ids) = 'ARRAY'),
  CONSTRAINT fk_chat_messages_thread
    FOREIGN KEY (thread_id) REFERENCES chat_threads (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_messages_sender
    FOREIGN KEY (sender_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- audit_logs — S-16, the Audit Log Viewer (A-071).
--
-- Every login, permission change, master change and ticket action.
-- "Export only, never editable" per the backlog — but note this table is
-- NOT one of the three hash-chained append-only tables, so A-008 does not
-- put immutability triggers on it. If the audit log needs the same
-- guarantee as ticket_history, that is a separate decision and a separate
-- migration; flagging rather than assuming.
--
-- ip_address is VARCHAR(45) to hold an IPv6 address in full.
-- ---------------------------------------------------------------------
CREATE TABLE audit_logs (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  actor_id      BIGINT        NULL,       -- NULL = SYSTEM
  action        VARCHAR(60)   NOT NULL,   -- LOGIN|LOGIN_FAILED|ROLE_CHANGED|…
  entity_type   VARCHAR(60)   NULL,       -- tickets|users|workflow_templates|…
  entity_id     BIGINT        NULL,
  old_value     TEXT          NULL,
  new_value     TEXT          NULL,
  ip_address    VARCHAR(45)   NULL,       -- IPv6-capable
  user_agent    VARCHAR(255)  NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_audit_logs_actor (actor_id, created_at),
  KEY ix_audit_logs_entity (entity_type, entity_id),
  KEY ix_audit_logs_action (action, created_at),
  CONSTRAINT fk_audit_logs_actor
    FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Deferred foreign key from A-004.
-- ---------------------------------------------------------------------
ALTER TABLE tickets
  ADD CONSTRAINT fk_tickets_task_type
    FOREIGN KEY (task_type_id) REFERENCES task_types (id);
