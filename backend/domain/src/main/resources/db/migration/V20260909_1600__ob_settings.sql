-- =====================================================================
-- B-113 · ob_settings — the constants PHASE-2-BUILD-PLAN §2 locked,
-- held as configuration.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-113 — "OB-11 and OB-12 —
--            TAT settings and email templates — amber threshold,
--            scanner cadence, the escalation matrix as configuration
--            rather than constants"
--          contracts/openapi.yaml `getObSettings` / `updateObSettings`
--          docs/Onboarding-Module-Plan.md §5.11, §9 (OB-11)
--
-- WHY THESE THREE AND NOT A GENERIC KEY-VALUE TABLE.
-- A `settings(key, value)` table is the tempting shape and it is the
-- wrong one here. Every reader would then parse its own value out of a
-- string, the CHECK constraints below could not exist, and "is 75 a
-- percentage or a count" would be answerable only by reading whichever
-- service happened to consume it. Three typed columns and a typed child
-- table make the shape the schema's business rather than each caller's.
--
-- WHY A SINGLETON ROW RATHER THAN ONE PER ORGANISATION.
-- EduTrack is deployed per organisation — there is no tenant column
-- anywhere in this schema, and adding the first one here would be
-- inventing multi-tenancy in a settings table. `ck_ob_settings_singleton`
-- pins the row at id 1 so a second INSERT is refused by the database
-- rather than by whichever service remembered to check.
--
-- WHAT THIS REPLACES.
-- `edutrack.ob-stats.amber-share` — B-120's property, whose own note says
-- "B-113 is where it becomes a row on the TAT settings screen, and that
-- key is the single thing it replaces". The property stays readable as
-- the seed default so a deployment that never opens OB-11 behaves exactly
-- as it does today.
--
-- The escalation matrix replaces the `reporting_manager_id` join B-114's
-- digest uses — its note says "§5.11's escalation matrix is where this
-- becomes configuration; that is B-113, and this join is the single thing
-- it replaces". That replacement is a code change in the scanner rather
-- than anything this file can express; the rungs below are what it will
-- read.
--
-- WHY updated_by IS NULLABLE.
-- The seeded row was written by nobody. A NOT NULL would need a sentinel
-- user, and "system" as a row in `users` is an account that can be
-- granted things.
-- =====================================================================

CREATE TABLE ob_settings (
  id                       BIGINT      NOT NULL,

  -- Warn at this percentage of a step's TAT. Seeded at 75 — plan §1.1 #3's
  -- "Amber before Red".
  --
  -- Capped below 100 rather than at it: an amber threshold of 100% fires at
  -- the same moment as the breach it is supposed to precede, which is a
  -- warning with no warning in it. The contract states the same bound, and
  -- it is repeated here because a service is not the only thing that can
  -- write a row.
  amber_threshold_percent  SMALLINT    NOT NULL DEFAULT 75,

  -- How often the TAT sweep runs. Seeded at 5. Bounded above at 60 so a
  -- misconfiguration cannot quietly turn the scanner off for a day.
  scanner_interval_minutes SMALLINT    NOT NULL DEFAULT 5,

  updated_by               BIGINT      NULL,
  updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT fk_ob_settings_updated_by
    FOREIGN KEY (updated_by) REFERENCES users (id),
  CONSTRAINT ck_ob_settings_singleton
    CHECK (id = 1),
  CONSTRAINT ck_ob_settings_amber
    CHECK (amber_threshold_percent BETWEEN 1 AND 99),
  CONSTRAINT ck_ob_settings_scanner
    CHECK (scanner_interval_minutes BETWEEN 1 AND 60)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The escalation ladder — one row per ObEscalationLevel.
--
-- WHY A CHILD TABLE AND NOT THREE COLUMN PAIRS ON ob_settings.
-- `l1_after_hours, l1_recipient, l2_after_hours, ...` would work and is
-- shorter. It is not used because every consumer then reads the ladder by
-- name rather than by iteration — the scanner would carry a switch over
-- three literals, and the ordering rule ("rungs ascend") would have to be
-- written as pairwise comparisons rather than as an ORDER BY.
--
-- WHY EXACTLY THREE ROWS, AND WHY THAT IS NOT CONFIGURABLE.
-- `ObEscalationLevel` is closed and `uq_ob_escalations_open (step_id,
-- level, open_key)` is keyed on it. A fourth rung would have no level to
-- be, and a matrix that allowed one would be configuration the database
-- cannot store. The FK to the level vocabulary plus the primary key does
-- the enforcing: three levels exist, each may appear once. The INTERVALS
-- are configuration; the number of rungs is not.
--
-- WHY recipient IS A ROLE AND NEVER A user_id.
-- Storing a person sends L2 to somebody who has since left, and needs
-- re-editing every time the team changes. The role is resolved when the
-- rung fires, leave-aware against the working calendar — which is also
-- why BACKUP_OWNER is a distinct value rather than a fallback baked into
-- STEP_OWNER (plan §1.1 #4).
-- ---------------------------------------------------------------------

CREATE TABLE ob_escalation_rungs (
  level              VARCHAR(2)  NOT NULL,   -- L1|L2|L3

  -- Working hours after the breach, NOT wall-clock — CLAUDE.md's calendar
  -- rule, and the reason a Friday-evening breach escalates on Monday
  -- morning rather than at two on Saturday. Zero on L1, which fires at the
  -- breach itself, so the lower bound is 0 and not 1.
  after_working_hours SMALLINT   NOT NULL,

  recipient          VARCHAR(20) NOT NULL,
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (level),
  CONSTRAINT ck_ob_escalation_rungs_level
    CHECK (level IN ('L1', 'L2', 'L3')),
  CONSTRAINT ck_ob_escalation_rungs_hours
    CHECK (after_working_hours BETWEEN 0 AND 720),
  CONSTRAINT ck_ob_escalation_rungs_recipient
    CHECK (recipient IN ('STEP_OWNER', 'BACKUP_OWNER', 'ONBOARDING_MANAGER', 'OB_ADMIN'))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- The ascending rule — L1 before L2 before L3 — is NOT a CHECK. MySQL
-- CHECKs are per row and cannot see a sibling; expressing it would need a
-- trigger, and a trigger that rejects a mid-transaction ordering would
-- refuse a legitimate save that swaps two rungs. It is enforced in
-- ObSettingsService, once, over the whole submitted ladder, which is the
-- only place that ever sees all three at the same time. Stated here so the
-- absence reads as a decision rather than an omission.


-- ---------------------------------------------------------------------
-- Seeds — PHASE-2-BUILD-PLAN §2's locked values, as the starting point
-- rather than as the contract. A deployment that never opens OB-11
-- behaves exactly as it does today.
-- ---------------------------------------------------------------------

INSERT INTO ob_settings (id, amber_threshold_percent, scanner_interval_minutes)
VALUES (1, 75, 5);

INSERT INTO ob_escalation_rungs (level, after_working_hours, recipient) VALUES
  ('L1', 0, 'STEP_OWNER'),
  ('L2', 4, 'BACKUP_OWNER'),
  ('L3', 8, 'ONBOARDING_MANAGER');
