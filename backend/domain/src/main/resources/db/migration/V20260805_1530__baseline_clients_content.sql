-- =====================================================================
-- A-006 · Flyway baseline 4/5 — clients & content
--
-- Tables: import_batches, clients, client_contacts, client_projects,
--         ticket_comments, ticket_attachments, email_log
-- Plus:   five columns on `tickets` (client attribution + content counters)
--
-- Source:  blueprint §4B.7 (PostgreSQL DDL), §4B.2 client dropdown,
--          §4B.4 attachments, §4B.5 comments, §4B.6 mail engine
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative)
--            BIGSERIAL     -> BIGINT AUTO_INCREMENT
--            TIMESTAMPTZ   -> DATETIME(6), stored UTC
--            BOOLEAN       -> TINYINT(1)
--            BIGINT[]      -> JSON            (§3.1, mentioned_user_ids)
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- ORDERING NOTE — this is "baseline 4/5" in the A-006 task description but
-- it carries a LATER timestamp than A-007 (V20260805_1106), so Flyway runs
-- it fifth. That is deliberate and required:
--     clients.sla_policy_id   -> sla_policies          (created in A-007)
--     email_log.template_id   -> notification_templates (created in A-007)
-- Both foreign keys need A-007's tables to exist first. Timestamp
-- versioning (CONTRIBUTING.md) makes the file name the ordering, not the
-- task number, so no renaming or renumbering is needed.
--
-- WHO IS WAITING ON THIS FILE (docs/DEPENDENCIES.md):
--     B-005 entities · B-025…B-029 client master · B-030…B-037 import
--     C-023…C-028 attachments · C-029…C-034 comments
--     D-010 outbox worker — `email_log` IS the queue (PLAN.md §2.2)
--
-- IMMUTABILITY NOTE — none of these tables carry an A-008 trigger, and
-- that is intentional. `ticket_comments` genuinely needs UPDATE: the
-- five-minute edit window (C-033) and the tombstone flag both mutate the
-- row. The guarantee is enforced in the service layer instead, with
-- `original_body` preserving the pre-edit text and `is_deleted` leaving
-- the row in place. The three trigger-protected tables remain exactly
-- ticket_history, ticket_effort_logs and ticket_stage_transitions.
-- =====================================================================


-- ---------------------------------------------------------------------
-- import_batches — one row per Excel import run (S-34, blueprint §4B.3).
--
-- Created first because `clients.import_batch_id` points at it: a bulk
-- import must be traceable, and a bad one reversible as a set rather
-- than row by row (B-037).
--
-- `entity` is what makes the import engine a schema registry rather than
-- two implementations — CLIENT today, RESOURCE from B-038, and any later
-- registration without a migration.
-- ---------------------------------------------------------------------
CREATE TABLE import_batches (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  entity           VARCHAR(30)   NOT NULL,              -- CLIENT|RESOURCE
  file_name        VARCHAR(255)  NULL,
  total_rows       INT           NOT NULL DEFAULT 0,
  created_rows     INT           NOT NULL DEFAULT 0,
  updated_rows     INT           NOT NULL DEFAULT 0,
  rejected_rows    INT           NOT NULL DEFAULT 0,
  status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                                 -- PENDING|VALIDATING|COMMITTING|DONE|FAILED
  -- Object-storage key of the .xlsx error report (B-036): the original
  -- rows plus an appended Reason column, so the user fixes and re-uploads
  -- only what was rejected.
  error_report_key VARCHAR(400)  NULL,
  imported_by      BIGINT        NULL,
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_import_batches_entity (entity, created_at),
  KEY ix_import_batches_imported_by (imported_by),
  CONSTRAINT fk_import_batches_user
    FOREIGN KEY (imported_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- clients — the client master (S-32/S-33, blueprint §4B.2).
--
-- `client_code` is the natural key and the upsert key for the Excel
-- import (B-035: update, never duplicate), so it is UNIQUE rather than
-- merely indexed.
--
-- `website_domain` exists so inbound mail can be auto-matched to a client
-- (D-039). It is indexed for that lookup, which happens per inbound
-- message rather than per page load.
--
-- `timezone` is presentation-layer only. Every DATETIME(6) in this schema
-- is UTC (PLAN.md §3.1); this column decides how the client's own hours
-- are rendered and how client-facing SLA windows read, never how
-- anything is stored.
--
-- Clients are deactivated, never deleted (B-029) — historical tickets
-- must keep resolving their client. Hence no ON DELETE CASCADE anywhere
-- that points here.
-- ---------------------------------------------------------------------
CREATE TABLE clients (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  client_code        VARCHAR(20)   NOT NULL,
  name               VARCHAR(150)  NOT NULL,
  short_name         VARCHAR(60)   NULL,
  industry           VARCHAR(80)   NULL,
  status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE|INACTIVE
  account_manager_id BIGINT        NULL,
  support_plan       VARCHAR(20)   NULL,        -- BASIC|STANDARD|PREMIUM|…
  sla_policy_id      BIGINT        NULL,        -- overrides the project default
  primary_email      VARCHAR(150)  NULL,
  support_email      VARCHAR(150)  NULL,
  phone              VARCHAR(30)   NULL,
  website_domain     VARCHAR(120)  NULL,        -- auto-matches inbound mail
  address_line1      VARCHAR(150)  NULL,
  address_line2      VARCHAR(150)  NULL,
  city               VARCHAR(80)   NULL,
  state              VARCHAR(80)   NULL,
  country            VARCHAR(80)   NULL,
  postal_code        VARCHAR(20)   NULL,
  timezone           VARCHAR(50)   NOT NULL DEFAULT 'Asia/Kolkata',
  contract_start     DATE          NULL,
  contract_end       DATE          NULL,
  notes              TEXT          NULL,
  -- Traces bulk-imported rows back to their batch. The blueprint declares
  -- this column without a foreign key; the key is added here because
  -- import_batches exists in the same file and B-037 requires the batch
  -- to be resolvable, not merely recorded.
  import_batch_id    BIGINT        NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_clients_code (client_code),
  KEY ix_clients_status (status),
  KEY ix_clients_name (name),
  KEY ix_clients_account_manager (account_manager_id),
  KEY ix_clients_domain (website_domain),
  KEY ix_clients_sla_policy (sla_policy_id),
  KEY ix_clients_import_batch (import_batch_id),
  CONSTRAINT fk_clients_account_manager
    FOREIGN KEY (account_manager_id) REFERENCES users (id),
  CONSTRAINT fk_clients_sla_policy
    FOREIGN KEY (sla_policy_id)      REFERENCES sla_policies (id),
  CONSTRAINT fk_clients_import_batch
    FOREIGN KEY (import_batch_id)    REFERENCES import_batches (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- client_contacts — the people at the client (B-027, blueprint §4B.2).
--
-- The ticket form's second dropdown depends on this table: pick a client,
-- then pick the individual who reported the issue (C-021).
--
-- `is_primary` is NOT enforced unique here on purpose. B-028 requires at
-- least one primary contact before a client becomes selectable on a
-- ticket, and "at least one" is not expressible as a UNIQUE key. A
-- partial unique index would give "at most one", which MySQL also lacks.
-- Both halves of that rule live in the service layer; the flag is stored
-- and indexed so the check is a single indexed read, not a table scan.
--
-- `receives_mail` feeds the D-036 recipient list; `portal_access` is the
-- forward hook for client portal login, not used in phase 1.
-- ---------------------------------------------------------------------
CREATE TABLE client_contacts (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  client_id     BIGINT        NOT NULL,
  name          VARCHAR(120)  NOT NULL,
  designation   VARCHAR(80)   NULL,
  email         VARCHAR(150)  NULL,
  phone         VARCHAR(30)   NULL,
  is_primary    TINYINT(1)    NOT NULL DEFAULT 0,
  receives_mail TINYINT(1)    NOT NULL DEFAULT 1,
  portal_access TINYINT(1)    NOT NULL DEFAULT 0,
  is_active     TINYINT(1)    NOT NULL DEFAULT 1,
  -- The blueprint omits timestamps on this table. They are added for
  -- consistency with every other table in the schema and because the
  -- contact grid (B-027) sorts by recency.
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_client_contacts_client (client_id, is_active),
  KEY ix_client_contacts_primary (client_id, is_primary),
  KEY ix_client_contacts_email (email),
  CONSTRAINT fk_client_contacts_client
    FOREIGN KEY (client_id) REFERENCES clients (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- client_projects — which clients are reachable from which project.
--
-- The client dropdown on the ticket form is filtered to clients mapped to
-- the ticket's project (C-021), so this join table is read on every
-- ticket create. Composite primary key: the pair is the identity.
--
-- CASCADE on both sides is safe here and only here — this table holds no
-- fact of its own, only the association. Deleting the association never
-- destroys history the way deleting a client would.
-- ---------------------------------------------------------------------
CREATE TABLE client_projects (
  client_id  BIGINT      NOT NULL,
  project_id BIGINT      NOT NULL,
  is_default TINYINT(1)  NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (client_id, project_id),
  KEY ix_client_projects_project (project_id),
  CONSTRAINT fk_client_projects_client
    FOREIGN KEY (client_id)  REFERENCES clients (id)  ON DELETE CASCADE,
  CONSTRAINT fk_client_projects_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_comments — the discussion thread (S-20, blueprint §4B.5).
--
-- Stamped with cycle_no, stage_code and iteration_no AT THE TIME OF
-- WRITING (C-032). Those are copied, never joined — the ribbon moves on,
-- and a comment written during QA iteration 2 must still read as QA
-- iteration 2 forever. This is also what lets a ribbon segment click
-- filter the thread below it (C-052).
--
-- `is_internal` DEFAULTS TO 1. Blueprint §4B.5 and the Stream C decision
-- log both settle on internal-always: an accidental leak to a client
-- costs far more than an extra click.
--
-- `body_text` is stored alongside `body_html` for search and for the
-- plain-text part of reply-by-email (D-039), so neither has to be
-- derived at read time.
--
-- Mutation is allowed here, unlike the three protected tables — see the
-- IMMUTABILITY NOTE in the file header. `original_body` is written once
-- on first edit and never again; `is_deleted` is a tombstone, the row is
-- never removed.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_comments (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id          BIGINT        NOT NULL,
  -- Journey coordinates at time of writing. Nullable because a comment
  -- can precede the first stage transition on a freshly created ticket.
  cycle_no           SMALLINT      NULL,
  stage_code         VARCHAR(20)   NULL,
  iteration_no       SMALLINT      NULL,
  author_id          BIGINT        NOT NULL,
  body_html          TEXT          NOT NULL,
  body_text          TEXT          NOT NULL,   -- search + email replies
  is_internal        TINYINT(1)    NOT NULL DEFAULT 1,   -- 0 = client-visible
  -- PLAN.md §3.1: PostgreSQL BIGINT[] has no MySQL equivalent. JSON array
  -- of user ids. If "every comment mentioning me" ever needs to be an
  -- indexed query rather than a scan, §3.4 names the child table that
  -- replaces this.
  mentioned_user_ids JSON          NULL,
  source             VARCHAR(15)   NOT NULL DEFAULT 'WEB',  -- WEB|EMAIL|API
  edited_at          DATETIME(6)   NULL,
  original_body      TEXT          NULL,       -- preserved on first edit
  is_deleted         TINYINT(1)    NOT NULL DEFAULT 0,      -- tombstone
  deleted_by         BIGINT        NULL,
  deleted_at         DATETIME(6)   NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The thread read, and the History-tab interleave (C-034).
  KEY ix_comments_ticket (ticket_id, created_at),
  -- Ribbon segment click -> filter the thread to that stage + iteration.
  KEY ix_comments_journey (ticket_id, cycle_no, stage_code, iteration_no),
  KEY ix_comments_author (author_id),
  KEY ix_comments_deleted_by (deleted_by),
  CONSTRAINT fk_comments_ticket
    FOREIGN KEY (ticket_id)  REFERENCES tickets (id),
  CONSTRAINT fk_comments_author
    FOREIGN KEY (author_id)  REFERENCES users (id),
  CONSTRAINT fk_comments_deleted_by
    FOREIGN KEY (deleted_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ticket_attachments — files on the ticket (blueprint §4B.4).
--
-- `storage_key` is the MinIO/S3 object key, `tickets/{id}/{uuid}` (C-025).
-- The bucket is never public; reads go through short-lived signed URLs,
-- so nothing here is a resolvable address on its own.
--
-- `scan_status` gates visibility: a file is PENDING until the AV scan
-- returns CLEAN, and an INFECTED file is never served. The default is
-- PENDING rather than CLEAN so a scanner outage fails closed.
--
-- `comment_id` is nullable — an attachment can hang off the ticket, a
-- comment, the handoff dialog or a quick update (C-023).
--
-- Same tombstone rule as comments: deletion inside the 15-minute window
-- (C-028) removes the object, but the row stays with is_deleted = 1.
-- ---------------------------------------------------------------------
CREATE TABLE ticket_attachments (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id         BIGINT        NOT NULL,
  comment_id        BIGINT        NULL,
  cycle_no          SMALLINT      NULL,
  stage_code        VARCHAR(20)   NULL,
  file_name         VARCHAR(255)  NOT NULL,
  storage_key       VARCHAR(400)  NOT NULL,   -- tickets/{id}/{uuid}
  mime_type         VARCHAR(100)  NOT NULL,   -- sniffed, not trusted from the client
  size_bytes        BIGINT        NOT NULL,
  thumbnail_key     VARCHAR(400)  NULL,
  is_client_visible TINYINT(1)    NOT NULL DEFAULT 0,
  scan_status       VARCHAR(15)   NOT NULL DEFAULT 'PENDING',
                                  -- PENDING|CLEAN|INFECTED
  uploaded_by       BIGINT        NULL,
  is_deleted        TINYINT(1)    NOT NULL DEFAULT 0,
  deleted_by        BIGINT        NULL,
  -- Added beyond blueprint §4B.7: the tombstone records when, not only
  -- who. Without it a deleted attachment cannot be placed on the History
  -- timeline (C-034).
  deleted_at        DATETIME(6)   NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_attachments_storage_key (storage_key),
  KEY ix_attachments_ticket (ticket_id, created_at),
  KEY ix_attachments_comment (comment_id),
  -- Attachments tab, grouped by cycle and stage (C-060).
  KEY ix_attachments_journey (ticket_id, cycle_no, stage_code),
  -- The AV worker claims PENDING rows; keep that scan off the main table.
  KEY ix_attachments_scan (scan_status),
  KEY ix_attachments_uploaded_by (uploaded_by),
  KEY ix_attachments_deleted_by (deleted_by),
  CONSTRAINT fk_attachments_ticket
    FOREIGN KEY (ticket_id)   REFERENCES tickets (id),
  CONSTRAINT fk_attachments_comment
    FOREIGN KEY (comment_id)  REFERENCES ticket_comments (id),
  CONSTRAINT fk_attachments_uploader
    FOREIGN KEY (uploaded_by) REFERENCES users (id),
  CONSTRAINT fk_attachments_deleted_by
    FOREIGN KEY (deleted_by)  REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- email_log — every mail send, and THE OUTBOX QUEUE.
--
-- PLAN.md §2.2 replaces BullMQ with this table: the row IS the queue
-- entry. D-010's worker claims with
--     SELECT ... WHERE status = 'QUEUED' AND next_attempt_at <= NOW(6)
--       ORDER BY next_attempt_at FOR UPDATE SKIP LOCKED
-- sends, then stamps the result. Enqueue is therefore atomic with the
-- business transaction — a handoff that rolls back cannot leave a phantom
-- mail queued, which a separate broker could never have guaranteed.
--
-- `next_attempt_at` is NOT in blueprint §4B.7. PLAN.md §2.2 names it
-- explicitly as the column that carries exponential backoff, and without
-- it D-010 has nowhere to store the retry schedule. It is the difference
-- between a queue and a log.
--
-- ix_email_log_claim is the index that decides whether the claim query is
-- a range scan or a table scan every few seconds, forever.
-- ---------------------------------------------------------------------
CREATE TABLE email_log (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  ticket_id       BIGINT        NULL,        -- NULL for non-ticket mail
  event_code      VARCHAR(40)   NOT NULL,    -- TICKET_ASSIGNED|HANDOFF_RECEIVED|…
  template_id     BIGINT        NULL,
  to_user_id      BIGINT        NULL,        -- NULL when the recipient is a client contact
  to_email        VARCHAR(150)  NOT NULL,
  subject         VARCHAR(300)  NULL,
  status          VARCHAR(15)   NOT NULL DEFAULT 'QUEUED',
                                -- QUEUED|SENT|BOUNCED|FAILED
  provider_msg_id VARCHAR(200)  NULL,
  retry_count     SMALLINT      NOT NULL DEFAULT 0,
  -- Outbox scheduling. Defaults to now, so a freshly enqueued mail is
  -- immediately claimable; backoff pushes it forward on each failure.
  next_attempt_at DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  error_text      TEXT          NULL,
  queued_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at         DATETIME(6)   NULL,
  PRIMARY KEY (id),
  -- The worker's claim query (D-010).
  KEY ix_email_log_claim (status, next_attempt_at),
  -- "Show me this ticket's mail" — the delivery-proof read (D-033).
  KEY ix_email_log_ticket (ticket_id, queued_at),
  -- D-035 rate limit: at most one mail per recipient per ticket per
  -- minute. Checked before every enqueue, so it must be indexed.
  KEY ix_email_log_rate (to_email, ticket_id, queued_at),
  KEY ix_email_log_template (template_id),
  KEY ix_email_log_user (to_user_id),
  CONSTRAINT fk_email_log_ticket
    FOREIGN KEY (ticket_id)   REFERENCES tickets (id),
  CONSTRAINT fk_email_log_template
    FOREIGN KEY (template_id) REFERENCES notification_templates (id),
  CONSTRAINT fk_email_log_user
    FOREIGN KEY (to_user_id)  REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- tickets — client attribution and content counters (blueprint §4B.7).
--
-- `is_client_raised` drives client-wise reporting, CSAT and the default
-- client-visibility of comments (C-022). It is a stored fact rather than
-- `client_id IS NOT NULL`, because an internally-raised ticket can still
-- belong to a client.
--
-- `comment_count` and `attachment_count` are materialised counters
-- maintained by the service layer on insert and tombstone. They exist so
-- the ticket list can render badges without a correlated subquery per
-- row — the same reason the dashboard reads summary tables rather than
-- issuing COUNT(*) (CLAUDE.md, PLAN.md §3.9).
-- ---------------------------------------------------------------------
ALTER TABLE tickets
  ADD COLUMN client_id         BIGINT     NULL,
  ADD COLUMN client_contact_id BIGINT     NULL,
  ADD COLUMN is_client_raised  TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN comment_count     INT        NOT NULL DEFAULT 0,
  ADD COLUMN attachment_count  INT        NOT NULL DEFAULT 0,
  ADD INDEX ix_tickets_client (client_id, status),
  ADD INDEX ix_tickets_client_contact (client_contact_id),
  ADD CONSTRAINT fk_tickets_client
    FOREIGN KEY (client_id)         REFERENCES clients (id),
  ADD CONSTRAINT fk_tickets_client_contact
    FOREIGN KEY (client_contact_id) REFERENCES client_contacts (id);
