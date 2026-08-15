-- =====================================================================
-- C-027 · blueprint §4B.4's three attachment caps, made configurable.
--
-- Table: attachment_settings (new)
--
-- §4B.4 words the limits row as "10 MB per file by default, 50 MB per
-- ticket, 20 files per ticket — **all configurable in system settings**".
-- C-025 enforced all three, correctly, from `edutrack.attachments.*` in
-- `application.yml`; the block there says in as many words that "making
-- these settings-backed rather than properties is C-027", and so does the
-- javadoc on AttachmentLimitExceededException. This is that table.
--
-- A property is configurable by whoever can restart the process. "System
-- settings" is configurable by an administrator, at runtime, on a running
-- system — which is what the blueprint is asking for, and it is the whole
-- difference between the two.
--
-- FLAGGED FOR STREAM A. This is a new table and touches none of
-- `tickets`, `ticket_history`, `ticket_effort_logs` or
-- `ticket_stage_transitions`, so CLAUDE.md's mandatory review does not
-- apply — but TEAM-PLAN §7.1 makes A the schema arbiter and a new table
-- should not appear on a tickets branch unremarked. Same precedent
-- B-011, B-016, B-017 and B-019 each set.
--
-- ---------------------------------------------------------------------
-- 1. Why a purpose-shaped table and not a generic `system_settings`
--
-- A key/value settings table is the obvious generalisation and is
-- deliberately not built here. It cannot type its values, so every
-- consumer parses a string and every parse is a place a typo becomes a
-- runtime failure instead of a failed write; and the CHECK constraints
-- below — which are the reason a bad limit cannot reach the enforcement
-- path at all — have nothing to attach to when the column is `TEXT`.
--
-- The product has one settings screen specified today (S-10's per-project
-- tab, B-019) and no org-wide one; inventing a generic store for a second
-- consumer that does not exist would be deciding that design from the
-- branch least able to. When the org-wide Settings screen in the sidebar
-- §11 sketches is actually specified, this table either joins it or is
-- absorbed by it, and that is a rename.
--
-- ---------------------------------------------------------------------
-- 2. One row, enforced by the schema
--
-- `id` is fixed at 1 by a CHECK, so the table physically cannot hold a
-- second configuration. The alternative — "the newest row wins" — reads
-- fine and then silently keeps serving the old limits the first time an
-- insert lands with a clock skew, and there is no scope column here that
-- a second row could ever mean something against.
--
-- The row is SEEDED with §4B.4's numbers rather than left absent. An
-- empty table would make "no administrator has configured this yet" and
-- "somebody set the caps to zero" the same state on the read path, and
-- the read path is an upload guard. Absence is still handled in
-- AttachmentSettingsService — it falls back to the properties rather than
-- refusing every upload, because a settings row deleted by hand should
-- cost the customisation and not the feature — but it is not the ordinary
-- state and nothing depends on it.
--
-- ---------------------------------------------------------------------
-- 3. The constraints are the point, and each one closes a specific way to
--    make the product silently wrong from a settings screen
--
-- `max_ticket_bytes >= max_file_bytes` — a per-ticket total below the
-- per-file cap makes the per-file cap unreachable: every file large
-- enough to test it is refused by the ticket total first, with a message
-- telling the user to remove an attachment from a ticket that may have
-- none. The two settings would not look wrong next to each other on a
-- form.
--
-- `max_file_bytes <= 100 MB` — an upper bound exists because
-- AttachmentService reads the whole part into a byte[] before sniffing,
-- stripping and storing it, so the per-file cap is also this feature's
-- per-request heap cost, multiplied by however many uploads are in
-- flight. The real ceiling is lower still and is NOT in this file: the
-- servlet container refuses an oversized body during parsing, before any
-- of our code runs, so a setting above `spring.servlet.multipart.
-- max-file-size` would produce a generic container 413 instead of §4B.4's
-- worded one. That check needs the running configuration and lives in
-- AttachmentSettingsService; this bound is the backstop under it.
--
-- `max_files <= 200` — the per-ticket count bounds a query that loads
-- every live attachment row on every upload (AttachmentService
-- .enforceLimits) and a gallery strip that renders every one of them.
-- Twenty is §4B.4's number; two hundred is the point past which an
-- administrator has broken the ticket page rather than configured it.
--
-- Lower bounds of 1 on all three: zero is not "unlimited", it is "no
-- attachment may ever be uploaded", and an administrator who meant the
-- first would disable the feature everywhere with no message saying so.
-- Unlimited is deliberately not expressible — §4B.4 has no such state and
-- an uncapped upload path is not a setting.
--
-- ---------------------------------------------------------------------
-- 4. `updated_by` is nullable and has no foreign key
--
-- The audit trail for a settings change is A-024's audit log, not this
-- column; what is kept here is enough to render "last changed by X on Y"
-- next to the values. Nullable because the seeded row was written by this
-- migration and no user did it, and unconstrained because a resource who
-- leaves must be deletable without either orphaning this row or losing
-- the fact that the limits were changed at all.
-- =====================================================================

CREATE TABLE attachment_settings (
  id               TINYINT UNSIGNED NOT NULL DEFAULT 1
                     COMMENT 'Always 1. Single-row table — see the CHECK below.',
  max_file_bytes   BIGINT UNSIGNED  NOT NULL
                     COMMENT '§4B.4 per-file cap. Default 10 MB. Bounded above by the container multipart limit at runtime.',
  max_ticket_bytes BIGINT UNSIGNED  NOT NULL
                     COMMENT '§4B.4 per-ticket total. Default 50 MB. Never below max_file_bytes.',
  max_files        SMALLINT UNSIGNED NOT NULL
                     COMMENT '§4B.4 per-ticket file count. Default 20.',
  updated_at       DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
                     COMMENT 'UTC, like every timestamp in this schema.',
  updated_by       BIGINT           NULL
                     COMMENT 'resources.id of the administrator who last saved. NULL for the seeded row.',

  PRIMARY KEY (id),

  CONSTRAINT ck_attachment_settings_singleton
    CHECK (id = 1),
  CONSTRAINT ck_attachment_settings_max_file_bytes
    CHECK (max_file_bytes BETWEEN 1 AND 104857600),
  CONSTRAINT ck_attachment_settings_max_files
    CHECK (max_files BETWEEN 1 AND 200),
  CONSTRAINT ck_attachment_settings_ticket_covers_file
    CHECK (max_ticket_bytes >= max_file_bytes)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
  COMMENT = 'C-027 · §4B.4 attachment caps, administrator-editable. Exactly one row, id = 1.';

-- §4B.4's stated defaults, so the enforced values are identical to
-- C-025's the moment this runs. 10 MB / 50 MB / 20.
INSERT INTO attachment_settings (id, max_file_bytes, max_ticket_bytes, max_files)
VALUES (1, 10485760, 52428800, 20);
