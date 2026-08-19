-- ---------------------------------------------------------------------
-- A-065 · §7.8's "All reports schedulable by email (daily/weekly/monthly)".
--
-- Two tables, because a schedule and a run of it are different facts with
-- different lifetimes. The schedule is a standing instruction somebody can
-- edit or cancel; a run is what happened at 06:00 on a Tuesday, and it is
-- the thing an auditor asks about — "who was sent the SLA breach report
-- last month, and what was in it". Folding the last run's outcome onto the
-- schedule row would keep exactly one answer and overwrite the rest.
--
-- WHY SCOPE IS NOT STORED HERE, AND THAT IS THE WHOLE SECURITY DESIGN
--
-- A report is scoped to whoever runs it (§2, ReportScope). A schedule runs
-- with nobody logged in, so the obvious design is to freeze the creator's
-- role and projects onto the row at creation and re-apply them later.
--
-- That is precisely wrong, and quietly: roles change. A PM who created a
-- schedule and was later moved to Developer would go on receiving
-- project-wide figures every Monday, from a row that recorded what they
-- used to be. Nobody would notice, because the mail keeps arriving and
-- looks the same. The same applies to project membership — leaving a
-- project would not stop that project's rows.
--
-- So only `created_by` is stored, and the runner re-reads that user's
-- CURRENT role and CURRENT project memberships on every run. A demotion
-- narrows the next email; leaving the organisation stops it. The schedule
-- can therefore never be more privileged than the person it belongs to is
-- at the moment it fires. `is_active` is the manual half of the same idea.
--
-- WHY RECIPIENTS MUST BE USERS, AND ARE STILL STORED AS EMAILS
--
-- The contract types `recipients` as an array of email addresses, and it
-- stays that shape on the wire. What is enforced above this table is that
-- each address belongs to an ACTIVE user: the mail carries a link to an
-- authenticated download, so an address with no account cannot open it and
-- would receive a permanent invitation to a 401.
--
-- Stored as text rather than as user ids on purpose. The report was
-- addressed to a person at an address; if that user is later deleted, the
-- history of who received last quarter's audit extract must not be erased
-- by a foreign key, which is the same argument `email_log.to_email` makes
-- one table over.
--
-- THE FILE IS NOT IN THE EMAIL
--
-- `report_schedule_runs.storage_key` holds the object-store key of the
-- generated file, and the mail carries a link rather than an attachment.
-- ImportReportStore already argues this for the import error report: a
-- report is a verbatim extract of the organisation's data, and an
-- attachment is an uncontrolled copy that outlives every permission change
-- made afterwards — sitting in an inbox, in a mail archive and in every
-- relay between here and there. A link is fetched by somebody who is
-- logged in now, and the download re-applies row scope at that moment.
-- ---------------------------------------------------------------------

CREATE TABLE report_schedules (
  id             BIGINT        NOT NULL AUTO_INCREMENT,

  -- Not a foreign key: reports are a Java catalogue (ReportCatalogue), not
  -- a table. Validated against it at creation, and re-checked on every run
  -- so a report withdrawn from the catalogue stops rather than throws.
  report_key     VARCHAR(60)   NOT NULL,

  cadence        VARCHAR(10)   NOT NULL,
  format         VARCHAR(10)   NOT NULL DEFAULT 'xlsx',

  -- JSON arrays/objects rather than child tables. Both are read only as a
  -- whole, by one owner, and never queried across schedules — a
  -- report_schedule_recipients table would be three joins to answer "who is
  -- on this schedule" and would buy nothing that is ever asked.
  recipients     JSON          NOT NULL,
  -- The filters the viewer had applied when the schedule was created:
  -- projectId, resourceId, clientId, taskTypeId, level. NOT the date range
  -- — see next_run_at's note. NULL means "no filters", which is different
  -- from '{}' only in intent and is stored as NULL for readability.
  parameters     JSON          NULL,

  created_by     BIGINT        NOT NULL,
  is_active      TINYINT(1)    NOT NULL DEFAULT 1,

  -- The clock. A due schedule is one whose next_run_at has passed, which
  -- makes the sweep a single indexed range scan rather than cron parsing
  -- per row. Advanced *after* a run, so a worker that dies mid-run leaves
  -- the row due and the next pass retries it.
  --
  -- The reporting period is derived from this at run time and never
  -- stored: a daily run covers yesterday, a weekly one last week, a monthly
  -- one last month. Storing the window would freeze it, and the second run
  -- would email a copy of the first.
  next_run_at    DATETIME(6)   NOT NULL,
  last_run_at    DATETIME(6)   NULL,

  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),

  -- The sweep's claim query, every few minutes for ever: active schedules
  -- whose time has come. Leading is_active because it eliminates the
  -- cancelled rows before the range scan rather than after it.
  KEY ix_report_schedules_due (is_active, next_run_at),
  -- "My scheduled reports", the only other read.
  KEY ix_report_schedules_owner (created_by, created_at),

  CONSTRAINT ck_report_schedules_cadence
    CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
  -- VARCHAR + CHECK rather than ENUM, PLAN.md §3.1's standing substitution:
  -- a MySQL ENUM compares by ordinal and renumbers its set on every ALTER.
  CONSTRAINT ck_report_schedules_format
    CHECK (format IN ('xlsx', 'csv', 'pdf')),
  -- A schedule with no recipients would run, store a file and tell nobody.
  CONSTRAINT ck_report_schedules_recipients
    CHECK (JSON_TYPE(recipients) = 'ARRAY' AND JSON_LENGTH(recipients) >= 1),

  -- RESTRICT by omission: deleting a user with live schedules should fail
  -- loudly rather than cascade away a standing instruction that is still
  -- emailing people. Deactivating them is what stops the mail, and the
  -- runner checks that on every pass.
  CONSTRAINT fk_report_schedules_creator
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- report_schedule_runs — what actually happened, one row per firing.
--
-- Append-only by intent but NOT by trigger: this is not one of the four
-- hash-chained tables (CLAUDE.md), and claiming that guarantee for a table
-- that does not have it would be worse than not claiming it. Nothing in the
-- service layer updates a run after it is written, except the single
-- status transition from RUNNING to SUCCEEDED or FAILED.
--
-- A FAILED run is recorded rather than dropped. A schedule that silently
-- produces nothing is indistinguishable from one nobody looks at, and the
-- failure people actually hit — a report withdrawn from the catalogue, or
-- a creator who lost access to every project — needs to be readable on the
-- screen that manages the schedule.
-- ---------------------------------------------------------------------
CREATE TABLE report_schedule_runs (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  schedule_id     BIGINT        NOT NULL,

  -- When it fired, and what window it covered. Both stored, because
  -- "the report you got on the 8th" and "the week it was about" are
  -- different questions and a reader of the list asks the second.
  run_at          DATETIME(6)   NOT NULL,
  period_from     DATE          NOT NULL,
  period_to       DATE          NOT NULL,

  status          VARCHAR(12)   NOT NULL,
  row_count       INT           NULL,
  -- What the rows were narrowed to, in words, exactly as the export writes
  -- into the file itself. Recorded because it is a property of the run and
  -- not of the schedule: the same schedule narrows differently after its
  -- owner changes role, and that difference is the thing worth seeing.
  applied_scope   VARCHAR(200)  NULL,

  storage_key     VARCHAR(400)  NULL,
  file_name       VARCHAR(200)  NULL,
  error_text      TEXT          NULL,

  PRIMARY KEY (id),
  -- The list on the manage screen: this schedule's runs, newest first.
  KEY ix_report_schedule_runs_schedule (schedule_id, run_at),

  CONSTRAINT ck_report_schedule_runs_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
  -- A succeeded run has a file; a failed one has a reason. Enforced here
  -- rather than trusted, because the download route reads storage_key and
  -- a SUCCEEDED row without one is a 500 waiting for the first person to
  -- click it.
  CONSTRAINT ck_report_schedule_runs_outcome
    CHECK ((status <> 'SUCCEEDED' OR storage_key IS NOT NULL)
       AND (status <> 'FAILED'    OR error_text  IS NOT NULL)),

  -- CASCADE, unlike the schedule's own FK. A run has no meaning without its
  -- schedule and holds no fact about anybody else; deleting the schedule is
  -- deliberate and rare, and orphan runs would be unreachable rows that the
  -- download route could still serve by id.
  CONSTRAINT fk_report_schedule_runs_schedule
    FOREIGN KEY (schedule_id) REFERENCES report_schedules (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The mail body for SCHEDULED_REPORT.
--
-- Seeded rather than hard-coded, because notification_templates is B-022's
-- master and §7.4's S-14 lets an Admin edit every other mail this system
-- sends. A body compiled into the worker would be the one mail they cannot
-- change.
--
-- `{{portal_url}}` is the new merge tag this task adds. Before it, a tag
-- could only be filled from a ticket, so no mail that is not about a ticket
-- could contain a working link at all — the daily digest has the same gap
-- and can now close it.
--
-- The link goes to the schedules screen rather than straight to the file:
-- a URL that downloads on sight would be a capability sitting in a mail
-- archive, which is the thing ImportReportStore refuses to mint.
-- ---------------------------------------------------------------------
--
-- `recipients` is REQUESTER, and it is descriptive here rather than
-- load-bearing. V20260815_1100 made the column NOT NULL with no default
-- and gave it a closed vocabulary of positions **relative to one ticket**
-- — ASSIGNEE, WATCHERS, STAGE_OWNER — resolved per send. This event has
-- no ticket and no position to resolve: its addresses are chosen when the
-- schedule is created and stored on `report_schedules.recipients`, and
-- ScheduledReportRunner enqueues one mail per address explicitly, exactly
-- as DigestScheduler does for DAILY_DIGEST.
--
-- REQUESTER — "whoever asked for the thing being answered" — is the
-- closest existing token and the one that overstates least; a schedule is
-- asked for by the person who created it. The honest value would be a new
-- vocabulary entry meaning "named on the schedule", and that is a change
-- to Stream D's NotificationRecipient enum and its validation service for
-- a column nothing reads on this path. Flagged rather than taken.
INSERT INTO notification_templates (event_code, channel, recipients, subject_template, body_template)
VALUES ('SCHEDULED_REPORT', 'EMAIL', 'REQUESTER', NULL,
        CONCAT(
          '<p>Your scheduled report is ready.</p>',
          '<p><a href="{{portal_url}}/reports/schedules">Open your scheduled reports</a> ',
          'to download it.</p>',
          '<p style="color:#6B7280;font-size:12px;">You are receiving this because somebody ',
          'at {{org}} scheduled this report to you. The download asks you to sign in, and ',
          'shows you the rows your own role allows.</p>'));
