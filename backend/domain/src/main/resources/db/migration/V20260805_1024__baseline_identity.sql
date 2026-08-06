-- =====================================================================
-- A-003 · Flyway baseline 1/5 — identity
--
-- Tables: roles, permissions, role_permissions, users, user_roles,
--         projects, project_members
--
-- Source:  blueprint §8.2 (PostgreSQL DDL) and §2 (role model)
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative)
--            BIGSERIAL   -> BIGINT AUTO_INCREMENT
--            TIMESTAMPTZ -> DATETIME(6), stored UTC
--            BOOLEAN     -> TINYINT(1)
--            NUMERIC     -> DECIMAL
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- This is the first of five files that create the whole model in one pass
-- (PLAN.md §6 M0). Everything downstream carries a foreign key to `users`
-- or `projects`, so this file must land first.
-- =====================================================================


-- ---------------------------------------------------------------------
-- roles — the six system roles of blueprint §2, extensible via S-09.
-- `id` is INT because blueprint §8.2 declares users.role_id as INT.
-- ---------------------------------------------------------------------
CREATE TABLE roles (
  id            INT           NOT NULL AUTO_INCREMENT,
  code          VARCHAR(30)   NOT NULL,
  name          VARCHAR(80)   NOT NULL,
  description   VARCHAR(255)  NULL,
  -- System roles cannot be deleted through the Role Master (PLAN.md §6 M3).
  is_system     TINYINT(1)    NOT NULL DEFAULT 0,
  is_active     TINYINT(1)    NOT NULL DEFAULT 1,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_roles_code (code)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- permissions — dotted capability codes consumed by @PreAuthorize (A-033):
-- ticket.read, ticket.assign, master.write, …
-- `category` groups them for the permission matrix screen (S-09).
-- ---------------------------------------------------------------------
CREATE TABLE permissions (
  id            INT           NOT NULL AUTO_INCREMENT,
  code          VARCHAR(60)   NOT NULL,
  name          VARCHAR(120)  NOT NULL,
  description   VARCHAR(255)  NULL,
  category      VARCHAR(40)   NOT NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_permissions_code (code),
  KEY ix_permissions_category (category)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- role_permissions — the §2 matrix, one row per granted capability.
-- Composite primary key: the pair *is* the identity, no surrogate needed.
-- ---------------------------------------------------------------------
CREATE TABLE role_permissions (
  role_id       INT           NOT NULL,
  permission_id INT           NOT NULL,
  granted_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, permission_id),
  KEY ix_role_permissions_permission (permission_id),
  CONSTRAINT fk_role_permissions_role
    FOREIGN KEY (role_id)       REFERENCES roles (id)       ON DELETE CASCADE,
  CONSTRAINT fk_role_permissions_permission
    FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- users — blueprint §8.2.
--
-- Two deliberate departures from the blueprint's DDL, both flagged for
-- review rather than made silently:
--
--   1. role_id is NOT NULL. The blueprint leaves it nullable, but §2 makes
--      role the input to every scope decision, and A-034's ScopeResolver
--      has no branch for a user without one — a NULL role would mean
--      undefined row visibility, which is the §17 top risk.
--
--   2. external_id / external_source added. GETTING-STARTED.md §1b asks
--      whether an employee directory or SSO exists; the answer is still
--      outstanding. Nullable columns cost nothing if EduTrack turns out
--      to be the system of record, and save a migration across every
--      master screen if it is not.
--
-- CHECK (id <> reporting_manager_id) from the blueprint is NOT expressible
-- here: MySQL forbids referencing an AUTO_INCREMENT column in a CHECK
-- constraint. Replaced by the trigger pair below.
-- ---------------------------------------------------------------------
CREATE TABLE users (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  emp_code              VARCHAR(20)   NOT NULL,
  username              VARCHAR(50)   NOT NULL,
  email                 VARCHAR(150)  NOT NULL,
  password_hash         VARCHAR(255)  NOT NULL,           -- Argon2id (A-020)
  full_name             VARCHAR(120)  NOT NULL,
  mobile                VARCHAR(20)   NULL,
  role_id               INT           NOT NULL,
  reporting_manager_id  BIGINT        NULL,
  department            VARCHAR(80)   NULL,
  designation           VARCHAR(80)   NULL,
  daily_capacity_hrs    DECIMAL(4,2)  NOT NULL DEFAULT 8.00,
  -- Display timezone only. Storage is UTC everywhere (PLAN.md §3.1).
  timezone              VARCHAR(50)   NOT NULL DEFAULT 'Asia/Kolkata',
  is_active             TINYINT(1)    NOT NULL DEFAULT 1,
  must_change_password  TINYINT(1)    NOT NULL DEFAULT 1, -- A-026
  failed_attempts       SMALLINT      NOT NULL DEFAULT 0, -- A-021, lockout at 5
  locked_until          DATETIME(6)   NULL,
  last_login_at         DATETIME(6)   NULL,
  external_id           VARCHAR(255)  NULL,
  external_source       VARCHAR(50)   NULL,               -- AZURE_AD | GOOGLE | …
  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_emp_code (emp_code),
  UNIQUE KEY uq_users_username (username),
  UNIQUE KEY uq_users_email (email),
  UNIQUE KEY uq_users_external (external_source, external_id),
  KEY ix_users_role (role_id),
  KEY ix_users_manager (reporting_manager_id),
  KEY ix_users_active (is_active),
  CONSTRAINT fk_users_role
    FOREIGN KEY (role_id)              REFERENCES roles (id),
  CONSTRAINT fk_users_manager
    FOREIGN KEY (reporting_manager_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- Replacement for the blueprint's CHECK (id <> reporting_manager_id).
-- Depth-1 self-reference only. Deeper cycles (A->B->A) are detected in the
-- Resource Master service — PLAN.md §6 M3, Stream B's S-07 — because a
-- trigger cannot walk the chain without a recursive query per write.
DELIMITER $$

CREATE TRIGGER trg_users_no_self_manager_ins BEFORE INSERT ON users
FOR EACH ROW
BEGIN
  IF NEW.reporting_manager_id IS NOT NULL
     AND NEW.id IS NOT NULL
     AND NEW.id = NEW.reporting_manager_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A user cannot be their own reporting manager';
  END IF;
END$$

CREATE TRIGGER trg_users_no_self_manager_upd BEFORE UPDATE ON users
FOR EACH ROW
BEGIN
  IF NEW.reporting_manager_id IS NOT NULL
     AND NEW.id = NEW.reporting_manager_id THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'A user cannot be their own reporting manager';
  END IF;
END$$

DELIMITER ;


-- ---------------------------------------------------------------------
-- user_roles — the many-to-many edge drawn in the blueprint's §8.1 ERD.
--
-- NOT read by the authentication path. `users.role_id` is authoritative:
-- §2's matrix is per-role, A-022's JWT carries a singular `role` claim, and
-- A-034's ScopeResolver switches on one role. This table exists as the seam
-- for the multi-role extension in §16 and is expected to be empty until
-- then. Raised with Stream A as schema arbiter — drop it if we would rather
-- not carry two places a role could live.
-- ---------------------------------------------------------------------
CREATE TABLE user_roles (
  user_id       BIGINT        NOT NULL,
  role_id       INT           NOT NULL,
  assigned_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  assigned_by   BIGINT        NULL,
  PRIMARY KEY (user_id, role_id),
  KEY ix_user_roles_role (role_id),
  CONSTRAINT fk_user_roles_user
    FOREIGN KEY (user_id)     REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_user_roles_role
    FOREIGN KEY (role_id)     REFERENCES roles (id),
  CONSTRAINT fk_user_roles_assigned_by
    FOREIGN KEY (assigned_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- projects — blueprint §8.2.
--
-- ticket_seq is the per-project counter driving ticket-code generation.
-- It is incremented with the LAST_INSERT_ID(expr) idiom in PLAN.md §3.2 —
-- never with COUNT(*), which breaks silently under concurrency.
--
-- project_code is immutable once the first ticket exists (PLAN.md §3.2),
-- enforced in the service layer: historical codes must keep resolving.
-- client_name is the denormalised label from §8.2; A-006 adds the real
-- `clients` table and the client_projects relationship alongside it.
-- ---------------------------------------------------------------------
CREATE TABLE projects (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  project_code      VARCHAR(10)   NOT NULL,               -- ticket-ID prefix
  name              VARCHAR(150)  NOT NULL,
  client_name       VARCHAR(150)  NULL,
  manager_id        BIGINT        NULL,
  start_date        DATE          NULL,
  target_end_date   DATE          NULL,
  status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  colour_tag        VARCHAR(7)    NULL,                   -- #RRGGBB
  ticket_seq        BIGINT        NOT NULL DEFAULT 0,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_projects_code (project_code),
  KEY ix_projects_manager (manager_id),
  KEY ix_projects_status (status),
  CONSTRAINT fk_projects_manager
    FOREIGN KEY (manager_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- project_members — the PM/Support row scope.
--
-- ix_project_members_user is load-bearing, not decorative: A-034 resolves
-- "project_id IN (my projects)" from this table on every ticket query a
-- PM or Support user makes.
-- ---------------------------------------------------------------------
CREATE TABLE project_members (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  project_id        BIGINT        NOT NULL,
  user_id           BIGINT        NOT NULL,
  -- Per-project role assignment (S-07). May differ from users.role_id:
  -- a Developer globally can be mapped as QA on one project.
  role_in_project   VARCHAR(30)   NULL,
  is_active         TINYINT(1)    NOT NULL DEFAULT 1,
  added_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  added_by          BIGINT        NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_project_members (project_id, user_id),
  KEY ix_project_members_user (user_id),
  CONSTRAINT fk_project_members_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
  CONSTRAINT fk_project_members_user
    FOREIGN KEY (user_id)    REFERENCES users (id),
  CONSTRAINT fk_project_members_added_by
    FOREIGN KEY (added_by)   REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
